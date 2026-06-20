package com.rk.opencode

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
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
 * - Installing from a user-selected tar.gz
 * - Verifying the install
 * - Providing the binary path / version
 * - Uninstalling
 *
 * The opencode tarball (opencode-linux-arm64-musl.tar.gz) is a single statically-linked
 * ARM64 binary built against musl libc, which can be executed directly inside the Android
 * app's private data directory (no proot / glibc needed).
 */
object OpencodeManager {

    private const val TAG = "OpencodeManager"

    /** Name of the binary inside the tarball. */
    private const val BINARY_NAME = "opencode"

    /** The directory where the opencode binary lives (re-uses ReTerminal's localBinDir). */
    val binaryDir: File get() = localBinDir()

    /** The actual opencode binary file. */
    val binaryFile: File get() = binaryDir.child(BINARY_NAME)

    /** Directory used as $HOME when running opencode, so its config/sessions live here. */
    val homeDir: File get() = localDir().child("opencode-home").also { if (!it.exists()) it.mkdirs() }

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

    /** Returns the version string reported by `opencode --version`, or null on failure. */
    suspend fun version(): String? = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext null
        runCatching {
            val process = ProcessBuilder(binaryFile.absolutePath, "--version")
                .redirectErrorStream(true)
                .directory(homeDir)
                .apply {
                    environment().apply {
                        put("HOME", homeDir.absolutePath)
                        put("PATH", "${binaryDir.absolutePath}:/system/bin:/system/xbin")
                        put("TMPDIR", application!!.cacheDir.absolutePath)
                    }
                }
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            out.ifBlank { null }
        }.getOrNull()
    }

    /**
     * Installs (or replaces) the opencode binary from a user-selected tar.gz Uri.
     *
     * Steps:
     *  1. Copy the SAF uri content into a temp file inside the app cache.
     *  2. Stream-decompress (gzip + tar) and look for the `opencode` entry.
     *  3. Copy the entry to [binaryFile] and chmod +x.
     *  4. Verify with `opencode --version`.
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

            // 3) Move the extracted binary into localBinDir/opencode and chmod +x.
            installStatus.value = "Instalando binário…"
            binaryDir.mkdirs()
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
            installProgress.value = 0.85f

            // 4) Verify the install with --version.
            installStatus.value = "Verificando instalação…"
            val v = version()
            if (v == null) {
                // Don't hard-fail — some builds print nothing on --version.
                // The binary is installed; the user can still try to start the server.
                Log.w(TAG, "opencode --version returned nothing, but binary is installed.")
            } else {
                Log.i(TAG, "opencode installed: $v")
            }

            installProgress.value = 1f
            installStatus.value = "Instalação concluída."
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

    /** Removes the opencode binary. */
    fun uninstall() {
        runCatching { binaryFile.delete() }
        runCatching { homeDir.deleteRecursively() }
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
                        val skipped = tis.available
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
