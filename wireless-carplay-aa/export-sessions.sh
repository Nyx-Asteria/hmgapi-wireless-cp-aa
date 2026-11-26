#!/system/bin/sh
# Copy only finalized journals; leave originals available until bounded local retention expires.
umask 077
source_dir=/data/data/com.daudio.app.dipo/files/wcp-carplay-sessions
usb_name=WCP-LOGS
# Native logger is independent of HMG-API Report a problem / Java heap.
native_dir=/data/hmgapi/logs/wcp-native-session
mkdir -p "$native_dir" || exit 1
native_pid=
native_session=
stop_native() {
    [ -z "$native_pid" ] || { kill "$native_pid" 2>/dev/null; wait "$native_pid" 2>/dev/null; }
    native_pid=
}
trap 'stop_native; exit 0' HUP INT TERM
# Start at the live tail; bounded rotation stays on disk, never in a Java String.
start_native() {
    native_session=$1
    logcat -v threadtime -T 1 -f "$native_dir/$native_session.native.log" -r 1024 -n 3 -s TccCarPlay:I '*:S' &
    native_pid=$!
}
# Never export native payloads without explicit opt-in through the existing logger setting.
# The DiPO journal creates a per-session marker only when carplay_log is enabled.

while true; do
    current=
    for marker in "$source_dir"/session-*.native-enabled; do
        [ -f "$marker" ] || continue
        base=${marker##*/}; base=${base%.native-enabled}
        [ -f "$source_dir/$base.active" ] && current=$base
    done
    if [ "$current" != "$native_session" ]; then
        stop_native
        native_session=
        [ -z "$current" ] || start_native "$current"
    fi
    for root in /storage/usb0 /storage/usb1 /storage/usb2; do
        [ -d "$root" ] || continue
        # Require a mounted volume, not an empty internal mountpoint.
        canonical=$(cd "$root" 2>/dev/null && pwd -P)
        [ -n "$canonical" ] || continue
        grep -F " $root " /proc/mounts >/dev/null 2>&1 ||
            grep -F " $canonical " /proc/mounts >/dev/null 2>&1 || continue
        dest="$root/$usb_name/sessions"
        [ ! -L "$root/$usb_name" ] && [ ! -L "$dest" ] || continue
        mkdir -p "$dest" || continue
        for src in "$source_dir"/session-*.log; do
            [ -f "$src" ] && [ ! -L "$src" ] || continue
            name=${src##*/}
            [ ! -L "$dest/$name" ] && [ ! -L "$dest/$name.part" ] || continue
            [ -f "$dest/$name" ] && cmp -s "$src" "$dest/$name" && continue
            if cat "$src" > "$dest/$name.part" && cmp -s "$src" "$dest/$name.part"; then
                mv "$dest/$name.part" "$dest/$name" || rm -f "$dest/$name.part"
            else
                rm -f "$dest/$name.part"
            fi
        done
        for src in "$native_dir"/session-*.native.log*; do
            [ -f "$src" ] && [ ! -L "$src" ] || continue
            name=${src##*/}; base=${name%%.native.log*}
            [ "$base" != "$native_session" ] || continue
            [ ! -L "$dest/$name" ] && [ ! -L "$dest/$name.part" ] || continue
            [ -f "$dest/$name" ] && continue
            # Scrub common addresses; native logs may still contain personal device details.
            if sed 's/[[:xdigit:]][[:xdigit:]]:[[:xdigit:]][[:xdigit:]]:[[:xdigit:]][[:xdigit:]]:[[:xdigit:]][[:xdigit:]]:[[:xdigit:]][[:xdigit:]]:[[:xdigit:]][[:xdigit:]]/[MAC]/g' "$src" > "$dest/$name.part"; then
                mv "$dest/$name.part" "$dest/$name" || rm -f "$dest/$name.part"
            else
                rm -f "$dest/$name.part"
            fi
        done
    done
    sleep 2
done
