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
        proc = d / "proc"
        proc.mkdir()
        foreign = proc / "123"
        foreign.mkdir()
        (foreign / "exe").symlink_to("/system/bin/logcat")
        (foreign / "fd").mkdir()
        (foreign / "fd" / "3").symlink_to(native / "session-999.native.log")
        (native / "session-999.native.log").write_text("orphan\n")
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
            + 'if [ "$1" = 1 ]; then /bin/sleep 0.05; exit 0; fi\n'
            + 'n=$(cat "' + str(d / "ticks") + '" 2>/dev/null || echo 0)\n'
            + 'n=$((n+1)); echo "$n" > "' + str(d / "ticks") + '"\n'
            + '/bin/sleep 0.1\n'
            + 'case "$n" in\n'
            + '1) [ ! -e "' + str(d / "starts") + '" ] && [ ! -e "' + str(native / "session-999.native-ready") + '" ] || touch "' + str(d / "orphan-violation") + '"; rm "' + str(foreign / "fd" / "3") + '";;\n'
            + '2) [ ! -e "' + str(dest) + '" ] || exit 99; echo "dev ' + str(usb) + ' vfat rw 0 0" > "' + str(mounts) + '";;\n'
            + '4) ln -s "' + str(native / "session-999.native.log") + '" "' + str(foreign / "fd" / "3") + '";;\n'
            + '5) [ "$(cat "' + str(d / "starts") + '" | wc -l)" -eq 2 ] || touch "' + str(d / "late-violation") + '"; rm "' + str(foreign / "fd" / "3") + '";;\n'
            + '7) [ ! -e "' + str(native / "session-001.native-ready") + '" ] || touch "' + str(d / "premature") + '"; mv "' + str(active) + '" "' + str(src / "session-001.log") + '"; rm "' + str(marker) + '";;\n'
            + '9) kill -TERM "$PPID";;\nesac\n')
        sleep.chmod(0o755)
        script = (root / "export-sessions.sh").read_text()
        script = script.replace("/data/data/com.daudio.app.dipo/files/wcp-carplay-sessions", str(src))
        script = script.replace("/data/hmgapi/logs/wcp-native-session", str(native))
        script = script.replace("/storage/usb", str(d / "storage/usb"))
        script = script.replace("/proc/mounts", str(mounts))
        script = script.replace("proc_root=/proc", "proc_root=" + str(proc))
        subprocess.run(["sh", "-c", script], env={**os.environ, "PATH":str(binpath)+":"+os.environ["PATH"]},
                       check=True, timeout=15)
        assert (d / "starts").read_text().splitlines() == ["started", "started", "started"], "restart after failure and late foreign writer" 
        assert ("-T 200" in (d / "args").read_text()) == modern
        assert not (d / "premature").exists()
        assert not (d / "late-violation").exists(), "no replacement while late writer remains"
        assert not (d / "orphan-violation").exists(), "must not start or publish while foreign writer exists"
        assert (native / "session-999.native-ready").is_file(), "readiness resumes after foreign writer disappears"
        assert (native / "session-001.native.log").read_text() == "native\n"
        assert (native / "session-001.native-ready").read_text().strip() == "ready"
        assert not dest.exists(), "native collector must not write USB"
        assert not (dest / "session-002.log").exists(), "Java journals belong to the Java exporter"
        assert not (dest / "session-003.active").exists()
        assert not list(native.glob("session-003*"))
        assert not list(dest.glob("*.part"))
        assert (src / "session-001.log").exists()
        print("Native capture passed: modern=" + str(modern))

# Exercise the actual retention function without starting a collector.
with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    native = d / "native"
    src = d / "journal"
    native.mkdir()
    src.mkdir()
    script = (root / "export-sessions.sh").read_text()
    body = script[script.index("prune_native() {"):script.index("# Prefer a short historical tail")]
    for i in range(12):
        base = f"session-{i:03}"
        (native / (base + ".native.log")).write_text("preserve")
        (native / (base + ".native-ready")).write_text("ready")
    def prune():
        return subprocess.run(["sh","-c",body + "\nprune_native 11"],env={**os.environ,"native_dir":str(native),"source_dir":str(src),"native_session":""}).returncode
    assert prune() != 0
    assert len(list(native.glob("*.native.log"))) == 12
    (native / "session-000.native-exported").write_text("verif")
    assert prune() != 0
    (native / "session-001.native-exported").write_text("verified\n")
    (src / "session-001.active").write_text("active")
    assert prune() != 0
    (src / "session-001.active").unlink()
    assert prune() == 0
    assert not (native / "session-001.native.log").exists()
    assert (native / "session-000.native.log").exists()
    print("Native retention preserves unexported groups")
