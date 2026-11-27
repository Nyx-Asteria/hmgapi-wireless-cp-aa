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
    interface DirectorySync { void flush(File dir) throws Exception; }
    // Android API 19 does not expose android.system.Os as a public SDK API.
    // Resolve the platform implementation at runtime; an unavailable or refusing
    // filesystem is a best-effort durability limitation, not a reason to lose logs.
    static DirectorySync directorySync = new DirectorySync() {
        public void flush(File dir) throws Exception {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Class<?> osType = Class.forName("libcore.io.Os");
            Class<?> constants = Class.forName("libcore.io.OsConstants");
            Object os = libcore.getField("os").get(null);
            int flags = constants.getField("O_RDONLY").getInt(null)
                    | constants.getField("O_DIRECTORY").getInt(null);
            FileDescriptor fd = (FileDescriptor) osType.getMethod("open", String.class, int.class, int.class)
                    .invoke(os, dir.getAbsolutePath(), flags, 0);
            try {
                osType.getMethod("fsync", FileDescriptor.class).invoke(os, fd);
            } finally {
                osType.getMethod("close", FileDescriptor.class).invoke(os, fd);
            }
        }
    };
    private static boolean syncWarning;
    private static void flushDirectory(File dir, Guard guard) throws IOException {
        guard.check();
        try { directorySync.flush(dir); }
        catch (Exception | LinkageError failure) {
            if (!syncWarning) {
                System.err.println("Session export: directory sync unavailable; abrupt power-loss durability not guaranteed");
                syncWarning = true;
            }
        }
        guard.check();
    }

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
                    if (!mounted(root, new File("/proc/self/mountinfo"))) continue;
                    try {
                        MountGuard guard = new MountGuard(root, new File("/proc/self/mountinfo"));
                        File dest = child(root, prefix);
                        Exception first = null;
                        try { exportDirectory(new File(files, args[0] + "-carplay-sessions"), dest, "sessions", guard); }
                        catch (Exception e) { first = e; }
                        if (!wcp) {
                            try { exportDirectory(new File(files, "stbt-third-party-navigation"), dest, "third-party-navigation", guard); }
                            catch (Exception e) { if (first == null) first = e; }
                        }
                        if (wcp) {
                            try { exportNative(new File("/data/hmgapi/logs/wcp-native-session"),
                                    new File(files, "wcp-carplay-sessions"), dest, guard); }
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
                int separator = Arrays.asList(fields).indexOf("-");
                if (separator >= 6 && fields.length > separator + 2
                        && (fields[4].equals(root.getPath()) || fields[4].equals(canonical)))
                    return fields[0] + " " + fields[2] + " " + fields[3] + " " + fields[4]
                            + " " + fields[separator + 1] + " " + fields[separator + 2];
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

    /** Only stopped, opted-in native collectors publish readiness markers. */
    static void exportNative(File source, File journals, File root, Guard guard) throws Exception {
        if (!source.isDirectory()) return;
        File[] files = source.listFiles();
        if (files == null) throw new IOException("cannot list native journals");
        Arrays.sort(files);
        Exception failure = null;
        for (File src : files) {
            String name = src.getName();
            if (!name.matches("session-[a-zA-Z0-9-]+\\.native\\.log(\\.[123])?")) continue;
            String base = name.substring(0, name.indexOf(".native.log"));
            try {
                File ready = child(source, base + ".native-ready");
                if (!ready.isFile() || new File(journals, base + ".active").exists()) continue;
                if (!src.isFile() || !child(source, name).equals(src.getCanonicalFile())) continue;
                guard.check();
                directory(root);
                File dest = child(root, "sessions");
                guard.check();
                directory(dest);
                exportOne(src, dest, guard);
            } catch (Exception e) { if (failure == null) failure = e; }
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
            complete(target, expected, guard, true);
            return;
        }
        // A known completion marker is no longer valid if its payload is missing or changed.
        // Preserve the old marker as evidence; never overwrite the conflicting payload.
        retireKnownMarker(source, target, expected, guard);
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
                    guard.check();
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
            flushDirectory(dest, guard);
            complete(target, expected, guard, false);
            System.out.println("Verified export: " + target.getName());
        } finally {
            if (!published) {
                try { guard.check(); part.delete(); } catch (IOException ignored) { /* Never clean up on a different mount. */ }
            }
        }
    }

    private static void retireKnownMarker(File source, File target, byte[] hash, Guard guard) throws Exception {
        File dest = target.getParentFile();
        File marker = child(dest, target.getName() + ".complete");
        byte[] known = manifestBytes(target.getName(), source.length(), hash);
        if (!marker.isFile() || marker.length() != known.length ||
                !Arrays.equals(MessageDigest.getInstance("SHA-256").digest(known), digest(marker))) return;
        File invalid = child(dest, target.getName() + ".complete.invalid");
        guard.check();
        // If an earlier invalidation already exists, preserve both markers rather than overwrite evidence.
        if (invalid.exists()) {
            if (!invalid.isFile() || !Arrays.equals(digest(marker), digest(invalid)))
                throw new IOException("invalid marker conflict: " + invalid.getName());
            guard.check();
            if (!marker.delete()) throw new IOException("cannot retire stale marker");
        } else {
            guard.check();
            if (!marker.renameTo(invalid)) throw new IOException("cannot retire stale marker");
        }
        flushDirectory(dest, guard);
    }

    private static byte[] manifestBytes(String name, long length, byte[] hash) throws UnsupportedEncodingException {
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            int v = b & 255;
            hex.append(Character.forDigit(v >>> 4, 16)).append(Character.forDigit(v & 15, 16));
        }
        return ("SHA256 " + hex + "\nBYTES " + length + "\nFILE " + name + "\n").getBytes("UTF-8");
    }

    /** Publish a per-file manifest only after the final USB file has been verified. */
    private static void complete(File target, byte[] hash, Guard guard, boolean alreadyVerified) throws Exception {
        guard.check();
        File dest = target.getParentFile();
        File marker = child(dest, target.getName() + ".complete");
        File part = child(dest, target.getName() + ".complete.part");
        if (!alreadyVerified && !Arrays.equals(hash, digest(target))) throw new IOException("published journal verification failed");
        byte[] manifest = manifestBytes(target.getName(), target.length(), hash);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(manifest);
        guard.check();
        if (marker.isFile() && marker.length() == manifest.length && Arrays.equals(expected, digest(marker))) {
            if (part.exists() && !part.delete()) throw new IOException("cannot clear stale manifest partial");
            return;
        }
        if (marker.exists()) throw new IOException("completion marker conflict: " + marker.getName());
        if (part.exists() && !part.delete()) throw new IOException("cannot clear manifest partial");
        boolean published = false;
        try {
            guard.check();
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(manifest);
                out.getFD().sync();
            }
            guard.check();
            if (!Arrays.equals(expected, digest(part)) || !Arrays.equals(hash, digest(target)))
                throw new IOException("completion verification failed");
            guard.check();
            if (marker.exists() || !part.renameTo(marker)) throw new IOException("cannot publish completion marker");
            published = true;
            flushDirectory(dest, guard);
        } finally {
            if (!published) {
                try { guard.check(); part.delete(); } catch (IOException ignored) {}
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
