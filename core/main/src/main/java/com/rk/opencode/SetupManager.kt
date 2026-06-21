package com.rk.opencode

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.alpineDir
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localLibDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * Manages the end-to-end first-run setup of the OpenCode integration.
 *
 * **Step ordering (as requested by the user):**
 *
 *   Step 1 — Install opencode: download from GitHub releases OR import via SAF.
 *            The user picks which release to install (or imports a tar.gz
 *            manually). This is the FIRST step because it's what the user
 *            actually came here to do.
 *
 *   Step 2 — Download Alpine rootfs + proot + libtalloc (~20 MB).
 *            Same files ReTerminal downloads, but exposed here with progress.
 *
 *   Step 3 — Extract Alpine and install libstdc++ + libgcc inside the chroot
 *            via `apk add`, streaming apk's output to the UI.
 *
 *   Step 4 — Done.
 *
 * Every step streams its progress + log output to [logLines] and [progress],
 * so the UI can show a "mini-terminal" of what's happening in real time.
 *
 * This replaces the old flow where the user had to open ReTerminal's terminal
 * screen first to trigger the Alpine download + apk setup. Now everything is
 * driven from the dedicated OpenCode setup screen — the user NEVER has to
 * open the terminal.
 */
object SetupManager {

    private const val TAG = "SetupManager"

    /** Minimum acceptable size for the opencode binary (50 MB).
     *  The real binary is ~160 MB; anything smaller means extraction failed
     *  or the user picked the wrong file. */
    private const val MIN_BINARY_SIZE_BYTES = 50L * 1024 * 1024

    /** ABI-specific Alpine/proot/talloc download URLs (same as ReTerminal's Downloader.kt). */
    private data class AbiUrls(val talloc: String, val proot: String, val alpine: String)

    private val abiMap = mapOf(
        "x86_64" to AbiUrls(
            talloc = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/x86_64/libtalloc.so.2",
            proot = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/x86_64/proot",
            alpine = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.0-x86_64.tar.gz",
        ),
        "arm64-v8a" to AbiUrls(
            talloc = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/libtalloc.so.2",
            proot = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/proot",
            alpine = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
        ),
        "armeabi-v7a" to AbiUrls(
            talloc = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/arm/libtalloc.so.2",
            proot = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/arm/proot",
            alpine = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/armhf/alpine-minirootfs-3.21.0-armhf.tar.gz",
        ),
    )

    /** One of the setup steps.
     *
     *  NOTE: The order is OPENCODE first, then ALPINE, then CPPLIBS — this is
     *  what the user explicitly requested: "na etapa 1 vai aparecer os releases
     *  do github no nosso próprio app". */
    enum class Step(val displayName: String) {
        IDLE("Aguardando início"),
        INSTALL_OPENCODE("Etapa 1/3 — Instalando opencode"),
        DOWNLOAD_ALPINE("Etapa 2/3 — Baixando Alpine + proot"),
        INSTALL_CPPLIBS("Etapa 3/3 — Instalando libstdc++ no chroot"),
        DONE("Setup concluído"),
        FAILED("Falha no setup"),
    }

    /** Current step. */
    val currentStep = mutableStateOf(Step.IDLE)

    /** Current sub-step progress (0..1f). */
    val progress = mutableStateOf(0f)

    /** Human-readable status message for the current step. */
    val statusMessage = mutableStateOf("")

    /** Mini-terminal log lines (capped at 1000). */
    val logLines = mutableStateListOf<String>()

    /** True when the whole setup is complete and the user can proceed. */
    val isComplete = mutableStateOf(false)

    /** Last error message, if any. */
    val lastError = mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun log(line: String) {
        val ts = System.currentTimeMillis()
        val stamped = "[$ts] $line"
        if (logLines.size >= 1000) {
            logLines.removeAt(0)
        }
        logLines.add(stamped)
        Log.d(TAG, line)
    }

    /** Clears the log buffer (called when the user re-runs the setup). */
    fun clearLog() {
        logLines.clear()
    }

    /** Returns true if step 1 (opencode binary installed) is already done. */
    fun isOpencodeInstalled(): Boolean {
        val f = OpencodeManager.binaryFile
        return f.exists() && f.canExecute() && f.length() >= MIN_BINARY_SIZE_BYTES
    }

