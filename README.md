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

### Why this works on Android

The `opencode-linux-arm64-musl.tar.gz` release is a **single ELF binary** built against
musl libc — but it is NOT fully static. It needs the musl dynamic linker
`/lib/ld-musl-aarch64.so.1`, which Android's Bionic libc does not ship. Trying to
`exec()` the binary directly on Android fails with `error=2, No such file or directory`.

The fix is to run opencode INSIDE ReTerminal's existing Alpine chroot, which already
ships musl libc and the dynamic linker. We re-use the same proot-based chroot
infrastructure that powers ReTerminal's terminal sessions:

1. The opencode binary is installed at `alpineDir()/usr/local/bin/opencode`, so inside
   the chroot it's reachable as `/usr/local/bin/opencode`.
2. To start the server, `OpencodeService` invokes `init-host-opencode.sh` (a stripped-down
   variant of ReTerminal's `init-host.sh`) which assembles the same proot bind-mount list
   and then `exec`s into `/usr/local/bin/opencode serve --hostname 127.0.0.1 --port 4096`.
3. The opencode process runs inside the chroot with full access to musl libc, the dynamic
   linker, and `/proc` — but it binds `127.0.0.1:4096` on the host's network namespace, so
   the browser can reach it directly at `http://127.0.0.1:4096`.

This means **the user must open the ReTerminal terminal at least once** so the Alpine
rootfs (~20 MB) and proot get downloaded. The OpenCode screen will detect this and show
a warning + disable the "Iniciar servidor" button until the Alpine rootfs is ready.

### Flow when the user taps "Iniciar servidor"

1. UI calls `OpencodeServer.start(context)`.
2. `OpencodeServer` checks that the opencode binary is installed AND that the Alpine
   rootfs + proot are downloaded. If not, it surfaces an error in the UI.
3. `OpencodeServer` sends an `ACTION_START` Intent to `OpencodeService`.
4. `OpencodeService` promotes itself to the foreground (so the OS won't kill it).
5. `OpencodeService` calls `OpencodeServer.launchProcess(hostname, port)`.
6. `OpencodeServer` calls `OpencodeManager.launchOpencodeViaProot(["serve", "--hostname", "127.0.0.1", "--port", "4096"])`.
7. `OpencodeManager` materializes the bundled `init-host-opencode.sh` script and runs:
   ```
   /system/bin/sh init-host-opencode.sh serve --hostname 127.0.0.1 --port 4096
   ```
   with `PREFIX=/data/data/com.rk.terminal/files`, `LINKER=/system/bin/linker64`,
   `PROOT_LOADER=<nativeLibDir>/libproot-loader.so`, etc.
8. The script extracts the Alpine rootfs (if not already done), then `exec`s into:
   ```
   linker64 proot --kill-on-exit -0 --link2symlink --sysvipc -L \
     -b /apex -b /system -b /sdcard -b /proc -b $PREFIX ... \
     -r $PREFIX/local/alpine -w /root \
     /usr/local/bin/opencode serve --hostname 127.0.0.1 --port 4096
   ```
9. opencode boots inside the chroot, opens a listening socket on `127.0.0.1:4096`,
   and prints `server listening on http://127.0.0.1:4096`.
10. A health-check coroutine in `OpencodeServer` polls `127.0.0.1:4096` every 1s;
    once reachable, the state transitions from `STARTING` to `RUNNING` and the
    "Abrir no navegador" button becomes available.

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
