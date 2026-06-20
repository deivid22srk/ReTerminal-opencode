package com.rk.opencode

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.terminal.service.OpencodeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs and observes the `opencode serve` process.
 *
 * The actual process is launched from [OpencodeService] (a foreground service), but all the
 * logic — start, stop, log capture, port probing — lives here so it can be unit tested and
 * referenced from the UI / service alike.
 */
object OpencodeServer {

    private const val TAG = "OpencodeServer"

    /** Maximum number of log lines we keep in memory for the UI. */
    private const val MAX_LOG_LINES = 1000

    /** Polling interval used to detect when the server is reachable. */
    private const val HEALTH_POLL_MS = 500L

    /** Server lifecycle states observed by the UI. */
    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    /** Current state, observed by Compose. */
    val state = mutableStateOf(State.STOPPED)

    /** Latest URL the server is (or will be) listening on. */
    val url = mutableStateOf<String?>(null)

    /** Latest detected port. */
    val port = mutableStateOf(OpencodeManager.DEFAULT_PORT)

    /** Ring-buffer-ish log captured from the process stdout+stderr. */
    val logs = mutableStateOf<List<String>>(emptyList())

    /** Last error message, if any. */
    val lastError = mutableStateOf<String?>(null)

    /** Pid of the running opencode process, or -1 if not running.
     *  Best-effort: try Process.pid() via reflection if available (Java 9+), else -1. */
    val pid = AtomicInteger(-1)

    /** Best-effort pid extraction (Process.pid() only exists on Java 9+/API 26+).
     *  On older runtimes the reflection returns null and we just store -1. */
    private fun Process.pidSafe(): Int = runCatching {
        val m = Process::class.java.getMethod("pid")
        (m.invoke(this) as Long).toInt()
    }.getOrDefault(-1)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Currently running process; null when stopped. */
    @Volatile
    private var process: Process? = null

    /** Reader jobs for stdout/stderr. */
    private var readerJob: Job? = null

    /** Health-check job. */
    private var healthJob: Job? = null

    /** Append a line to the in-memory log buffer (capped at MAX_LOG_LINES). */
    private fun appendLog(line: String) {
        val ts = System.currentTimeMillis()
        val stamped = "[$ts] $line"
        val current = logs.value
        val next = if (current.size >= MAX_LOG_LINES) {
            current.drop(current.size - MAX_LOG_LINES + 1) + stamped
        } else {
            current + stamped
        }
        logs.value = next
    }

    /** Clears the captured log buffer. */
    fun clearLogs() {
        logs.value = emptyList()
    }

    /**
     * Starts the opencode server with the given [hostname] and [port].
     * If a process is already running, this is a no-op.
     *
     * Should be called from the foreground service.
     */
    fun start(
        context: Context,
        hostname: String = OpencodeManager.DEFAULT_HOSTNAME,
        port: Int = OpencodeManager.DEFAULT_PORT,
    ) {
        if (process != null) {
            Log.w(TAG, "start() called but a process is already running")
            return
        }
        if (!OpencodeManager.isInstalled()) {
            lastError.value = "opencode não está instalado. Importe o tar.gz primeiro."
            state.value = State.FAILED
            return
        }

        // Ensure the service is up so the process is tied to a foreground lifecycle.
        val svc = Intent(context, OpencodeService::class.java).apply {
            action = OpencodeService.ACTION_START
            putExtra(OpencodeService.EXTRA_HOSTNAME, hostname)
            putExtra(OpencodeService.EXTRA_PORT, port)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, svc)
    }

