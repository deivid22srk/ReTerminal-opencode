#!/system/bin/sh
# init-host-opencode.sh
#
# Launches opencode inside ReTerminal's Alpine chroot via proot — without ever
# opening a terminal session.
#
# Invoked as: sh init-host-opencode.sh <opencode-args...>
#
# Inside the chroot, $PREFIX/local/alpine is mounted as the rootfs, so the opencode
# binary installed at $PREFIX/local/alpine/usr/local/bin/opencode is reachable as
# /usr/local/bin/opencode inside the chroot.
#
# Environment variables expected (set by OpencodeServer.kt):
#   PREFIX         - the app's /data/data/<pkg>/files parent directory
#   LINKER         - /system/bin/linker64 or /system/bin/linker (used by proot)
#   PROOT_LOADER   - path to libproot-loader.so (set by the app)
#   PROOT_LOADER32 - path to libproot-loader32.so (optional)
#   HOME           - the Android-side home dir (used as the chroot's HOME bind source)
#   PROOT_TMP_DIR  - a writable dir for proot's glue rootfs (set by the app)

set -e

ALPINE_DIR=$PREFIX/local/alpine

# 1) Make sure the Alpine rootfs is extracted (mirrors init-host.sh).
mkdir -p "$ALPINE_DIR"
if [ -z "$(ls -A "$ALPINE_DIR" | grep -vE '^(root|tmp)$')" ]; then
    tar -xf "$PREFIX/files/alpine.tar.gz" -C "$ALPINE_DIR"
fi

# 2) Make sure proot + libtalloc are in place (mirrors init-host.sh).
[ ! -e "$PREFIX/local/bin/proot" ] && cp "$PREFIX/files/proot" "$PREFIX/local/bin"
for sofile in "$PREFIX/files/"*.so.2; do
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    [ ! -e "$dest" ] && cp "$sofile" "$dest"
done

# 3) Install libstdc++ and libgcc INSIDE the Alpine chroot.
#    The opencode binary is a Bun-compiled C++ executable that links against
#    libstdc++.so.6, libc.musl-aarch64.so.1, and libgcc_s.so.1. The Alpine
#    mini-rootfs only ships musl libc — we need to install the C++ runtime
#    libraries via apk before opencode can run.
#
#    We do this lazily: only if /usr/lib/libstdc++.so.6 doesn't exist.
#    The first run will require network access (apk needs to fetch packages
#    from dl-cdn.alpinelinux.org); subsequent runs are instant.
if [ ! -e "$ALPINE_DIR/usr/lib/libstdc++.so.6" ] || [ ! -e "$ALPINE_DIR/usr/lib/libgcc_s.so.1" ]; then
    echo "[init-host-opencode] Installing libstdc++ and libgcc inside Alpine chroot..."
    # Set up resolv.conf so apk can resolve the Alpine CDN host.
    if [ ! -s "$ALPINE_DIR/etc/resolv.conf" ]; then
        echo "nameserver 8.8.8.8" > "$ALPINE_DIR/etc/resolv.conf"
        echo "nameserver 8.8.4.4" >> "$ALPINE_DIR/etc/resolv.conf"
    fi

    # Build a minimal proot invocation just for apk — we don't want opencode's
    # fancy bind mounts yet, just enough to run apk add.
    APK_ARGS="--kill-on-exit"
    APK_ARGS="$APK_ARGS -w /"
    for system_mnt in /apex /odm /product /system /system_ext /vendor \
     /linkerconfig/ld.config.txt \
     /linkerconfig/com.android.art/ld.config.txt; do
     if [ -e "$system_mnt" ]; then
      system_mnt=$(realpath "$system_mnt")
      APK_ARGS="$APK_ARGS -b ${system_mnt}"
     fi
    done
    unset system_mnt
    APK_ARGS="$APK_ARGS -b /dev"
    APK_ARGS="$APK_ARGS -b /dev/urandom:/dev/random"
    APK_ARGS="$APK_ARGS -b /proc"
    APK_ARGS="$APK_ARGS -b $PREFIX"
    APK_ARGS="$APK_ARGS -b /sys"
    if [ -e "/proc/self/fd" ]; then
      APK_ARGS="$APK_ARGS -b /proc/self/fd:/dev/fd"
    fi
    APK_ARGS="$APK_ARGS -r $ALPINE_DIR"
    APK_ARGS="$APK_ARGS -0"
    APK_ARGS="$APK_ARGS --link2symlink"
    APK_ARGS="$APK_ARGS -L"

    $LINKER $PREFIX/local/bin/proot $APK_ARGS \
        /sbin/apk update || true
    $LINKER $PREFIX/local/bin/proot $APK_ARGS \
        /sbin/apk add --no-cache libstdc++ libgcc || {
        echo "[init-host-opencode] WARNING: failed to install libstdc++/libgcc"
        echo "[init-host-opencode] opencode may fail to start with 'symbol not found' errors"
        echo "[init-host-opencode] Make sure your device has internet access and try again"
    }
    echo "[init-host-opencode] libstdc++/libgcc install step done"
fi

# 4) Make sure PROOT_TMP_DIR is set and writable. proot uses this to create its
#    glue rootfs; if it's not set, proot falls back to /tmp, which on Android
#    may not be writable from an unprivileged app context.
if [ -z "$PROOT_TMP_DIR" ]; then
    PROOT_TMP_DIR="$PREFIX/tmp"
fi
mkdir -p "$PROOT_TMP_DIR" 2>/dev/null || true
export PROOT_TMP_DIR

# 5) Build proot args (same bind mounts as init-host.sh).
ARGS="--kill-on-exit"
ARGS="$ARGS -w /root"

# System mounts — let the chroot see the Android system trees.
for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do
 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

# Storage binds so opencode can read/write the user's project files.
ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"

# /proc/self/fd/{0,1,2} — proot warning "can't sanitize binding" appears if
# these don't exist (which is the case when stdin/stdout/stderr aren't connected
# to a TTY, e.g. when launched from a foreground service via ProcessBuilder).
# These binds are optional, so skip them silently if they don't exist.
if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi
if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi
if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

ARGS="$ARGS -b /sys"

# /dev/shm — needed by some node-based binaries (opencode is a bun-compiled binary).
if [ ! -d "$PREFIX/local/alpine/tmp" ]; then
 mkdir -p "$PREFIX/local/alpine/tmp"
 chmod 1777 "$PREFIX/local/alpine/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/alpine/tmp:/dev/shm"

# Set the chroot rootfs and proot options.
ARGS="$ARGS -r $PREFIX/local/alpine"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# 6) Launch proot with opencode as the entrypoint.
# $@ is the opencode subcommand + args, e.g. "serve --hostname 127.0.0.1 --port 4096".
# We exec into it so the process becomes a direct child of proot (no extra shell layer).
echo "[init-host-opencode] Launching: proot ... /usr/local/bin/opencode $@"
exec $LINKER $PREFIX/local/bin/proot $ARGS /usr/local/bin/opencode "$@"
