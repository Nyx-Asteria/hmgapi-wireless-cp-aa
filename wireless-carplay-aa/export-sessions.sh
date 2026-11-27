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
# Keep at most twelve native session groups, including rotation segments.
prune_native() {
    count=0
    for file in "$native_dir"/session-*.native.log; do
        [ -f "$file" ] || continue
        count=$((count + 1))
    done
    for file in "$native_dir"/session-*.native.log; do
        [ "$count" -gt 12 ] || break
        [ -f "$file" ] || continue
        name=${file##*/}; base=${name%.native.log}
        [ "$base" != "$native_session" ] || continue
        if rm -f "$native_dir/$base.native-ready" "$file.1" "$file.2" "$file.3" "$file" &&
                [ ! -e "$file" ] && [ ! -e "$file.1" ] && [ ! -e "$file.2" ] && [ ! -e "$file.3" ]; then
            count=$((count - 1))
        else
            echo "Native retention cleanup failed: $base" >&2
        fi
    done
}
# Prefer a short historical tail when supported; older Android logcat lacks -T.
# The fallback reads the existing bounded ring before following live output.
start_native() {
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
    # Scan only while we do not own a running collector, including startup and
    # finalization. Never certify a native file with an unowned writer present.
    if [ -z "$native_pid" ] && foreign_writer; then
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
        prune_native
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
    if [ -z "$native_pid" ] && foreign_writer; then
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