    /**
     * Internal: launches the actual `opencode serve` process.
     * Called from the service after it has promoted itself to the foreground.
     */
    internal fun launchProcess(hostname: String, port: Int) {
        scope.launch {
            try {
                val bin = OpencodeManager.binaryFile.absolutePath
                val cmd = mutableListOf(
                    bin, "serve",
                    "--hostname", hostname,
                    "--port", port.toString(),
                )
                Log.i(TAG, "Starting opencode: ${cmd.joinToString(" ")}")

                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                pb.directory(OpencodeManager.homeDir)
                pb.environment().apply {
                    put("HOME", OpencodeManager.homeDir.absolutePath)
                    put("PATH", "${OpencodeManager.binaryDir.absolutePath}:/system/bin:/system/xbin")
                    put("TMPDIR", application!!.cacheDir.absolutePath)
                    put("XDG_CONFIG_HOME", OpencodeManager.homeDir.child(".config").absolutePath.also { File(it).mkdirs() })
                    put("XDG_DATA_HOME", OpencodeManager.homeDir.child(".local/share").absolutePath.also { File(it).mkdirs() })
                    put("XDG_CACHE_HOME", OpencodeManager.homeDir.child(".cache").absolutePath.also { File(it).mkdirs() })
                }

                state.value = State.STARTING
                lastError.value = null
                OpencodeServer.port.value = port
                url.value = "http://$hostname:$port"

                val p = pb.start()
                process = p
                pid.set(p.pidSafe())

                appendLog(">>> opencode serve --hostname $hostname --port $port")
                appendLog(">>> pid=${p.pidSafe()}")

                // Start the reader coroutine.
                readerJob = scope.launch {
                    val reader = BufferedReader(InputStreamReader(p.inputStream))
                    var line = reader.readLine()
                    while (line != null) {
                        appendLog(line)
                        // Detect "server listening on http://..."
                        if (line.contains("server listening on") || line.contains("listening on")) {
                            state.value = State.RUNNING
                        }
                        line = reader.readLine()
                    }
                    try { reader.close() } catch (_: Exception) {}
                }

                // Start a watcher coroutine that detects process exit.
                scope.launch {
                    val code = p.waitFor()
                    appendLog("<<< process exited with code $code")
                    if (state.value != State.STOPPED) {
                        if (code != 0 && state.value != State.RUNNING) {
                            state.value = State.FAILED
                            lastError.value = "opencode encerrou com código $code"
                        } else if (code != 0) {
                            state.value = State.FAILED
                            lastError.value = "opencode encerrou com código $code"
                        } else {
                            state.value = State.STOPPED
                        }
                    }
                    process = null
                    pid.set(-1)
                }

                // Start a health-check coroutine that polls the server URL.
                healthJob = scope.launch {
                    var attempts = 0
                    while (state.value == State.STARTING && attempts < 60) {
                        delay(HEALTH_POLL_MS)
                        if (isReachable(hostname, port)) {
                            state.value = State.RUNNING
                            appendLog(">>> health check OK at http://$hostname:$port")
                            break
                        }
                        attempts++
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to launch opencode process", e)
                state.value = State.FAILED
                lastError.value = e.message ?: "IOException ao iniciar processo"
                process = null
                pid.set(-1)
            }
        }
    }

    /** Stops the running opencode process gracefully, then forcibly if needed. */
    fun stop(context: Context? = null) {
        if (context != null) {
            val svc = Intent(context, OpencodeService::class.java).apply {
                action = OpencodeService.ACTION_STOP
            }
            androidx.core.content.ContextCompat.startForegroundService(context, svc)
        } else {
            stopInternal()
        }
    }

    internal fun stopInternal() {
        val p = process
        if (p != null) {
            appendLog(">>> stopping opencode process (pid=${p.pidSafe()})")
            runCatching { p.destroy() } // SIGTERM
            // Give it a moment, then destroyForcibly if still alive.
            scope.launch {
                delay(2000)
                if (p.isAlive) {
                    runCatching { p.destroyForcibly() }
                }
            }
        }
        readerJob?.cancel()
        readerJob = null
        healthJob?.cancel()
        healthJob = null
        process = null
        pid.set(-1)
        state.value = State.STOPPED
    }

    /** Quick TCP probe to see if anything is listening on the given port. */
    private suspend fun isReachable(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 500)
            socket.close()
            true
        }.getOrDefault(false)
    }
}
