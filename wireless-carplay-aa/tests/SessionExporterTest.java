package hmgapi.mods.sessionexport;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

public final class SessionExporterTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static void put(File f, String text) throws IOException {
        Files.write(f.toPath(), text.getBytes("UTF-8"));
    }
    static void remove(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) remove(c);
        f.delete();
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("java-session-export").toFile();
        try {
            File spool = new File(root, "spool"), usb = new File(root, "usb");
            check(spool.mkdir() && usb.mkdir(), "directories");
            File mounts = new File(root, "mounts");
            put(mounts, "");
            check(!SessionExporter.mounted(usb, mounts), "unmounted");
            put(mounts, "31 20 8:1 / " + usb.getCanonicalPath() + " rw - vfat /dev/block/sda1 rw\n");
            check(SessionExporter.mounted(usb, mounts), "mounted");
            File logs = new File(usb, "LOGS");
            File src = new File(spool, "session-001.log");
            put(src, "START\nEND\n");
            put(new File(spool, "session-002.active"), "START\n");
            SessionExporter.exportDirectory(spool, logs, "sessions");
            File dest = new File(logs, "sessions");
            File target = new File(dest, src.getName());
            check(Arrays.equals(SessionExporter.digest(src), SessionExporter.digest(target)), "verified bytes");
            check(!new File(dest, "session-002.active").exists(), "active excluded");
            check(src.exists(), "source retained");
            File complete = new File(dest, src.getName() + ".complete");
            check(complete.isFile(), "completion marker published");
            String manifest = new String(Files.readAllBytes(complete.toPath()), "UTF-8");
            StringBuilder expectedHash = new StringBuilder();
            for (byte b : SessionExporter.digest(src)) expectedHash.append(String.format("%02x", b & 255));
            check(manifest.equals("SHA256 " + expectedHash + "\nBYTES " + src.length() + "\nFILE " + src.getName() + "\n"), "manifest content");
            check(complete.delete(), "simulate interrupted marker publication");
            File markerPart = new File(dest, src.getName() + ".complete.part");
            put(markerPart, "unfinished marker");
            SessionExporter.exportDirectory(spool, logs, "sessions");
            check(complete.isFile() && !markerPart.exists(), "completion recovery");
            put(complete, "conflicting marker");
            try {
                SessionExporter.exportDirectory(spool, logs, "sessions");
                throw new AssertionError("marker conflict accepted");
            } catch (IOException expected) {}
            check(new String(Files.readAllBytes(complete.toPath()), "UTF-8").equals("conflicting marker"), "marker conflict retained");
            check(complete.delete(), "remove marker conflict");
            SessionExporter.exportDirectory(spool, logs, "sessions");

            File part = new File(dest, src.getName() + ".part");
            put(part, "interrupted");
            SessionExporter.exportDirectory(spool, logs, "sessions");
            check(!part.exists(), "stale partial cleared");
            put(target, "other data");
            File later = new File(spool, "session-003.log");
            put(later, "later complete session");
            try {
                SessionExporter.exportDirectory(spool, logs, "sessions");
                throw new AssertionError("conflict accepted");
            } catch (IOException expected) {}
            check(new String(Files.readAllBytes(target.toPath()), "UTF-8").equals("other data"), "conflict retained");
            check(Arrays.equals(SessionExporter.digest(later), SessionExporter.digest(new File(dest, later.getName()))), "later session exported despite conflict");
            check(target.delete(), "remove conflict");
            put(part, "old partial");
            SessionExporter.exportDirectory(spool, logs, "sessions");
            check(!part.exists() && Arrays.equals(SessionExporter.digest(src), SessionExporter.digest(target)), "partial recovery");
            SessionExporter.MountGuard guard = new SessionExporter.MountGuard(usb, mounts);
            File removable = new File(spool, "session-004.log");
            put(removable, "complete before removal");
            put(mounts, "");
            try {
                SessionExporter.exportOne(removable, dest, guard);
                throw new AssertionError("removed mount accepted");
            } catch (IOException expected) {}
            check(!new File(dest, removable.getName()).exists(), "removed mount not written");
            put(mounts, "32 20 8:1 / " + usb.getCanonicalPath() + " rw - vfat /dev/block/sda1 rw\n");
            try {
                guard.check();
                throw new AssertionError("same-device remount with new mount ID accepted");
            } catch (IOException expected) {}
            put(mounts, "31 20 8:1 / " + usb.getCanonicalPath() + " rw - vfat /dev/block/sda1 rw\n");
            SessionExporter.exportOne(removable, dest, new SessionExporter.MountGuard(usb, mounts));
            check(new File(dest, removable.getName()).isFile(), "export resumes on mounted USB");
            File nativeSpool = new File(root, "native");
            check(nativeSpool.mkdir(), "native spool");
            put(new File(nativeSpool, "session-101.native.log"), "native base");
            put(new File(nativeSpool, "session-101.native.log.1"), "native rotation");
            put(new File(nativeSpool, "session-102.native.log"), "unfinished");
            put(new File(nativeSpool, "session-103.native.log"), "still active");
            put(new File(nativeSpool, "session-101.native-ready"), "ready");
            put(new File(nativeSpool, "session-103.native-ready"), "ready");
            put(new File(spool, "session-103.active"), "START");
            put(new File(nativeSpool, "session-101.native.log.4"), "unsupported rotation");
            SessionExporter.exportNative(nativeSpool, spool, logs, new SessionExporter.MountGuard(usb, mounts));
            check(new File(dest, "session-101.native.log.complete").isFile(), "native base verified");
            check(new File(dest, "session-101.native.log.1.complete").isFile(), "rotation verified");
            check(!new File(dest, "session-102.native.log").exists(), "unready native excluded");
            check(!new File(dest, "session-103.native.log").exists(), "active native excluded");
            check(!new File(dest, "session-101.native.log.4").exists(), "unexpected rotation excluded");
            // Simulate mount removal after the partial file opens, before its first chunk.
            File interrupted = new File(spool, "session-005.log");
            put(interrupted, "session during USB removal");
            File interruptedPart = new File(dest, interrupted.getName()+".part");
            File interruptedTarget = new File(dest, interrupted.getName());
            final SessionExporter.MountGuard liveGuard = new SessionExporter.MountGuard(usb, mounts);
            SessionExporter.Guard removal = new SessionExporter.Guard() {
                public void check() throws IOException {
                    if (interruptedPart.exists()) put(mounts, "");
                    liveGuard.check();
                }
            };
            try {
                SessionExporter.exportOne(interrupted, dest, removal);
                throw new AssertionError("mid-copy removal accepted");
            } catch (IOException expected) {}
            check(interrupted.isFile() && !interruptedTarget.exists(), "source retained and no publication after removal");
            check(!new File(dest, interrupted.getName()+".complete").exists(), "no completion marker after removal");
            check(interruptedPart.exists(), "no cleanup on changed mount");
            put(mounts, "33 20 8:1 / " + usb.getCanonicalPath() + " rw - vfat /dev/block/sda1 rw\n");
            SessionExporter.exportOne(interrupted, dest, new SessionExporter.MountGuard(usb, mounts));
            check(!interruptedPart.exists() && Arrays.equals(SessionExporter.digest(interrupted), SessionExporter.digest(interruptedTarget)), "recovery after mid-copy removal");
            System.out.println("Java session export tests passed");
        } finally { remove(root); }
    }
}
