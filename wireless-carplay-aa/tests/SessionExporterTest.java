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

    static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) out.append(String.format("%02x",b&255));
        return out.toString();
    }
    static void hashRegression() throws Exception {
        check(hex(SessionExporter.digest(new byte[0])).equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),"empty SHA-256");
        check(hex(SessionExporter.digest("abc".getBytes("UTF-8"))).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"abc SHA-256");
        byte[] million=new byte[1000000];Arrays.fill(million,(byte)'a');
        check(hex(SessionExporter.digest(million)).equals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),"million a SHA-256");
        java.util.Random rng=new java.util.Random(12345);
        for(int len:new int[]{1,55,56,57,63,64,65,119,120,127,128,129,32767,32768,32769,4194304}){
            byte[] data=new byte[len];rng.nextBytes(data);
            byte[] expected=java.security.MessageDigest.getInstance("SHA-256").digest(data);
            check(Arrays.equals(expected,SessionExporter.digest(data)),"one-shot boundary "+len);
            SessionExporter.Sha256 stream=new SessionExporter.Sha256();
            for(int off=0;off<len;){int n=Math.min(37,len-off);stream.update(data,off,n);off+=n;}
            check(Arrays.equals(expected,stream.digest()),"stream boundary "+len);
        }
    }
    public static void main(String[] args) throws Exception {
        hashRegression();
        File root = Files.createTempDirectory("java-session-export").toFile();
        // Remove every JCA provider during the actual export regression.
        java.security.Provider[] providers=java.security.Security.getProviders();
        for(java.security.Provider provider:providers) java.security.Security.removeProvider(provider.getName());
        try {
        try { java.security.MessageDigest.getInstance("SHA-256"); throw new AssertionError("provider unexpectedly available"); }
        catch(java.security.NoSuchAlgorithmException expected) {}
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
            File invalid = new File(dest, src.getName() + ".complete.invalid");
            check(!complete.exists() && invalid.isFile(), "stale known marker retired");
            check(new String(Files.readAllBytes(invalid.toPath()), "UTF-8").equals(manifest), "old manifest preserved");
            check(target.delete(), "remove conflict");
            put(part, "old partial");
            SessionExporter.exportDirectory(spool, logs, "sessions");
            check(!part.exists() && Arrays.equals(SessionExporter.digest(src), SessionExporter.digest(target)), "partial recovery");
            check(complete.isFile() && invalid.isFile(), "fresh completion and historical evidence coexist");
            check(target.delete(), "simulate missing exported payload");
            SessionExporter.exportDirectory(spool, logs, "sessions");
            check(target.isFile() && complete.isFile() && invalid.isFile(), "missing payload recovered");
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
            final File durable = new File(spool, "session-006.log");
            put(durable, "directory sync fixture");
            final File durableTarget = new File(dest, durable.getName());
            final File durableMarker = new File(dest, durable.getName()+".complete");
            final java.util.List<String> flushes = new java.util.ArrayList<String>();
            SessionExporter.DirectorySync original = SessionExporter.directorySync;
            try {
                SessionExporter.directorySync = new SessionExporter.DirectorySync() {
                    public void flush(File dir) throws Exception {
                        check(dir.equals(dest), "sync destination directory");
                        check(durableTarget.isFile(), "payload published before sync");
                        flushes.add(durableMarker.exists() ? "marker" : "payload");
                    }
                };
                SessionExporter.exportOne(durable, dest);
                check(flushes.equals(Arrays.asList("payload","marker")), "ordered directory sync boundaries");
                SessionExporter.directorySync = new SessionExporter.DirectorySync() {
                    public void flush(File dir) throws Exception { throw new IOException("unsupported fsync"); }
                };
                File fallback = new File(spool, "session-007.log");
                put(fallback, "fallback");
                SessionExporter.exportOne(fallback, dest);
                check(new File(dest, fallback.getName()+".complete").isFile() && fallback.isFile(), "unsupported directory sync preserves verified export and source");
            } finally { SessionExporter.directorySync = original; }

            File bundles=new File(root,"bundles");check(bundles.mkdir(),"bundle spool");
            String id="2026-11-27_13-00-00_session-7_12345678-1234-1234-1234-123456789abc";
            File bundle=new File(bundles,id);check(bundle.mkdir(),"bundle");
            put(new File(bundle,"summary.txt"),"end");
            put(new File(bundle,"session.log"),"data");
            put(new File(bundle,"errors.log"),"");
            File diag=new File(bundle,"diagnostics");check(diag.mkdir(),"diagnostics");put(new File(diag,"core.log"),"metadata");
            put(new File(bundle,"secret.bin"),"excluded");
            File open=new File(bundles,id+".open");check(open.mkdir(),"open session");
            File bundleDest=new File(logs,id);
            put(mounts,"41 20 8:1 / "+usb.getCanonicalPath()+" rw - vfat /dev/block/sda1 rw\n");
            SessionExporter.MountGuard bundleGuard=new SessionExporter.MountGuard(usb,mounts);
            SessionExporter.exportBundles(bundles,logs,bundleGuard);
            check(new File(bundleDest,"COMPLETE.txt").isFile(),"bundle complete");
            check(new File(bundleDest,"diagnostics/core.log").isFile(),"category exported");
            check(!new File(bundleDest,"secret.bin").exists(),"unapproved file excluded");
            check(!new File(logs,open.getName()).exists(),"open excluded");
            check(new File(bundle,".exported").delete(),"simulate local marker loss");
            SessionExporter.exportBundles(bundles,logs,bundleGuard);
            check(new File(bundle,".exported").delete(),"marker recovered");
            put(new File(bundleDest,"session.log"),"corrupt");
            try{SessionExporter.exportBundles(bundles,logs,bundleGuard);throw new AssertionError("corrupt bundle accepted");}catch(IOException expected){}
            check(!new File(bundle,".exported").exists(),"conflict not marked");
            put(new File(bundleDest,"session.log"),"data");
            put(mounts,"42 20 8:1 / "+usb.getCanonicalPath()+" rw - vfat /dev/block/sda1 rw\n");
            try{SessionExporter.exportBundles(bundles,logs,bundleGuard);throw new AssertionError("remount accepted");}catch(IOException expected){}
            check(!new File(bundle,".exported").exists(),"remount not marked");
            SessionExporter.exportBundles(bundles,logs,new SessionExporter.MountGuard(usb,mounts));
            check(new File(bundle,".exported").isFile(),"retry verified");
            System.out.println("Java session export tests passed");
        } finally { remove(root); }
    } finally { for(java.security.Provider provider:providers) java.security.Security.addProvider(provider); }
    }
}
