package com.rk.terminal.ui.screens.opencode

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.rk.opencode.OpencodeRelease
import com.rk.opencode.SetupManager
import com.rk.opencode.SetupManager.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run Setup screen for the OpenCode integration.
 *
 * **Step ordering (as requested by the user):**
 *
 *   Step 1 — Install opencode:
 *            • Show GitHub releases (only the compatible asset for the device ABI)
 *            • User taps one to download, OR imports a tar.gz manually
 *            • Progress streams to the mini-terminal
 *
 *   Step 2 — Download Alpine rootfs + proot + libtalloc (~20 MB).
 *            Automatic after step 1.
 *
 *   Step 3 — Extract Alpine and install libstdc++ + libgcc inside the chroot
 *            via `apk add`. Automatic after step 2.
 *
 *   Step 4 — Done. User proceeds to the OpenCode server screen.
 *
 * The user NEVER has to open the ReTerminal terminal screen — everything is
 * driven from this dedicated setup screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    navController: NavController? = null,
    onComplete: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe SetupManager state.
    val currentStep = SetupManager.currentStep
    val progress = SetupManager.progress
    val statusMessage = SetupManager.statusMessage
    val logLines = SetupManager.logLines
    val lastError = SetupManager.lastError

    // Local UI state.
    var releases by remember { mutableStateOf<List<OpencodeRelease>>(emptyList()) }
    var releasesLoading by remember { mutableStateOf(false) }
    var releasesError by remember { mutableStateOf<String?>(null) }
    var selectedRelease by remember { mutableStateOf<OpencodeRelease?>(null) }

    // Track which steps are done so the UI can pre-check them.
    var step1Done by remember { mutableStateOf(SetupManager.isOpencodeInstalled()) }
    var step2Done by remember { mutableStateOf(SetupManager.isAlpineDownloaded()) }
    var step3Done by remember { mutableStateOf(SetupManager.areCppLibsInstalled()) }

    // Auto-scroll the mini-terminal.
    val logListState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            logListState.animateScrollToItem(logLines.size - 1)
        }
    }

    // Pre-fetch GitHub releases on first show — this is step 1, so it should
    // be visible immediately when the user enters the setup screen.
    LaunchedEffect(Unit) {
        if (releases.isEmpty() && !releasesLoading) {
            releasesLoading = true
            releasesError = null
            try {
                releases = withContext(Dispatchers.IO) { SetupManager.fetchReleases() }
                if (releases.isEmpty()) {
                    releasesError = "Nenhum release compatível encontrado. Verifique sua conexão ou importe manualmente."
                }
            } catch (e: Exception) {
                releasesError = e.message ?: "Erro ao buscar releases"
            } finally {
                releasesLoading = false
            }
        }
    }

    // SAF launcher for manual import (step 1 alternative).
    val pickTarGz = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = SetupManager.runStep1ImportOpencode(context, uri)
                if (ok) {
                    step1Done = true
                    // Auto-advance to steps 2 + 3.
                    autoAdvanceToSteps23(scope, snackbarHostState, lastError) {
                        step2Done = SetupManager.isAlpineDownloaded()
                        step3Done = SetupManager.areCppLibsInstalled()
                        if (SetupManager.isSetupComplete()) {
                            SetupManager.markComplete()
                            onComplete()
                        }
                    }
                } else {
                    snackbarHostState.showSnackbar("Falha: ${lastError.value ?: ""}")
                }
            }
        }
    }

    // Helper: run steps 2 + 3 in sequence (called automatically after step 1).
    fun runSteps2And3() {
        scope.launch {
            if (!step2Done) {
                val ok = SetupManager.runStep2DownloadAlpine()
                if (!ok) {
                    snackbarHostState.showSnackbar("Etapa 2 falhou: ${lastError.value ?: ""}")
                    return@launch
                }
                step2Done = true
            }
            if (!step3Done) {
                val ok = SetupManager.runStep3InstallCppLibs()
                if (!ok) {
                    snackbarHostState.showSnackbar("Etapa 3 falhou: ${lastError.value ?: ""}")
                    return@launch
                }
                step3Done = true
            }
            if (SetupManager.isSetupComplete()) {
                SetupManager.markComplete()
                onComplete()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Setup OpenCode", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Step cards (1, 2, 3) ----
            StepCard(
                number = 1,
                title = "Instalar opencode",
                subtitle = "Escolha um release do GitHub OU importe um tar.gz manualmente",
                isDone = step1Done,
                isActive = currentStep.value == Step.INSTALL_OPENCODE,
            )
            StepCard(
                number = 2,
                title = "Baixar Alpine rootfs + proot",
                subtitle = "Baixa ~20 MB de arquivos necessários para o chroot Alpine",
                isDone = step2Done,
                isActive = currentStep.value == Step.DOWNLOAD_ALPINE,
            )
            StepCard(
                number = 3,
                title = "Instalar libstdc++ no Alpine",
                subtitle = "Executa apk add libstdc++ libgcc dentro do chroot (requer internet)",
                isDone = step3Done,
                isActive = currentStep.value == Step.INSTALL_CPPLIBS,
            )

            // ---- Active step progress bar ----
            AnimatedVisibility(
                visible = currentStep.value != Step.IDLE &&
                    currentStep.value != Step.DONE &&
                    currentStep.value != Step.FAILED
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${currentStep.value.displayName}… ${(progress.value * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (statusMessage.value.isNotBlank()) {
                            Text(
                                statusMessage.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress.value.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ---- Step 1 content: GitHub releases OR import ----
            // This is ALWAYS shown if step 1 isn't done — the user must pick
            // a release or import before they can proceed.
            if (!step1Done) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Releases do GitHub (compatíveis com seu dispositivo)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))

                        if (releasesLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Carregando releases...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (releasesError != null) {
                            Text(
                                releasesError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        // Show the 10 most recent compatible releases.
                        releases.take(10).forEach { rel ->
                            ReleaseRow(
                                release = rel,
                                isDownloading = currentStep.value == Step.INSTALL_OPENCODE,
                                onDownload = {
                                    selectedRelease = rel
                                    scope.launch {
                                        val ok = SetupManager.runStep1DownloadOpencode(rel)
                                        if (ok) {
                                            step1Done = true
                                            // Auto-advance to steps 2 + 3.
                                            runSteps2And3()
                                        } else {
                                            snackbarHostState.showSnackbar("Falha: ${lastError.value ?: ""}")
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        if (releases.isNotEmpty()) {
                            Text(
                                "Mostrando os ${releases.size.coerceAtMost(10)} releases mais recentes. " +
                                    "Para versões antigas, use a importação manual abaixo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                pickTarGz.launch(
                                    arrayOf(
                                        "application/gzip",
                                        "application/x-gzip",
                                        "application/x-tar",
                                        "application/octet-stream",
                                    )
                                )
                            },
                            enabled = currentStep.value != Step.INSTALL_OPENCODE,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Ou importar um .tar.gz manualmente")
                        }
                    }
                }
            }

            // ---- Manual trigger for steps 2+3 (if step 1 done but 2/3 not) ----
            if (step1Done && (!step2Done || !step3Done)) {
                Button(
                    onClick = { runSteps2And3() },
                    enabled = currentStep.value == Step.IDLE ||
                        currentStep.value == Step.DONE ||
                        currentStep.value == Step.FAILED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (step2Done && !step3Done) "Executar etapa 3 (libstdc++)"
                        else if (!step2Done && step3Done) "Executar etapa 2 (Alpine)"
                        else "Baixar e configurar Alpine + libstdc++"
                    )
                }
            }

            // ---- Error card ----
            AnimatedVisibility(visible = lastError.value != null && currentStep.value == Step.FAILED) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Falha no setup",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            lastError.value ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { SetupManager.reset() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Tentar novamente")
                        }
                    }
                }
            }

            // ---- Mini-terminal log panel ----
            if (logLines.isNotEmpty()) {
                Text(
                    "Mini-terminal",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1E1E)
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                    ) {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                color = Color(0xFFD4D4D4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---- Finish button ----
            AnimatedVisibility(visible = step1Done && step2Done && step3Done) {
                Button(
                    onClick = {
                        SetupManager.markComplete()
                        onComplete()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ir para o servidor OpenCode")
                }
            }
        }
    }
}

/**
 * Helper: auto-advance to steps 2 and 3 after step 1 completes.
 * Runs in the given [scope], shows errors via [snackbarHostState].
 */
private fun autoAdvanceToSteps23(
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    lastError: androidx.compose.runtime.MutableState<String?>,
    onAllDone: () -> Unit,
) {
    scope.launch {
        if (!SetupManager.isAlpineDownloaded()) {
            val ok = SetupManager.runStep2DownloadAlpine()
            if (!ok) {
                snackbarHostState.showSnackbar("Etapa 2 falhou: ${lastError.value ?: ""}")
                return@launch
            }
        }
        if (!SetupManager.areCppLibsInstalled()) {
            val ok = SetupManager.runStep3InstallCppLibs()
            if (!ok) {
                snackbarHostState.showSnackbar("Etapa 3 falhou: ${lastError.value ?: ""}")
                return@launch
            }
        }
        if (SetupManager.isSetupComplete()) {
            SetupManager.markComplete()
            onAllDone()
        }
    }
}

/** A single step card with number, title, subtitle, and a status indicator. */
@Composable
private fun StepCard(
    number: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isActive: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDone -> MaterialTheme.colorScheme.secondaryContainer
                isActive -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = when {
                            isDone -> MaterialTheme.colorScheme.secondary
                            isActive -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isDone -> Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    isActive -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    else -> Text(
                        number.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A single release row inside the GitHub releases panel. */
@Composable
private fun ReleaseRow(
    release: OpencodeRelease,
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                release.tagName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${release.assetSizePretty} · ${release.publishedAt.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onDownload,
            enabled = !isDownloading,
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(if (isDownloading) "..." else "Baixar")
        }
    }
}