    /** Returns true if step 2 (Alpine rootfs + proot + libtalloc) is already done. */
    fun isAlpineDownloaded(): Boolean {
        val app = application ?: return false
        val reTerminal = app.filesDir
        return reTerminal.child("proot").exists() &&
            reTerminal.child("libtalloc.so.2").exists() &&
            reTerminal.child("alpine.tar.gz").exists()
    }

    /** Returns true if step 3 (libstdc++ + libgcc installed inside chroot) is done. */
    fun areCppLibsInstalled(): Boolean = OpencodeManager.areCppLibsInstalled()

    /** Returns true if every step is already complete (no setup needed). */
    fun isSetupComplete(): Boolean {
        return isOpencodeInstalled() && isAlpineDownloaded() && areCppLibsInstalled()
    }

    // -------------------------------------------------------------------------
    // STEP 1 — Install opencode (download from GitHub release or import via SAF)
    // -------------------------------------------------------------------------

    /**
     * Fetches the list of recent releases from github.com/anomalyco/opencode/releases.
     * Returns only releases that ship an asset compatible with the device ABI.
     */
    suspend fun fetchReleases(): List<OpencodeRelease> = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url("https://api.github.com/repos/anomalyco/opencode/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("GitHub API retornou ${resp.code}")
            }
            val body = resp.body?.string()
                ?: throw IOException("Resposta vazia da GitHub API")
            val arr = org.json.JSONArray(body)
            val result = mutableListOf<OpencodeRelease>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val tagName = obj.optString("tag_name").ifBlank { continue }
                val publishedAt = obj.optString("published_at").ifBlank { "(unknown date)" }
                val assets = obj.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val assetName = asset.optString("name")
                    val expectedAsset = expectedAssetNameForAbi() ?: continue
                    if (assetName == expectedAsset) {
                        val url = asset.optString("browser_download_url")
                        val size = asset.optLong("size")
                        result.add(
                            OpencodeRelease(
                                tagName = tagName,
                                publishedAt = publishedAt,
                                assetName = assetName,
                                assetUrl = url,
                                assetSizeBytes = size,
                            )
                        )
                        break
                    }
                }
            }
            result
        }
    }

    /** Returns the expected opencode tarball asset name for the current device ABI. */
    fun expectedAssetNameForAbi(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        return when (abi) {
            "arm64-v8a" -> "opencode-linux-arm64-musl.tar.gz"
            "x86_64" -> "opencode-linux-x64-musl.tar.gz"
            else -> null
        }
    }

    /**
     * Step 1 (download variant): downloads the opencode tarball from
     * [release.assetUrl], extracts the `opencode` binary, installs it into the
     * Alpine chroot, and VERIFIES the result.
     *
     * Progress is streamed to [progress] and [logLines].
     */
    suspend fun runStep1DownloadOpencode(release: OpencodeRelease): Boolean = withContext(Dispatchers.IO) {
        currentStep.value = Step.INSTALL_OPENCODE
        progress.value = 0f
        statusMessage.value = "Baixando ${release.tagName}"
        log("=== Etapa 1: Baixando ${release.tagName} (${release.assetName}) ===")

        try {
            val app = application!!
            val tmpTar = File(app.cacheDir, "opencode-${release.tagName}.tar.gz")

            // Phase 1a: download (0% → 60%)
            log("  ↓ Baixando ${release.assetName} (${release.assetSizePretty})...")
            val downloadOk = downloadFileWithProgress(release.assetUrl, tmpTar) { downloaded, total ->
                val p = if (total > 0) downloaded.toFloat() / total else 0f
                progress.value = p * 0.6f
            }
            if (!downloadOk) {
                throw IOException("Falha no download")
            }
            log("  ✓ Download concluído (${tmpTar.length() / 1024 / 1024} MB)")
            progress.value = 0.6f

            // Phase 1b: extract (60% → 90%)
            log("  Extraindo binário opencode do tar.gz...")
            val extracted = extractOpencodeFromTarGz(tmpTar) { p ->
                progress.value = 0.6f + p * 0.3f
            }
            if (extracted == null) {
                throw IOException("Binário 'opencode' não encontrado dentro do tar.gz. " +
                    "Verifique se o arquivo é o opencode-linux-arm64-musl.tar.gz correto.")
            }
            log("  ✓ Extração concluída (${extracted.length() / 1024 / 1024} MB)")
            progress.value = 0.9f

            // Phase 1c: install + verify (90% → 100%)
            log("  Instalando binário em ${OpencodeManager.binaryFile.absolutePath}...")
            installExtractedBinary(extracted)
            runCatching { tmpTar.delete() }

            // CRITICAL: verify the binary actually exists, is executable, and is
            // big enough. The opencode binary is ~160 MB; anything smaller means
            // extraction was incomplete or the wrong file was picked.
            if (!verifyBinaryInstalled()) {
                throw IOException(
                    "Verificação pós-instalação falhou: o binário não existe, " +
                        "não é executável, ou é menor que ${MIN_BINARY_SIZE_BYTES / 1024 / 1024} MB " +
                        "(tamanho atual: ${OpencodeManager.binaryFile.length() / 1024 / 1024} MB). " +
                        "A extração pode ter sido interrompida."
                )
            }
            log("  ✓ Binário verificado: ${OpencodeManager.binaryFile.length() / 1024 / 1024} MB, executável")
            progress.value = 1f
            log("=== Etapa 1 concluída ===")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Step 1 (download) failed", e)
            log("!!! Etapa 1 falhou: ${e.message}")
            lastError.value = "Etapa 1: ${e.message}"
            currentStep.value = Step.FAILED
            false
        }
    }

    /**
     * Step 1 (import variant): installs opencode from a user-selected tar.gz Uri.
     *
     * This version properly streams progress to [progress] and [logLines] —
     * unlike the old version which jumped from 0% to 100% with no intermediate
     * feedback, making the user think extraction had finished while it was
     * still running.
     */
    suspend fun runStep1ImportOpencode(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        currentStep.value = Step.INSTALL_OPENCODE
        progress.value = 0f
        statusMessage.value = "Importando tar.gz..."
        log("=== Etapa 1: Importando tar.gz selecionado pelo usuário ===")

        try {
            val app = application!!

            // Phase 1a: copy SAF uri → temp file (0% → 40%)
            log("  Lendo arquivo selecionado...")
            val tmpTar = File(app.cacheDir, "opencode-import-${System.currentTimeMillis()}.tar.gz")
            copyUriToFileWithProgress(context, uri, tmpTar) { written, total ->
                val p = if (total > 0) written.toFloat() / total else 0f
                progress.value = p * 0.4f
            }
            log("  ✓ Arquivo copiado (${tmpTar.length() / 1024 / 1024} MB)")
            progress.value = 0.4f

            // Phase 1b: extract (40% → 80%)
            log("  Extraindo binário opencode do tar.gz...")
            val extracted = extractOpencodeFromTarGz(tmpTar) { p ->
                progress.value = 0.4f + p * 0.4f
            }
            if (extracted == null) {
                throw IOException("Binário 'opencode' não encontrado dentro do tar.gz. " +
                    "Verifique se o arquivo é o opencode-linux-arm64-musl.tar.gz correto.")
            }
            log("  ✓ Extração concluída (${extracted.length() / 1024 / 1024} MB)")
            progress.value = 0.8f

            // Phase 1c: install + verify (80% → 100%)
            log("  Instalando binário em ${OpencodeManager.binaryFile.absolutePath}...")
            installExtractedBinary(extracted)
            runCatching { tmpTar.delete() }

            if (!verifyBinaryInstalled()) {
                throw IOException(
                    "Verificação pós-instalação falhou: o binário não existe, " +
                        "não é executável, ou é menor que ${MIN_BINARY_SIZE_BYTES / 1024 / 1024} MB " +
                        "(tamanho atual: ${OpencodeManager.binaryFile.length() / 1024 / 1024} MB). " +
                        "A extração pode ter sido interrompida."
                )
            }
            log("  ✓ Binário verificado: ${OpencodeManager.binaryFile.length() / 1024 / 1024} MB, executável")
            progress.value = 1f
            log("=== Etapa 1 concluída ===")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Step 1 (import) failed", e)
            log("!!! Etapa 1 falhou: ${e.message}")
            lastError.value = "Etapa 1: ${e.message}"
            currentStep.value = Step.FAILED
            false
        }
    }

    // -------------------------------------------------------------------------
    // STEP 2 — Download Alpine rootfs + proot + libtalloc
    // -------------------------------------------------------------------------

    /**
     * Runs step 2: downloads proot, libtalloc.so.2, and alpine.tar.gz into the
     * app's filesDir, if not already present.
     */
    suspend fun runStep2DownloadAlpine(): Boolean = withContext(Dispatchers.IO) {
        currentStep.value = Step.DOWNLOAD_ALPINE
        progress.value = 0f
        statusMessage.value = "Baixando Alpine + proot"
        log("=== Etapa 2: Baixando Alpine rootfs + proot + libtalloc ===")

        try {
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it in abiMap }
                ?: throw RuntimeException("CPU não suportada: ${Build.SUPPORTED_ABIS.joinToString()}")
            log("  ABI detectada: $abi")

            val urls = abiMap[abi]!!
            val app = application!!
            val filesToDownload = listOf(
                "libtalloc.so.2" to urls.talloc,
                "proot" to urls.proot,
                "alpine.tar.gz" to urls.alpine,
            )

            val totalFiles = filesToDownload.size
            var completedFiles = 0

            for ((name, url) in filesToDownload) {
                val dest = app.filesDir.child(name)
                if (dest.exists() && dest.length() > 0) {
                    log("  ✓ $name já existe, pulando download")
                    completedFiles++
                    progress.value = completedFiles.toFloat() / totalFiles
                    continue
                }
                log("  ↓ Baixando $name...")
                val ok = downloadFileWithProgress(url, dest) { downloaded, total ->
                    val fileProgress = if (total > 0) downloaded.toFloat() / total else 0f
                    progress.value = (completedFiles + fileProgress) / totalFiles
                }
                if (!ok) {
                    throw IOException("Falha ao baixar $name")
                }
                if (name == "proot") {
                    dest.setExecutable(true, true)
                }
                completedFiles++
                progress.value = completedFiles.toFloat() / totalFiles
                log("  ✓ $name baixado (${dest.length() / 1024} KB)")
            }

            progress.value = 1f
            log("=== Etapa 2 concluída ===")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Step 2 failed", e)
            log("!!! Etapa 2 falhou: ${e.message}")
            lastError.value = "Etapa 2: ${e.message}"
            currentStep.value = Step.FAILED
            false
        }
    }

    // -------------------------------------------------------------------------
    // STEP 3 — Extract Alpine + install libstdc++/libgcc inside the chroot
    // -------------------------------------------------------------------------

    /**
     * Runs step 3: extracts the Alpine rootfs (if not already extracted) and
     * installs libstdc++ + libgcc via `apk add` inside the chroot.
     */
    suspend fun runStep3InstallCppLibs(): Boolean = withContext(Dispatchers.IO) {
        currentStep.value = Step.INSTALL_CPPLIBS
        progress.value = 0f
        statusMessage.value = "Instalando libstdc++ no chroot"
        log("=== Etapa 3: Instalando libstdc++ e libgcc dentro do Alpine ===")

        try {
            val app = application!!
            val prefix = app.filesDir.parentFile!!.absolutePath
            val alpineDir = alpineDir()

            // 1) Extract Alpine rootfs if not already done.
            if (!alpineDir.child("bin").exists()) {
                log("  Extraindo alpine.tar.gz...")
                val alpineTar = app.filesDir.child("alpine.tar.gz")
                if (!alpineTar.exists()) {
                    throw IOException("alpine.tar.gz não encontrado. Rode a etapa 2 primeiro.")
                }
                alpineDir.mkdirs()
                extractTarGz(alpineTar, alpineDir) { p ->
                    progress.value = p * 0.5f
                }
                log("  ✓ Alpine extraído em ${alpineDir.absolutePath}")
            } else {
                log("  Alpine já está extraído")
            }

            // 2) Skip if libs already installed.
            if (OpencodeManager.areCppLibsInstalled()) {
                log("  libstdc++ e libgcc já estão instalados")
                progress.value = 1f
                log("=== Etapa 3 concluída ===")
                return@withContext true
            }

            // 3) Set up resolv.conf for apk.
            log("  Configurando resolv.conf...")
            val resolvConf = alpineDir.child("etc").child("resolv.conf").apply { parentFile?.mkdirs() }
            if (!resolvConf.exists() || resolvConf.length() == 0L) {
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
            }

            // 4) Materialize proot + libtalloc into localBin/localLib.
            val prootBinary = localBinDir().child("proot")
            if (!prootBinary.exists()) {
                app.filesDir.child("proot").copyTo(prootBinary, overwrite = true)
                prootBinary.setExecutable(true, true)
            }
            val libtalloc = localLibDir().child("libtalloc.so.2")
            if (!libtalloc.exists()) {
                app.filesDir.child("libtalloc.so.2").copyTo(libtalloc, overwrite = true)
            }

            // 5) Run apk update + apk add.
            log("  Executando: apk update")
            val apkUpdateOk = runProotApk(prefix, listOf("update"))
            if (!apkUpdateOk) {
                log("  (apk update falhou — tentando mesmo assim)")
            }
            progress.value = 0.6f

            log("  Executando: apk add --no-cache libstdc++ libgcc")
            val apkAddOk = runProotApk(prefix, listOf("add", "--no-cache", "libstdc++", "libgcc"))
            if (!apkAddOk) {
                throw IOException("apk add libstdc++ libgcc falhou. Verifique sua conexão de internet.")
            }
            progress.value = 1f

            if (!OpencodeManager.areCppLibsInstalled()) {
                throw IOException("libstdc++.so.6 não encontrado após apk add")
            }
            log("  ✓ libstdc++ e libgcc instalados com sucesso")
            log("=== Etapa 3 concluída ===")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Step 3 failed", e)
            log("!!! Etapa 3 falhou: ${e.message}")
            lastError.value = "Etapa 3: ${e.message}"
            currentStep.value = Step.FAILED
            false
        }
    }

    /** Marks the setup as complete. */
    fun markComplete() {
        currentStep.value = Step.DONE
        isComplete.value = true
    }

    /** Resets the setup state so it can be re-run. */
    fun reset() {
        currentStep.value = Step.IDLE
        progress.value = 0f
        statusMessage.value = ""
        isComplete.value = false
        lastError.value = null
    }

    // -------------------------------------------------------------------------
    // Internals: download / extract / install helpers
    // -------------------------------------------------------------------------

    /** Downloads [url] to [dest], reporting progress to [onProgress]. */
    private suspend fun downloadFileWithProgress(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val total = body.contentLength()
                var downloaded = 0L
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out ->
                    body.byteStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "download failed: $url", e)
            false
        }
    }

    /** Copies a SAF Uri to [dest], reporting progress to [onProgress]. */
    private fun copyUriToFileWithProgress(
        context: Context,
        uri: Uri,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Não foi possível abrir o arquivo selecionado.")

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

    /** Extracts a .tar.gz into [destDir], reporting progress via [onProgress]. */
    private fun extractTarGz(tarGz: File, destDir: File, onProgress: (Float) -> Unit) {
        val total = tarGz.length().coerceAtLeast(1)
        var consumed = 0L
        tarGz.inputStream().buffered().use { fis ->
            GzipCompressorInputStream(fis).use { gz ->
                TarArchiveInputStream(gz).use { tis ->
                    var entry: TarArchiveEntry? = tis.nextEntry as? TarArchiveEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val outFile = File(destDir, entry.name)
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = tis.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    consumed += n
                                    onProgress(consumed.toFloat() / total)
                                }
                            }
                            if ((entry.mode and 0b001_000_000) != 0) {
                                outFile.setExecutable(true, true)
                            }
                        }
                        entry = tis.nextEntry as? TarArchiveEntry
                    }
                }
            }
        }
    }

    /**
     * Streams through the .tar.gz looking for the `opencode` entry.
     * Returns the extracted temp file, or null if not found.
     *
     * IMPORTANT: this function blocks until the entry is FULLY extracted.
     * The caller MUST NOT assume the extraction is complete until this
     * function returns a non-null File.
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
                        if (!found && (name == "opencode" || File(name).name == "opencode") && !entry.isDirectory) {
                            // Found the opencode entry — copy it to `out`.
                            // This loop blocks until the ENTIRE entry is read.
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
                            // Flush is implicit via BufferedOutputStream.close().
                            found = true
                            break
                        }
                        // Skip this entry.
                        val skipped = tis.available()
                        if (skipped > 0) {
                            val buf = ByteArray(64 * 1024)
                            while (tis.read(buf) > 0) { /* drain */ }
                        }
                        consumed = tarGz.length()
                        onProgress(consumed.toFloat() / totalSize)
                        entry = tis.nextEntry as? TarArchiveEntry
                    }
                }
            }
        }
        return if (found) out else null
    }

    /**
     * Moves the extracted binary to its final location and chmod +x.
     * Blocks until the file is fully in place.
     */
    private fun installExtractedBinary(extracted: File) {
        OpencodeManager.binaryFile.parentFile?.mkdirs()
        if (OpencodeManager.binaryFile.exists()) {
            OpencodeManager.binaryFile.delete()
        }
        // renameTo can fail across filesystems; fall back to copy.
        // copyTo blocks until the file is fully written.
        if (!extracted.renameTo(OpencodeManager.binaryFile)) {
            extracted.copyTo(OpencodeManager.binaryFile, overwrite = true)
            extracted.delete()
        }
        if (!OpencodeManager.binaryFile.setExecutable(true, true)) {
            throw IOException("Não foi possível tornar o binário executável.")
        }
    }

    /**
     * CRITICAL verification: confirms the binary actually exists, is executable,
     * AND is big enough to be a real opencode binary (≥ 50 MB).
     *
     * This catches the bug where the UI reported "extraction complete" while
     * extraction was still running, or where the user picked a wrong/small file.
     */
    private fun verifyBinaryInstalled(): Boolean {
        val f = OpencodeManager.binaryFile
        if (!f.exists()) {
            Log.e(TAG, "verifyBinaryInstalled: file does not exist at ${f.absolutePath}")
            return false
        }
        if (!f.canExecute()) {
            Log.e(TAG, "verifyBinaryInstalled: file not executable")
            return false
        }
        val size = f.length()
        if (size < MIN_BINARY_SIZE_BYTES) {
            Log.e(TAG, "verifyBinaryInstalled: file too small ($size bytes, expected ≥ $MIN_BINARY_SIZE_BYTES)")
            return false
        }
        Log.i(TAG, "verifyBinaryInstalled: OK ($size bytes)")
        return true
    }

    /**
     * Runs `apk <args>` inside the Alpine chroot via proot, streaming apk's
     * stdout/stderr to our log buffer.
     */
    private fun runProotApk(prefix: String, apkArgs: List<String>): Boolean {
        val app = application!!
        val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
        val proot = localBinDir().child("proot")
        val alpineDir = alpineDir()

        val args = mutableListOf(
            "--kill-on-exit",
            "-w", "/",
            "-r", alpineDir.absolutePath,
            "-0",
            "--link2symlink",
            "-L",
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc",
            "-b", "/sys",
            "-b", prefix,
        )
        listOf("/apex", "/odm", "/product", "/system", "/system_ext", "/vendor").forEach { mnt ->
            if (File(mnt).exists()) args.addAll(listOf("-b", mnt))
        }
        if (File("/proc/self/fd").exists()) args.addAll(listOf("-b", "/proc/self/fd:/dev/fd"))

        val cmd = mutableListOf(linker, proot.absolutePath)
        cmd.addAll(args)
        cmd.add("/sbin/apk")
        cmd.addAll(apkArgs)

        val env = mutableMapOf(
            "PATH" to "/sbin:/bin:/usr/sbin:/usr/bin:/system/bin:/system/xbin",
            "HOME" to "/root",
            "LD_LIBRARY_PATH" to localLibDir().absolutePath,
            "TMPDIR" to app.cacheDir.absolutePath,
            "PROOT_TMP_DIR" to File(prefix, "tmp").absolutePath.also { File(it).mkdirs() },
        )
        val nativeLibDir = app.applicationInfo.nativeLibraryDir
        if (File(nativeLibDir).child("libproot-loader.so").exists()) {
            env["PROOT_LOADER"] = "$nativeLibDir/libproot-loader.so"
        }
        if (File(nativeLibDir).child("libproot-loader32.so").exists()) {
            env["PROOT_LOADER32"] = "$nativeLibDir/libproot-loader32.so"
        }

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        pb.directory(app.filesDir)

        val p = pb.start()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        var line = reader.readLine()
        while (line != null) {
            log("    apk: $line")
            line = reader.readLine()
        }
        val code = p.waitFor()
        return code == 0
    }
}

/** A GitHub release entry with the device-compatible opencode tarball asset. */
data class OpencodeRelease(
    val tagName: String,
    val publishedAt: String,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long,
) {
    val assetSizePretty: String
        get() {
            val mb = assetSizeBytes / 1024.0 / 1024.0
            return if (mb >= 1) "%.1f MB".format(mb) else "${assetSizeBytes / 1024} KB"
        }
}
