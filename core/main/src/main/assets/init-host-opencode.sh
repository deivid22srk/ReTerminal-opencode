#!/system/bin/sh
# init-host-opencode.sh
#
# This is a stripped-down variant of ReTerminal's init-host.sh that's purpose-built
# for launching the opencode server inside the Alpine chroot via proot — without
# ever opening a terminal session.
#
# Invoked as: sh init-host-opencode.sh <opencode-args...>
#
# Inside the chroot, $PREFIX/local/alpine is mounted as the rootfs, so the opencode
# binary installed at $PREFIX/local/alpine/usr/local/bin/opencode is reachable as
# /usr/local/bin/opencode inside the chroot. Alpine's musl libc and the dynamic
# linker /lib/ld-musl-aarch64.so.1 are present there, so the musl-linked opencode
# binary can finally run.
#
# Environment variables expected (set by OpencodeServer.kt):
#   PREFIX         - the app's /data/data/<pkg>/files parent directory
#   LINKER         - /system/bin/linker64 or /system/bin/linker (used by proot)
#   PROOT_LOADER   - path to libproot-loader.so (set by the app)
#   PROOT_LOADER32 - path to libproot-loader32.so (optional)
#   HOME           - the Android-side home dir (used as the chroot's HOME bind source)

set -e

ALPINE_DIR=$PREFIX/local/alpine

# Make sure the Alpine rootfs is extracted (mirrors what init-host.sh does).
mkdir -p "$ALPINE_DIR"
if [ -z "$(ls -A "$ALPINE_DIR" | grep -vE '^(root|tmp)$')" ]; then
    tar -xf "$PREFIX/files/alpine.tar.gz" -C "$ALPINE_DIR"
fi

# Make sure proot + libtalloc are in place.
[ ! -e "$PREFIX/local/bin/proot" ] && cp "$PREFIX/files/proot" "$PREFIX/local/bin"
for sofile in "$PREFIX/files/"*.so.2; do
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    [ ! -e "$dest" ] && cp "$sofile" "$dest"
done

# ---- Build proot args (same bind mounts as init-host.sh) ----
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

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi
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

# ---- Launch proot with opencode as the entrypoint ----
# $@ is the opencode subcommand + args, e.g. "serve --hostname 127.0.0.1 --port 4096".
# We exec into it so the process becomes a direct child of proot (no extra shell layer).
exec $LINKER $PREFIX/local/bin/proot $ARGS /usr/local/bin/opencode "$@"
