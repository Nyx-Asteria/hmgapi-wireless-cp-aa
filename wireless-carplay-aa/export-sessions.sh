#!/system/bin/sh
# Copy only finalized journals; leave originals available until bounded local retention expires.
umask 077
source_dir=/data/data/com.daudio.app.dipo/files/wcp-carplay-sessions
# Native logger is independent of HMG-API Report a problem / Java heap.
native_dir=/data/hmgapi/logs/wcp-native-session
mkdir -p "$native_dir" || exit 1
native_pid=
native_session=
proc_root=/proc
# An abandoned logcat may still own the output after an abnormal supervisor exit.
# Do not signal an unowned PID (PID reuse is possible). Refuse to start a second
# collector or publish readiness until the matching writer has disappeared.
foreign_writer() {
    for entry in "$proc_root"/[0-9]*; do
        [ -d "$entry" ] || continue
        pid=${entry##*/}
        [ "$pid" != "$native_pid" ] || continue
        executable=$(readlink "$entry/exe" 2>/dev/null) || continue
        case "$executable" in
            */logcat) ;;
            *) continue ;;
        esac
        for fd in "$entry"/fd/*; do
            output=$(readlink "$fd" 2>/dev/null) || continue
            case "$output" in
                "$native_dir"/session-*.native.log|"$native_dir"/session-*.native.log.[123]) return 0 ;;
            esac
        done
    done
    return 1
}
foreign_reported=0
retry_ticks=0
retry_delay=1
healthy_ticks=0
stop_native() {
    if [ -n "$native_pid" ]; then
        kill "$native_pid" 2>/dev/null
        remaining=3
        while kill -0 "$native_pid" 2>/dev/null && [ "$remaining" -gt 0 ]; do
            sleep 1
            remaining=$((remaining - 1))
        done
        if kill -0 "$native_pid" 2>/dev/null; then
            kill -KILL "$native_pid" 2>/dev/null
        fi
        wait "$native_pid" 2>/dev/null
    fi
    native_pid=
}
trap 'stop_native; exit 0' HUP INT TERM
# Reserve room for the next native group, including its three rotations.
# Only fully verified groups can be reclaimed. Never delete active or unexported evidence.
native_capacity_reported=0
prune_native() {
    limit=$1
    count=0
    seen=
    for file in "$native_dir"/session-*.native.log "$native_dir"/session-*.native.log.[123]; do
        [ -f "$file" ] || continue
        name=${file##*/}; base=${name%%.native.log*}
        case " $seen " in *" $base "*) continue;; esac
        seen="$seen $base"
        count=$((count + 1))
    done
    seen=
    for file in "$native_dir"/session-*.native-exported; do
        [ "$count" -gt "$limit" ] || break
        [ -f "$file" ] && [ ! -L "$file" ] || continue
        [ "$(cat "$file" 2>/dev/null)" = verified ] || continue
        name=${file##*/}; base=${name%.native-exported}
        [ "$base" != "$native_session" ] || continue
        [ ! -f "$source_dir/$base.active" ] || continue
        [ -f "$native_dir/$base.native-ready" ] || continue
        if rm -f "$native_dir/$base.native.log" "$native_dir/$base.native.log.1" "$native_dir/$base.native.log.2" "$native_dir/$base.native.log.3" &&
                rm -f "$native_dir/$base.native-ready" "$file"; then
            count=$((count - 1))
        else
            echo "Native retention cleanup failed: $base" >&2
        fi
    done
    [ "$count" -le "$limit" ]
}
# Prefer a short historical tail when supported; older Android logcat lacks -T.
# The fallback reads the existing bounded ring before following live output.
start_native() {
    # Recheck immediately before starting, including retry and session switches.
    if foreign_writer; then return 1; fi
    # Retry an existing active group without requiring an extra slot.
    if [ ! -f "$native_dir/$1.native.log" ]; then
        if ! prune_native 11; then
            if [ "$native_capacity_reported" -eq 0 ]; then
                echo "Native collector storage full; unexported recordings retained" >&2
                native_capacity_reported=1
            fi
            return 1
        fi
    fi
    native_capacity_reported=0
    native_session=$1
    if logcat -h 2>&1 | grep -E '(^|[[:space:]])-T([[:space:],]|$)' >/dev/null; then
        set -- -T 200
    else
        set --
    fi
    logcat -v threadtime "$@" -f "$native_dir/$native_session.native.log" -r 1024 -n 3 -s TccCarPlay:I '*:S' &
    native_pid=$!
}
# Never export native payloads without explicit opt-in through the existing logger setting.
# The DiPO journal creates a per-session marker only when carplay_log is enabled.

while true; do
    # A foreign writer may open its file after our startup scan. Check even
    # when we own a collector; stop only our own child if ownership conflicts.
    if foreign_writer; then
        stop_native
        native_session=
        retry_ticks=0
        retry_delay=1
        healthy_ticks=0
        if [ "$foreign_reported" -eq 0 ]; then
            echo "Native collector suspended: unowned logcat writer detected" >&2
            foreign_reported=1
        fi
        sleep 2
        continue
    fi
    foreign_reported=0
    current=
    for marker in "$source_dir"/session-*.native-enabled; do
        [ -f "$marker" ] && [ ! -L "$marker" ] || continue
        [ "$(cat "$marker" 2>/dev/null)" = enabled ] || continue
        base=${marker##*/}; base=${base%.native-enabled}
        [ -f "$source_dir/$base.active" ] && current=$base
    done
    if [ "$current" != "$native_session" ]; then
        stop_native
        native_session=
        retry_ticks=0
        retry_delay=1
        healthy_ticks=0
        [ -z "$current" ] || start_native "$current"
        if ! foreign_writer; then prune_native 12 || :; fi
    fi
    # Keep polling the session marker during backoff; never sleep through teardown.
    if [ -n "$current" ] && [ "$current" = "$native_session" ]; then
        if [ -n "$native_pid" ]; then
            if kill -0 "$native_pid" 2>/dev/null; then
                healthy_ticks=$((healthy_ticks + 1))
                if [ "$healthy_ticks" -ge 30 ]; then
                    retry_delay=1
                    healthy_ticks=30
                fi
            else
                wait "$native_pid" 2>/dev/null
                native_pid=
                healthy_ticks=0
                retry_ticks=$retry_delay
                retry_delay=$((retry_delay * 2))
                [ "$retry_delay" -le 30 ] || retry_delay=30
                echo "Native collector exited; retry in $((retry_ticks * 2)) seconds" >&2
            fi
        elif [ "$retry_ticks" -gt 0 ]; then
            retry_ticks=$((retry_ticks - 1))
            [ "$retry_ticks" -ne 0 ] || start_native "$current"
        else
            start_native "$current"
        fi
    fi
    # Publish readiness only after logcat has stopped writing the session.
    if foreign_writer; then
        stop_native
        native_session=
        sleep 2
        continue
    fi
    for file in "$native_dir"/session-*.native.log; do
        [ -f "$file" ] && [ ! -L "$file" ] || continue
        name=${file##*/}; base=${name%.native.log}
        [ "$base" != "$native_session" ] || continue
        [ ! -f "$source_dir/$base.active" ] || continue
        [ ! -L "$native_dir/$base.native-ready" ] || continue
        [ -f "$native_dir/$base.native-ready" ] || echo ready > "$native_dir/$base.native-ready"
    done
    sleep 2
done
