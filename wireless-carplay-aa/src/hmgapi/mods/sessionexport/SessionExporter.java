package hmgapi.mods.sessionexport;

import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Java-only verified export of finalized journal files. Runs outside DiPO. */
public final class SessionExporter {
    private static final String[] ROOTS = {
        "/storage/usb0", "/storage/usb1", "/storage/usb2",
        "/mnt/media_rw/usb0", "/mnt/media_rw/usb1", "/mnt/media_rw/usb2"
    };
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1 || !("wcp".equals(args[0]) || "stbt".equals(args[0])))
            throw new IllegalArgumentException("expected wcp or stbt");
        boolean wcp = "wcp".equals(args[0]);
        File files = new File("/data/data/com.daudio.app.dipo/files");
        String prefix = wcp ? "WCP-LOGS" : "TBT-LOGS";
        String previous = "";
        while (true) {
            String error = "";
            try {
                for (String path : ROOTS) {
                    File root = new File(path);
                    if (!mounted(root, new File("/proc/mounts"))) continue;
                    try {
                        MountGuard guard = new MountGuard(root, new File("/proc/mounts"));
                        File dest = child(root, prefix);
                        Exception first = null;
                        try { exportDirectory(new File(files, args[0] + "-carplay-sessions"), dest, "sessions", guard); }
                        catch (Exception e) { first = e; }
                        if (!wcp) {
                            try { exportDirectory(new File(files, "stbt-third-party-navigation"), dest, "third-party-navigation", guard); }
                            catch (Exception e) { if (first == null) first = e; }
                        }
                        if (first != null) throw first;
                    } catch (Exception e) { error = e.toString(); }
                }
            } catch (Exception e) { error = e.toString(); }
            if (!error.equals(previous)) {
                System.err.println(error.length() == 0 ? "Session export recovered" : "Session export: " + error);
                previous = error;
            }
            Thread.sleep(5000);
        }
    }

    interface Guard { void check() throws IOException; }
    private static final Guard NO_GUARD = new Guard() { public void check() {} };

    static final class MountGuard implements Guard {
        private final File root, mounts;
        private final String identity;
        MountGuard(File root, File mounts) throws IOException {
            this.root = root; this.mounts = mounts;
            identity = mountIdentity(root, mounts);
            if (identity == null) throw new IOException("USB not mounted");
        }
        public void check() throws IOException {
            if (!identity.equals(mountIdentity(root, mounts))) throw new IOException("USB mount changed");
        }
    }

    static boolean mounted(File root, File mounts) throws IOException {
        return mountIdentity(root, mounts) != null;
    }

    private static String mountIdentity(File root, File mounts) throws IOException {
        if (!root.isDirectory()) return null;
        String canonical = root.getCanonicalPath();
        try (BufferedReader in = new BufferedReader(new FileReader(mounts))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length > 2 && (fields[1].equals(root.getPath()) || fields[1].equals(canonical)))
                    return fields[0] + " " + fields[1] + " " + fields[2];
            }
        }
        return null;
    }

    static File child(File parent, String name) throws IOException {
        File f = new File(parent.getCanonicalFile(), name);
        if (!f.getCanonicalFile().equals(f.getAbsoluteFile()))
            throw new IOException("unsafe path: " + name);
        return f;
    }

    private static void directory(File f) throws IOException {
        if (!f.isDirectory() && !f.mkdirs() && !f.isDirectory())
            throw new IOException("cannot create " + f.getName());
    }

    static void exportDirectory(File source, File root, String kind) throws Exception {
        exportDirectory(source, root, kind, NO_GUARD);
    }

    static void exportDirectory(File source, File root, String kind, Guard guard) throws Exception {
        if (!source.isDirectory()) return;
        File[] files = source.listFiles();
        if (files == null) throw new IOException("cannot list journals");
        Arrays.sort(files);
        Exception failure = null;
        for (File src : files) {
            String name = src.getName();
            if (!name.matches("session-[a-zA-Z0-9-]+\\.log")) continue;
            try {
                if (!src.isFile() || !child(source, name).equals(src.getCanonicalFile())) continue;
                guard.check();
                directory(root);
                File dest = child(root, kind);
                guard.check();
                directory(dest);
                exportOne(src, dest, guard);
            } catch (Exception e) {
                if (failure == null) failure = e;
            }
        }
        if (failure != null) throw failure;
    }

    static void exportOne(File source, File dest) throws Exception {
        exportOne(source, dest, NO_GUARD);
    }

    static void exportOne(File source, File dest, Guard guard) throws Exception {
        guard.check();
        if (source.length() > MAX_BYTES) throw new IOException("journal exceeds export limit");
        File target = child(dest, source.getName());
        File part = child(dest, source.getName() + ".part");
        byte[] expected = digest(source);
        if (target.isFile() && Arrays.equals(expected, digest(target))) {
            guard.check();
            if (part.exists() && !part.delete()) throw new IOException("cannot remove stale partial");
            return;
        }
        if (target.exists()) throw new IOException("destination conflict: " + target.getName());
        if (dest.getUsableSpace() < source.length() + 65536) throw new IOException("USB space low");
        guard.check();
        if (part.exists() && !part.delete()) throw new IOException("cannot clear partial");
        boolean published = false;
        try {
            guard.check();
            try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[32768];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("journal grew during copy");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            guard.check();
            if (!Arrays.equals(expected, digest(part)) || !Arrays.equals(expected, digest(source)))
                throw new IOException("journal verification failed");
            guard.check();
            if (target.exists() || !part.renameTo(target)) throw new IOException("cannot publish journal");
            published = true;
            System.out.println("Verified export: " + target.getName());
        } finally {
            if (!published) {
                try { guard.check(); part.delete(); } catch (IOException ignored) { /* Never clean up on a different mount. */ }
            }
        }
    }

    static byte[] digest(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("file exceeds export limit");
                md.update(buffer, 0, n);
            }
        }
        return md.digest();
    }
}
