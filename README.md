# ReTerminal + OpenCode

This is a fork of [ReTerminal](https://github.com/RohitKushvaha01/ReTerminal) that adds a
**dedicated OpenCode server integration**. Users can:

1. **Import** the official `opencode-linux-arm64-musl.tar.gz` release from
   [github.com/anomalyco/opencode/releases](https://github.com/anomalyco/opencode/releases)
   directly from inside the app (via the system file picker — SAF).
2. The app **automatically extracts** the `opencode` binary from the tarball, installs it
   into the app's private data directory, and marks it executable. No terminal needed.
3. From a **dedicated UI** the user can start/stop the OpenCode HTTP server with a single
   tap — the app runs `opencode serve --hostname 127.0.0.1 --port 4096` for them.
4. While running, the user can tap **"Abrir no navegador"** to launch the system browser at
   `http://127.0.0.1:4096`.
5. A foreground service keeps the server alive even when the app is backgrounded.

The terminal screen is still there (it's still ReTerminal), but for the OpenCode server
flow the user never needs to touch a terminal — everything is driven by the dedicated UI.

## How it works (architecture)

| Component | File | Responsibility |
|-----------|------|----------------|
| `OpencodeManager` | `core/main/src/main/java/com/rk/opencode/OpencodeManager.kt` | Imports the tar.gz via SAF, extracts & installs the binary, verifies with `--version`. |
| `OpencodeServer` | `core/main/src/main/java/com/rk/opencode/OpencodeServer.kt` | Launches/stops the `opencode serve` process, captures logs, exposes state to Compose. |
| `OpencodeService` | `core/main/src/main/java/com/rk/terminal/service/OpencodeService.kt` | Foreground service that owns the process lifecycle so it survives backgrounding. |
| `OpencodeScreen` | `core/main/src/main/java/com/rk/terminal/ui/screens/opencode/OpencodeScreen.kt` | Material 3 UI with status card, install / start / stop / open / remove buttons, and live logs. |

### Why this works on Android without root / proot

The `opencode-linux-arm64-musl.tar.gz` release is a **single static ELF binary** built
against musl libc. Android's Bionic libc coexists with musl-linked static binaries — they
just need a directory where the kernel allows `exec()`. The app's private data directory
(`/data/data/com.rk.terminal/files/local/bin/`) is exactly such a location, and is what
ReTerminal already uses for its own `proot` binary. We re-use that infrastructure.

The `chmod +x` step is implemented with `File.setExecutable(true, true)`.

### Flow when the user taps "Iniciar servidor"

1. UI calls `OpencodeServer.start(context)`.
2. `OpencodeServer` sends an `ACTION_START` Intent to `OpencodeService`.
3. `OpencodeService` promotes itself to the foreground (so the OS won't kill it).
4. `OpencodeService` calls `OpencodeServer.launchProcess(hostname, port)`.
5. `OpencodeServer` runs `ProcessBuilder("/data/data/.../opencode", "serve", "--hostname", "127.0.0.1", "--port", "4096")` with `HOME=/data/data/.../opencode-home` and stream-reads stdout/stderr into a Compose-observable log buffer.
6. A health-check coroutine polls `127.0.0.1:4096` every 500ms; once reachable, the state
   transitions from `STARTING` to `RUNNING` and the "Abrir no navegador" button becomes
   available.

## Building

This repository ships a GitHub Actions workflow at
[`.github/workflows/build.yml`](.github/workflows/build.yml) that builds both debug and
release APKs for the `Fdroid` and `PlayStore` flavors.

To build locally:

```bash
./gradlew :app:assembleFdroidDebug
```

The APK will be at `app/build/outputs/apk/Fdroid/debug/app-Fdroid-debug.apk`.

## Original ReTerminal features

Everything from the upstream ReTerminal is preserved:

- Basic Terminal
- Virtual Keys
- Multiple Sessions
- Alpine Linux support
- Configurable Keyboard Shortcuts (Paste, Session Management)

See the [original README](https://github.com/RohitKushvaha01/ReTerminal) for details.
