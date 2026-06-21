package com.rk.terminal.ui.screens.opencode

import android.content.Intent
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
import androidx.compose.material.icons.filled.Close
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
 * Replaces the old flow where the user had to open ReTerminal's terminal screen
 * first to trigger the Alpine download + apk setup. Now everything is driven
 * from a single dedicated screen with a mini-terminal showing real-time progress.
 *
 * Steps:
 *   1) Download Alpine rootfs + proot + libtalloc (~20 MB).
 *   2) Extract Alpine and install libstdc++ + libgcc inside the chroot via apk.
 *   3) Install opencode: either download from GitHub releases or import manually.
 *   4) Done — user proceeds to the OpenCode server screen.
 *
 * Each step shows:
 *   - A status line (current step + progress %)
 *   - A linear progress bar
 *   - A "mini-terminal" panel with streaming log output
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
    val isComplete = SetupManager.isComplete

    // Local UI state.
    var releases by remember { mutableStateOf<List<OpencodeRelease>>(emptyList()) }
    var releasesLoading by remember { mutableStateOf(false) }
    var releasesError by remember { mutableStateOf<String?>(null) }
    var selectedRelease by remember { mutableStateOf<OpencodeRelease?>(null) }
    var showReleases by remember { mutableStateOf(false) }

    // Compute which steps are already done (so the UI can pre-check them).
    var step1Done by remember { mutableStateOf(SetupManager.isAlpineDownloaded()) }
    var step2Done by remember { mutableStateOf(SetupManager.areCppLibsInstalled()) }
    var step3Done by remember { mutableStateOf(SetupManager.isOpencodeInstalled()) }

    // Auto-scroll the mini-terminal to the bottom.
    val logListState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            logListState.animateScrollToItem(logLines.size - 1)
        }
    }

    // When the user opens the "Download from GitHub" panel, fetch releases.
    LaunchedEffect(showReleases) {
        if (showReleases && releases.isEmpty() && !releasesLoading) {
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

    // SAF launcher for manual import.
    val pickTarGz = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = SetupManager.runStep3ImportOpencode(context, uri)
                if (ok) {
                    step3Done = true
                    SetupManager.markComplete()
                    snackbarHostState.showSnackbar("opencode instalado!")
                    onComplete()
                } else {
                    snackbarHostState.showSnackbar("Falha: ${lastError.value ?: "erro"}")
                }
            }
        }
    }

    // Helper: run steps 1+2 in sequence (so the user only taps one button for both).
    fun runSteps1And2() {
        scope.launch {
            step1Done = SetupManager.isAlpineDownloaded()
            step2Done = SetupManager.areCppLibsInstalled()
            if (!step1Done) {
                val ok = SetupManager.runStep1DownloadAlpine()
                if (!ok) {
                    snackbarHostState.showSnackbar("Etapa 1 falhou: ${lastError.value ?: ""}")
                    return@launch
                }
                step1Done = true
            }
            if (!step2Done) {
                val ok = SetupManager.runStep2InstallCppLibs()
                if (!ok) {
                    snackbarHostState.showSnackbar("Etapa 2 falhou: ${lastError.value ?: ""}")
                    return@launch
                }
                step2Done = true
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
                title = "Baixar Alpine rootfs + proot",
                subtitle = "Baixa ~20 MB de arquivos necessários para o chroot Alpine",
                isDone = step1Done,
                isActive = currentStep.value == Step.DOWNLOAD_ALPINE,
            )
            StepCard(
                number = 2,
                title = "Instalar libstdc++ no Alpine",
                subtitle = "Executa apk add libstdc++ libgcc dentro do chroot (requer internet)",
                isDone = step2Done,
                isActive = currentStep.value == Step.INSTALL_CPPLIBS,
            )
            StepCard(
                number = 3,
                title = "Instalar opencode",
                subtitle = "Baixe da lista de releases do GitHub OU importe um tar.gz manualmente",
                isDone = step3Done,
                isActive = currentStep.value == Step.INSTALL_OPENCODE,
            )

            // ---- Active step progress bar ----
            AnimatedVisibility(visible = currentStep.value != Step.IDLE && currentStep.value != Step.DONE && currentStep.value != Step.FAILED) {
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
                        LinearProgressIndicator(
                            progress = { progress.value.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ---- Action buttons ----
            if (!step1Done || !step2Done) {
                Button(
                    onClick = { runSteps1And2() },
                    enabled = currentStep.value == Step.IDLE ||
                        currentStep.value == Step.DONE ||
                        currentStep.value == Step.FAILED,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (step1Done && step2Done) "Reexecutar etapas 1-2" else "Baixar e configurar Alpine")
                }
            }

            // ---- Step 3 actions: download from GitHub OR import ----
            if (step1Done && step2Done && !step3Done) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { showReleases = !showReleases },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Baixar do GitHub")
                    }
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
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Importar .tar.gz")
                    }
                }

                AnimatedVisibility(visible = showReleases) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Releases compatíveis",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(8.dp))

                            if (releasesLoading) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Carregando...", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (releasesError != null) {
                                Text(
                                    releasesError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            releases.take(10).forEach { rel ->
                                ReleaseRow(
                                    release = rel,
                                    isDownloading = currentStep.value == Step.INSTALL_OPENCODE,
                                    onDownload = {
                                        selectedRelease = rel
                                        scope.launch {
                                            val ok = SetupManager.runStep3DownloadOpencode(rel)
                                            if (ok) {
                                                step3Done = true
                                                SetupManager.markComplete()
                                                snackbarHostState.showSnackbar("opencode ${rel.tagName} instalado!")
                                                onComplete()
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
                                        "Para versões antigas, use \"Importar .tar.gz\".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
            // Number circle / status icon
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

/** A single release row inside the "Download from GitHub" panel. */
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
