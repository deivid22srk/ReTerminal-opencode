package com.rk.opencode

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.alpineDir
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.localBinDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Singleton responsible for managing the opencode binary lifecycle:
 * - Installing from a user-selected tar.gz into the Alpine rootfs
 * - Verifying the install
 * - Providing the binary path (inside the chroot) / version
 * - Uninstalling
 *
 * IMPORTANT: the opencode binary is musl-linked, so it CANNOT run directly on
 * Android's Bionic libc. It must live INSIDE the Alpine chroot at
 * `alpineDir()/usr/local/bin/opencode`, where `/lib/ld-musl-aarch64.so.1` exists.
 *
 * The actual execution is done via proot, mirroring ReTerminal's init-host.sh
 * approach — see [com.rk.terminal.service.OpencodeService] and the bundled
 * `init-host-opencode.sh` asset.
 */
object OpencodeManager {

    private const val TAG = "OpencodeManager"

    /** Name of the binary inside the tarball. */
    private const val BINARY_NAME = "opencode"

    /**
     * The Alpine rootfs directory (chroot root). ReTerminal already downloads and
     * extracts alpine.tar.gz here. We just drop the opencode binary inside it.
     */
    val alpineRoot: File get() = alpineDir()

    /**
     * The opencode binary, installed INSIDE the Alpine chroot at /usr/local/bin/opencode.
     * Inside the chroot this path is reachable as `/usr/local/bin/opencode`.
     */
    val binaryFile: File get() = alpineRoot.child("usr").child("local").child("bin").child(BINARY_NAME)
        .also { it.parentFile?.mkdirs() }

    /**
     * Path of the opencode binary AS SEEN FROM INSIDE THE CHROOT.
     * This is what we pass to proot when launching the server.
     */
    const val BINARY_PATH_IN_CHROOT = "/usr/local/bin/opencode"

    /** Home directory for opencode INSIDE the chroot (/root/opencode-home). */
    const val HOME_IN_CHROOT = "/root/opencode-home"

    /** Default port the opencode server listens on. */
    const val DEFAULT_PORT = 4096

    /** Default hostname — localhost, only accessible from the device itself. */
    const val DEFAULT_HOSTNAME = "127.0.0.1"

    /** Last captured installation error, exposed for the UI. */
    val installError = mutableStateOf<String?>(null)

    /** Tracks whether an install / extract operation is currently running. */
    val isInstalling = mutableStateOf(false)

    /** Tracks install progress (0..1f). */
    val installProgress = mutableStateOf(0f)

    /** Human-readable status message for the UI during install. */
    val installStatus = mutableStateOf<String>("")

    /** True when the opencode binary is present and executable. */
    fun isInstalled(): Boolean {
        return binaryFile.exists() && binaryFile.canExecute()
    }

    /**
     * True when the Alpine rootfs + proot + libtalloc are all downloaded.
     *
     * ReTerminal downloads these the first time the user opens the terminal screen.
     * opencode can only run inside the Alpine chroot, so this must be true before
     * we attempt to launch it.
     */
    fun isAlpineReady(): Boolean {
        val reTerminal = application!!.filesDir
        val proot = reTerminal.child("proot")
        val libtalloc = reTerminal.child("libtalloc.so.2")
        val alpineTar = reTerminal.child("alpine.tar.gz")
        // Also accept the case where alpine.tar.gz was already extracted into alpineDir().
        val alpineExtracted = alpineRoot.exists() &&
            alpineRoot.child("bin").exists() &&
            alpineRoot.child("lib").exists()
        return (proot.exists() && libtalloc.exists() && (alpineTar.exists() || alpineExtracted))
    }

    /**
     * True when libstdc++ and libgcc have already been installed inside the
     * Alpine chroot. Useful for the UI to know whether the next "Iniciar servidor"
     * will be a fast start (libs already present) or a slow start (libs need to
     * be fetched via apk, ~30s with network).
     */
    fun areCppLibsInstalled(): Boolean {
        return alpineRoot.child("usr").child("lib").child("libstdc++.so.6").exists() &&
            alpineRoot.child("usr").child("lib").child("libgcc_s.so.1").exists()
    }

