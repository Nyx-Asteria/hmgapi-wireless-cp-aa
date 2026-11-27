"""Exercise native capture and readiness with legacy/modern logcat."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
for modern in (False, True):
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        src = d / "journal"
        native = d / "native"
        usb = d / "storage/usb0"
        binpath = d / "bin"
        for f in (src, native, usb, binpath):
            f.mkdir(parents=True)
        cmp = binpath / "cmp"
        cmp.write_text('#!/bin/sh\ncase "$1" in -*) echo "cmp: invalid option" >&2; exit 2;; esac\nexec /usr/bin/cmp "$@"\n')
        cmp.chmod(0o755)
        mounts = d / "mounts"
        mounts.write_text("")
        marker = src / "session-001.native-enabled"
        active = src / "session-001.active"
        marker.write_text("enabled")
        active.write_text("START\n")
        (src / "session-002.log").write_text("complete\n")
        (src / "session-003.active").write_text("no native opt-in\n")
        dest = usb / "WCP-LOGS/sessions"
        logcat = binpath / "logcat"
        helptext = "-T COUNT" if modern else "-t COUNT"
        logcat.write_text("#!/bin/sh\n"
            + 'if [ "$1" = -h ]; then echo "' + helptext + '"; exit 0; fi\n'
            + 'echo "$*" > "' + str(d / "args") + '"\n'
            + 'while [ "$#" -gt 0 ]; do if [ "$1" = -f ]; then shift; printf "native\\n" > "$1"; break; fi; shift; done\n'
            + 'echo started >> "' + str(d / "starts") + '"\n'
            + 'if [ ! -e "' + str(d / "failed-once") + '" ]; then touch "' + str(d / "failed-once") + '"; exit 1; fi\n'
            + 'while true; do /bin/sleep 1; done\n')
        logcat.chmod(0o755)
        sleep = binpath / "sleep"
        sleep.write_text("#!/bin/sh\n"
            + 'n=$(cat "' + str(d / "ticks") + '" 2>/dev/null || echo 0)\n'
            + 'n=$((n+1)); echo "$n" > "' + str(d / "ticks") + '"\n'
            + '/bin/sleep 0.1\n'
            + 'case "$n" in\n'
            + '1) [ ! -e "' + str(dest) + '" ] || exit 99; echo "dev ' + str(usb) + ' vfat rw 0 0" > "' + str(mounts) + '";;\n'
            + '4) [ ! -e "' + str(native / "session-001.native-ready") + '" ] || touch "' + str(d / "premature") + '"; mv "' + str(active) + '" "' + str(src / "session-001.log") + '"; rm "' + str(marker) + '";;\n'
            + '6) kill -TERM "$PPID";;\nesac\n')
        sleep.chmod(0o755)
        script = (root / "export-sessions.sh").read_text()
        script = script.replace("/data/data/com.daudio.app.dipo/files/wcp-carplay-sessions", str(src))
        script = script.replace("/data/hmgapi/logs/wcp-native-session", str(native))
        script = script.replace("/storage/usb", str(d / "storage/usb"))
        script = script.replace("/proc/mounts", str(mounts))
        subprocess.run(["sh", "-c", script], env={**os.environ, "PATH":str(binpath)+":"+os.environ["PATH"]},
                       check=True, timeout=15)
        assert (d / "starts").read_text().splitlines() == ["started", "started"], "dead collector must restart exactly once" 
        assert ("-T 200" in (d / "args").read_text()) == modern
        assert not (d / "premature").exists()
        assert (native / "session-001.native.log").read_text() == "native\n"
        assert (native / "session-001.native-ready").read_text().strip() == "ready"
        assert not dest.exists(), "native collector must not write USB"
        assert not (dest / "session-002.log").exists(), "Java journals belong to the Java exporter"
        assert not (dest / "session-003.active").exists()
        assert not list(native.glob("session-003*"))
        assert not list(dest.glob("*.part"))
        assert (src / "session-001.log").exists()
        print("Native capture passed: modern=" + str(modern))
