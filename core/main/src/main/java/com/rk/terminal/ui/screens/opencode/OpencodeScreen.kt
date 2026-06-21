package com.rk.terminal.ui.screens.opencode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import com.rk.libcommons.toast
import com.rk.opencode.OpencodeManager
import com.rk.opencode.OpencodeServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated OpenCode server screen.
 *
 * UX flow:
 *   1. If opencode isn't installed yet → big "Importar tar.gz" button.
 *   2. While installing → progress bar + status text.
 *   3. When installed → "Iniciar servidor" / "Parar servidor" / "Abrir no navegador" / "Remover".
 *   4. While running → live log panel + URL + Stop button.
 *
 * The terminal screen is intentionally NOT shown here — the user wanted everything to be
 * automatic, driven by a dedicated UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpencodeScreen(
    modifier: Modifier = Modifier,
    navController: NavController? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-observe install state from the singleton so the UI updates live.
    val installProgress = OpencodeManager.installProgress
    val installStatus = OpencodeManager.installStatus
    val installError = OpencodeManager.installError
    val isInstalling = OpencodeManager.isInstalling

    var installedVersion by remember { mutableStateOf<String?>(null) }
    var isInstalled by remember { mutableStateOf(OpencodeManager.isInstalled()) }
    var isAlpineReady by remember { mutableStateOf(OpencodeManager.isAlpineReady()) }

    // ---- Storage permission gate ----
    // On Android 11+ (API 30+) we need MANAGE_EXTERNAL_STORAGE (All files access),
    // which can only be granted via the system Settings page.
    // On older Android we request READ/WRITE_EXTERNAL_STORAGE via the dialog.
    // Without storage permission, importing the tar.gz from /sdcard/Download fails
    // silently on some devices (SAF picker doesn't show the file, or ContentResolver
    // returns null when opening the URI).
    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }

    // Re-check permission whenever the screen is resumed (e.g. user returned from
    // the system Settings page where they granted MANAGE_EXTERNAL_STORAGE).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = checkStoragePermission(context)
                isAlpineReady = OpencodeManager.isAlpineReady()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Runtime permission launcher for READ/WRITE_EXTERNAL_STORAGE (Android < 11).
    val requestStoragePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasStoragePermission = results.values.all { it }
    }

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — MANAGE_EXTERNAL_STORAGE can't be requested via dialog,
            // must send the user to the system Settings page.
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }.onFailure {
                // Fallback: open the generic "all files" settings page.
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        } else {
            // Android 10 and below — request via dialog.
            requestStoragePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }
    }

    // Recheck Alpine readiness whenever the screen is recomposed (e.g. user returned
    // from the terminal screen where they triggered the download).
    LaunchedEffect(isInstalled) {
        isAlpineReady = OpencodeManager.isAlpineReady()
    }

    val serverState = OpencodeServer.state
    val serverUrl = OpencodeServer.url
    val serverLogs = OpencodeServer.logs
    val serverError = OpencodeServer.lastError

    // SAF launcher — lets the user pick the tar.gz from any app (Files, Downloads, etc.).
    val pickTarGz = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = OpencodeManager.installFromUri(context, uri)
                if (ok) {
                    isInstalled = true
                    installedVersion = withContext(Dispatchers.IO) { OpencodeManager.version() }
                    snackbarHostState.showSnackbar("opencode instalado com sucesso!")
                } else {
                    isInstalled = OpencodeManager.isInstalled()
                    snackbarHostState.showSnackbar(
                        "Falha na instalação: ${installError.value ?: "erro desconhecido"}"
                    )
                }
            }
        }
    }

    // Refresh installed-version label whenever install state changes.
    LaunchedEffect(isInstalled) {
        if (isInstalled) {
            installedVersion = withContext(Dispatchers.IO) { OpencodeManager.version() }
        } else {
            installedVersion = null
        }
    }

    // Auto-scroll the log to the bottom whenever new lines arrive.
    val logListState = rememberLazyListState()
    LaunchedEffect(serverLogs.value.size) {
        if (serverLogs.value.isNotEmpty()) {
            logListState.animateScrollToItem(serverLogs.value.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Servidor OpenCode", fontWeight = FontWeight.SemiBold) },
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

            // ---- Status card ----
            StatusCard(
                isInstalled = isInstalled,
                installedVersion = installedVersion,
                serverState = serverState.value,
                serverUrl = serverUrl.value,
                serverError = serverError.value,
            )

            // ---- Install progress (if installing) ----
            AnimatedVisibility(visible = isInstalling.value, enter = fadeIn(), exit = fadeOut()) {
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
                        Text(installStatus.value, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { installProgress.value.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ---- Install error (if any) ----
            AnimatedVisibility(
                visible = installError.value != null && !isInstalling.value,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Text(
                        text = installError.value ?: "",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---- Action buttons ----
            if (!isInstalled) {
                // ---- Storage permission gate ----
                // Force the user to grant storage permission before allowing import.
                // Without it, on some devices the SAF file picker can't see files
                // in /sdcard/Download, or the ContentResolver returns null.
                if (!hasStoragePermission) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Permissão de armazenamento necessária",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    "Para importar o tar.gz do opencode, conceda acesso a todos os " +
                                        "arquivos. Você será redirecionado para as Configurações do " +
                                        "Android — ative a permissão e volte para este app."
                                } else {
                                    "Para importar o tar.gz do opencode, conceda permissão de " +
                                        "leitura/escrita no armazenamento."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Button(
                                onClick = { requestStoragePermission() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Icon(Icons.Filled.Security, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Conceder permissão de armazenamento")
                            }
                        }
                    }
                }

                Button(
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
                    enabled = !isInstalling.value && hasStoragePermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Importar opencode-linux-arm64-musl.tar.gz")
                }
                Text(
                    text = "Baixe o arquivo em github.com/anomalyco/opencode/releases " +
                        "(procure por opencode-linux-arm64-musl.tar.gz, ~53 MB) e selecione-o aqui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Warn the user if the Alpine rootfs isn't ready yet — and offer to
                // run the dedicated setup wizard (which downloads Alpine + installs
                // libstdc++ + installs opencode in a single guided flow).
                if (!isAlpineReady) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Setup inicial necessário",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "O opencode precisa do Alpine rootfs + proot + libstdc++ antes de " +
                                    "iniciar. Toque abaixo para executar o setup guiado com " +
                                    "mini-terminal mostrando o progresso.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    navController?.navigate(
                                        com.rk.terminal.ui.routes.MainActivityRoutes.OpencodeSetup.route
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.onTertiary,
                                ),
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Executar setup inicial")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (serverState.value) {
                        OpencodeServer.State.STOPPED, OpencodeServer.State.FAILED -> {
                            Button(
                                onClick = { OpencodeServer.start(context) },
                                enabled = isAlpineReady,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Iniciar servidor")
                            }
                        }
                        OpencodeServer.State.STARTING -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.weight(1f),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Iniciando…")
                            }
                        }
                        OpencodeServer.State.RUNNING -> {
                            Button(
                                onClick = { OpencodeServer.stop(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Parar servidor")
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            OpencodeManager.uninstall()
                            isInstalled = false
                            installedVersion = null
                            OpencodeServer.stop(context)
                        },
                        enabled = serverState.value == OpencodeServer.State.STOPPED ||
                            serverState.value == OpencodeServer.State.FAILED,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Remover")
                    }
                }

                if (serverState.value == OpencodeServer.State.RUNNING) {
                    Button(
                        onClick = {
                            val u = serverUrl.value
                                ?: "http://${OpencodeManager.DEFAULT_HOSTNAME}:${OpencodeManager.DEFAULT_PORT}"
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }.onFailure {
                                toast("Nenhum navegador disponível: ${it.message}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Abrir no navegador · " +
                                (serverUrl.value
                                    ?: "http://${OpencodeManager.DEFAULT_HOSTNAME}:${OpencodeManager.DEFAULT_PORT}")
                        )
                    }
                }
            }

            // ---- Logs panel ----
            if (isInstalled && serverLogs.value.isNotEmpty()) {
                Text(
                    "Logs",
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
                        items(serverLogs.value) { line ->
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

            // ---- Footer help ----
            if (isInstalled && serverState.value == OpencodeServer.State.STOPPED) {
                Text(
                    "Toque em \"Iniciar servidor\" para subir opencode em " +
                        "http://${OpencodeManager.DEFAULT_HOSTNAME}:${OpencodeManager.DEFAULT_PORT}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    isInstalled: Boolean,
    installedVersion: String?,
    serverState: OpencodeServer.State,
    serverUrl: String?,
    serverError: String?,
) {
    val accent = when (serverState) {
        OpencodeServer.State.RUNNING -> Color(0xFF2E7D32)
        OpencodeServer.State.STARTING -> MaterialTheme.colorScheme.tertiary
        OpencodeServer.State.FAILED -> MaterialTheme.colorScheme.error
        OpencodeServer.State.STOPPED -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (serverState) {
                OpencodeServer.State.RUNNING -> Icon(
                    Icons.Filled.CloudDone,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp),
                )
                OpencodeServer.State.STARTING -> CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                )
                else -> Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (serverState) {
                        OpencodeServer.State.RUNNING -> "Servidor rodando"
                        OpencodeServer.State.STARTING -> "Iniciando servidor…"
                        OpencodeServer.State.STOPPED -> if (isInstalled) "Pronto para iniciar" else "opencode não instalado"
                        OpencodeServer.State.FAILED -> "Falha ao iniciar"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                val subtitle = when {
                    !isInstalled -> "Importe o tar.gz do opencode para começar"
                    serverState == OpencodeServer.State.RUNNING -> serverUrl ?: "—"
                    installedVersion != null -> "Versão instalada: $installedVersion"
                    else -> "Binário instalado em ${OpencodeManager.binaryFile.absolutePath}"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (serverError != null && serverState == OpencodeServer.State.FAILED) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = serverError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Checks whether the app has storage permission.
 *
 * - Android 11+ (API 30+): MANAGE_EXTERNAL_STORAGE (All files access).
 * - Android 10 and below: READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE.
 *
 * Without this permission, on some devices the SAF file picker can't see files
 * in /sdcard/Download, or ContentResolver.openInputStream() returns null. The
 * user explicitly requested that the import button be disabled until storage
 * permission is granted.
 */
private fun checkStoragePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