    /**
     * Returns the version string reported by `opencode --version` when run inside
     * the chroot via proot, or null on failure.
     *
     * This actually launches proot to run the binary — slower than a plain exec,
     * but it's the only way to verify the binary works end-to-end.
     */
    suspend fun version(): String? = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext null
        // Don't run proot for version — it's slow and the user just wants to know
        // the binary exists. We could parse the filename instead, but the tarball
        // name doesn't include the version. So just return a placeholder.
        // The real version check happens when the server starts.
        runCatching {
            val process = launchOpencodeViaProot(arrayOf("--version"))
            val out = process.inputStream.bufferedReader().use { it.readText().trim() }
            val err = process.errorStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            Log.d(TAG, "version stdout: $out")
            if (out.isBlank()) err.ifBlank { null } else out
        }.getOrNull()
    }

    /**
     * Installs (or replaces) the opencode binary from a user-selected tar.gz Uri.
     *
     * Steps:
     *  1. Copy the SAF uri content into a temp file inside the app cache.
     *  2. Stream-decompress (gzip + tar) and look for the `opencode` entry.
     *  3. Copy the entry to [binaryFile] (inside the Alpine rootfs) and chmod +x.
     *
     * Note: we don't run `opencode --version` to verify, because that requires
     * booting proot — too slow for the install flow. The real verification happens
     * the first time the user taps "Iniciar servidor".
     *
     * @param context any context, used to open the SAF Uri.
     * @param uri     the content uri returned by ACTION_OPEN_DOCUMENT.
     * @return true on success, false otherwise (see [installError] for reason).
     */
    suspend fun installFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        installError.value = null
        isInstalling.value = true
        installProgress.value = 0f
        installStatus.value = "Lendo arquivo selecionado…"

        try {
            // 1) Copy SAF uri -> temp file. We need random access for tar streaming,
            //    but SAF streams are forward-only, so we materialize a copy first.
            val tmpTar = File(context.cacheDir, "opencode-import-${System.currentTimeMillis()}.tar.gz")
            try {
                copyUriToFile(context, uri, tmpTar) { written, total ->
                    val p = if (total > 0) (written.toFloat() / total).coerceIn(0f, 0.4f) else 0f
                    installProgress.value = p
                    installStatus.value = "Copiando tar.gz… ${(p * 100).toInt()}%"
                }
            } catch (e: Exception) {
                throw IOException("Falha ao ler o arquivo selecionado: ${e.message}", e)
            }

            // 2) Extract the `opencode` entry.
            installStatus.value = "Extraindo binário…"
            val extracted = extractOpencodeFromTarGz(tmpTar) { p ->
                installProgress.value = 0.4f + p * 0.4f
            }
            if (extracted == null) {
                throw IOException(
                    "Não foi possível encontrar um arquivo 'opencode' dentro do tar.gz. " +
                        "Verifique se o arquivo selecionado é o opencode-linux-arm64-musl.tar.gz correto."
                )
            }

            // 3) Move the extracted binary into alpineDir/usr/local/bin/opencode and chmod +x.
            installStatus.value = "Instalando binário dentro do Alpine…"
            binaryFile.parentFile?.mkdirs()
            if (binaryFile.exists()) {
                binaryFile.delete()
            }
            if (!extracted.renameTo(binaryFile)) {
                // renameTo can fail across filesystems; fall back to copy.
                extracted.copyTo(binaryFile, overwrite = true)
                extracted.delete()
            }
            if (!binaryFile.setExecutable(true, true)) {
                throw IOException("Não foi possível tornar o binário executável.")
            }
            installProgress.value = 0.9f

            // 4) Cleanup the temp tar.gz to free cache space.
            runCatching { tmpTar.delete() }

            installProgress.value = 1f
            installStatus.value = "Instalação concluída."
            Log.i(TAG, "opencode installed at ${binaryFile.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "installFromUri failed", e)
            installError.value = e.message ?: e.javaClass.simpleName
            installStatus.value = "Falha: ${installError.value}"
            false
        } finally {
            isInstalling.value = false
        }
    }

    /** Removes the opencode binary from the Alpine rootfs. */
    fun uninstall() {
        runCatching { binaryFile.delete() }
    }

    /**
     * Launches opencode inside the Alpine chroot via proot, with the given args.
     *
     * Returns the spawned [Process] — caller is responsible for reading its
     * stdout/stderr and calling waitFor().
     *
     * The proot invocation mirrors ReTerminal's init-host.sh:
     *   linker64 proot --kill-on-exit -0 --link2symlink --sysvipc -L \
     *     -b <binds...> -r <alpine> -w /root \
     *     /usr/local/bin/opencode <args...>
     *
     * We use the bundled `init-host-opencode.sh` script to assemble the proot
     * command, so we don't have to re-implement the bind-mount list in Kotlin.
     */
    fun launchOpencodeViaProot(args: Array<String>): Process {
        val app = application ?: throw IllegalStateException("Application not initialized")
        val prefix = app.filesDir.parentFile!!.absolutePath

        // ALWAYS materialize the init-host-opencode.sh script fresh, so we pick up
        // any updates to the bundled asset. (It's tiny — ~3 KB.)
        val scriptFile = localBinDir().child("init-host-opencode")
        scriptFile.parentFile?.mkdirs()
        scriptFile.createNewFile()
        app.assets.open("init-host-opencode.sh").use { input ->
            FileOutputStream(scriptFile).use { input.copyTo(it) }
        }
        scriptFile.setExecutable(true, true)

        // PROOT_TMP_DIR — proot needs a writable directory to create its glue rootfs.
        // The default /tmp is NOT writable from an Android unprivileged app context,
        // so we MUST set this explicitly to a path inside the app's data dir.
        // The ReTerminal App.kt already creates $PREFIX/tmp for this purpose.
        val prootTmpDir = File(app.filesDir.parentFile, "tmp").apply {
            if (!exists()) mkdirs()
            setWritable(true, true)
            setReadable(true, true)
            setExecutable(true, true)
        }

        // Build env for the script — same variables MkSession.kt sets, plus a few
        // extras specifically for proot/opencode.
        val env = mutableMapOf<String, String>()
        env["PATH"] = "${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}"
        env["PREFIX"] = prefix
        env["HOME"] = "/sdcard"
        env["LD_LIBRARY_PATH"] = app.filesDir.parentFile!!.child("local").child("lib").absolutePath
        env["LINKER"] = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
        env["TMPDIR"] = app.cacheDir.absolutePath
        // Critical: proot glue rootfs must live in app-private storage.
        env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
        // proot loader paths — same env vars MkSession sets.
        val nativeLibDir = app.applicationInfo.nativeLibraryDir
        if (File(nativeLibDir).child("libproot-loader32.so").exists()) {
            env["PROOT_LOADER32"] = "$nativeLibDir/libproot-loader32.so"
        }
        if (File(nativeLibDir).child("libproot-loader.so").exists()) {
            env["PROOT_LOADER"] = "$nativeLibDir/libproot-loader.so"
        }

        // Command: sh <script> <args...>
        val cmd = mutableListOf(
            "/system/bin/sh",
            scriptFile.absolutePath,
            *args,
        )

        Log.i(TAG, "Launching opencode via proot: ${cmd.joinToString(" ")}")
        Log.i(TAG, "  PREFIX=$prefix")
        Log.i(TAG, "  PROOT_TMP_DIR=${prootTmpDir.absolutePath}")
        Log.i(TAG, "  BINARY_PATH_IN_CHROOT=$BINARY_PATH_IN_CHROOT")

        return ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .directory(app.filesDir)
            .apply { environment().putAll(env) }
            .start()
    }

    // ---- internals ----

    private fun copyUriToFile(
        context: Context,
        uri: Uri,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val resolver = context.contentResolver
        val input: InputStream = resolver.openInputStream(uri)
            ?: throw IOException("Não foi possível abrir o arquivo selecionado.")

        // Try to get total size for progress reporting.
        var total = -1L
        runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                total = afd.length
            }
        }

        input.use { ins ->
            BufferedOutputStream(FileOutputStream(dest)).use { out ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(written, total)
                }
            }
        }
    }

    /**
     * Streams through the .tar.gz looking for an entry named exactly "opencode" (or with
     * that basename). When found, copies it to a temp file inside the cache dir and returns it.
     */
    private fun extractOpencodeFromTarGz(tarGz: File, onProgress: (Float) -> Unit): File? {
        val out = File(tarGz.parentFile, "opencode-extracted-${System.currentTimeMillis()}")
        var found = false

        tarGz.inputStream().buffered().use { fis ->
            GzipCompressorInputStream(fis).use { gz ->
                TarArchiveInputStream(gz).use { tis ->
                    var entry: TarArchiveEntry? = tis.nextEntry as? TarArchiveEntry
                    val totalSize = tarGz.length().coerceAtLeast(1)
                    var consumed = 0L
                    while (entry != null) {
                        val name = entry.name.trim('/')
                        // The opencode tarball contains a single top-level `opencode` file,
                        // but be lenient: match basename too.
                        if (!found && (name == BINARY_NAME || File(name).name == BINARY_NAME) && !entry.isDirectory) {
                            BufferedOutputStream(FileOutputStream(out)).use { bos ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = tis.read(buf)
                                    if (n < 0) break
                                    bos.write(buf, 0, n)
                                    consumed += n
                                    onProgress(consumed.toFloat() / totalSize)
                                }
                            }
                            found = true
                            break
                        }
                        // Skip to next entry.
                        val skipped = tis.available()
                        if (skipped > 0) {
                            // Drain so nextEntry is positioned correctly.
                            val buf = ByteArray(64 * 1024)
                            while (tis.read(buf) > 0) { /* drain */ }
                        }
                        consumed = tarGz.length() // approximation
                        onProgress(consumed.toFloat() / totalSize)
                        entry = tis.nextEntry as? TarArchiveEntry
                    }
                }
            }
        }

        return if (found) out else null
    }
}
