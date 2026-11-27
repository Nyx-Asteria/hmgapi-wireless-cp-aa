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
            put(mounts, "device " + usb.getCanonicalPath() + " vfat rw 0 0\n");
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
            System.out.println("Java session export tests passed");
        } finally { remove(root); }
    }
}
