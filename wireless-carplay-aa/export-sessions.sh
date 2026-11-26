#!/system/bin/sh
# Copy only finalized journals; leave originals available until bounded local retention expires.
umask 077
source_dir=/data/data/com.daudio.app.dipo/files/wcp-carplay-sessions
usb_name=WCP-LOGS
while true; do
    for root in /storage/usb0 /storage/usb1 /storage/usb2; do
        [ -d "$root" ] || continue
        # Require a mounted volume, not an empty internal mountpoint.
        canonical=$(cd "$root" 2>/dev/null && pwd -P)
        [ -n "$canonical" ] || continue
        grep -F " $root " /proc/mounts >/dev/null 2>&1 ||
            grep -F " $canonical " /proc/mounts >/dev/null 2>&1 || continue
        dest="$root/$usb_name/sessions"
        mkdir -p "$dest" || continue
        for src in "$source_dir"/session-*.log; do
            [ -f "$src" ] || continue
            name=${src##*/}
            [ -f "$dest/$name" ] && cmp -s "$src" "$dest/$name" && continue
            if cat "$src" > "$dest/$name.part" && cmp -s "$src" "$dest/$name.part"; then
                mv "$dest/$name.part" "$dest/$name" || rm -f "$dest/$name.part"
            else
                rm -f "$dest/$name.part"
            fi
        done
    done
    sleep 5
done
