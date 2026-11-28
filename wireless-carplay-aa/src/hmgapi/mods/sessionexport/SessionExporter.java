package hmgapi.mods.sessionexport;

import java.io.*;
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
                        try { exportBundles(new File(files, args[0] + "-session-bundles"), dest, guard); }
                        catch (Exception e) { if (first == null) first = e; }
                        if (!wcp) {
                            try { exportBundles(new File(files, "stbt-third-party-bundles"), child(dest, "third-party-navigation"), guard); }
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
                !Arrays.equals(digest(known), digest(marker))) return;
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
        byte[] expected = digest(manifest);
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
        Sha256 md = new Sha256();
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

    /**
     * Streaming SHA-256 (FIPS 180-4), no JCA provider or native dependencies.
     * Export verification only; not a signature or authentication primitive.
     * Prefer this deterministic implementation on the legacy head unit, where
     * a registered provider can fail at construction or during digest updates.
     */
    static final class Sha256 {
        private static final int[] K = {
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
        };
        private final int[] h={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
        private final int[] w=new int[64];
        private final byte[] block=new byte[64];
        private long length;
        private int used;
        private boolean finished;
        void update(byte[] bytes,int off,int n) {
            if(finished) throw new IllegalStateException("digest finished");
            if(off<0 || n<0 || off>bytes.length-n) throw new IndexOutOfBoundsException();
            length+=n;
            while(n>0) {
                int take=Math.min(n,64-used);
                System.arraycopy(bytes,off,block,used,take);
                used+=take;off+=take;n-=take;
                if(used==64){compress();used=0;}
            }
        }
        private void compress() {
            for(int i=0;i<16;i++){int j=i*4;w[i]=((block[j]&255)<<24)|((block[j+1]&255)<<16)|((block[j+2]&255)<<8)|(block[j+3]&255);}
            for(int i=16;i<64;i++){
                int x=w[i-15],y=w[i-2];
                int s0=Integer.rotateRight(x,7)^Integer.rotateRight(x,18)^(x>>>3);
                int s1=Integer.rotateRight(y,17)^Integer.rotateRight(y,19)^(y>>>10);
                w[i]=w[i-16]+s0+w[i-7]+s1;
            }
            int a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],z=h[7];
            for(int i=0;i<64;i++){
                int s1=Integer.rotateRight(e,6)^Integer.rotateRight(e,11)^Integer.rotateRight(e,25);
                int ch=(e&f)^(~e&g);
                int t1=z+s1+ch+K[i]+w[i];
                int s0=Integer.rotateRight(a,2)^Integer.rotateRight(a,13)^Integer.rotateRight(a,22);
                int maj=(a&b)^(a&c)^(b&c);
                int t2=s0+maj;
                z=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
            }
            h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=z;
        }
        byte[] digest(){
            if(finished) throw new IllegalStateException("digest finished");
            finished=true;
            long bits=length*8L;
            block[used++]=(byte)0x80;
            if(used>56){Arrays.fill(block,used,64,(byte)0);compress();used=0;}
            Arrays.fill(block,used,56,(byte)0);
            for(int i=0;i<8;i++) block[63-i]=(byte)(bits>>>(i*8));
            compress();
            byte[] result=new byte[32];
            for(int i=0;i<8;i++)for(int j=0;j<4;j++)result[i*4+j]=(byte)(h[i]>>>(24-j*8));
            return result;
        }
    }
    static byte[] digest(byte[] bytes){
        Sha256 md=new Sha256();md.update(bytes,0,bytes.length);return md.digest();
    }

    // CPUL-style immutable directory publication. Keep the root service as the
    // only USB publisher; the DiPO writer owns only its internal spool.
    private static boolean sessionFolder(String name) {
        return name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}_session-[0-9]+_[0-9a-f-]{36}");
    }
    static void exportBundles(File spool, File root, Guard guard) throws Exception {
        File[] sessions=spool.listFiles();
        if(sessions==null)return;
        Arrays.sort(sessions);
        Exception first=null;
        for(File source:sessions){
            if(!sessionFolder(source.getName())||!source.isDirectory())continue;
            try {
                child(spool,source.getName());
                if(child(source,".exported").isFile())continue;
                exportBundle(source,root,guard);
            } catch(Exception e){if(first==null)first=e;}
        }
        if(first!=null)throw first;
    }
    private static boolean bundlePayload(String name,boolean diagnostic){
        return diagnostic ? name.matches("[a-z_]+\\.log") :
            name.equals("summary.txt")||name.equals("session.log")||name.equals("errors.log");
    }
    private static String bundleManifest(File root,Guard guard)throws Exception{
        if(!child(root,"summary.txt").isFile()||!child(root,"session.log").isFile()||!child(root,"errors.log").isFile())
            throw new IOException("incomplete session directory");
        StringBuilder out=new StringBuilder();
        long[] bytes={0};
        bundleEntries(root,false,guard,out,bytes);
        return out.toString();
    }
    private static void bundleEntries(File root,boolean diagnostic,Guard guard,StringBuilder out,long[] bytes)throws Exception{
        guard.check();
        File[] files=root.listFiles();
        if(files==null)throw new IOException("cannot list session");
        Arrays.sort(files);
        for(File f:files){
            String name=f.getName();
            if(!diagnostic&&name.equals("diagnostics")){
                File safe=child(root,name);
                if(!safe.isDirectory())throw new IOException("invalid diagnostics directory");
                bundleEntries(safe,true,guard,out,bytes);
            }else if(bundlePayload(name,diagnostic)){
                File safe=child(root,name);
                if(!safe.isFile())throw new IOException("invalid session payload");
                long length=safe.length();
                if(length<0||length>8L*1024*1024||bytes[0]+length>8L*1024*1024)throw new IOException("session size limit");
                Sha256 md=new Sha256();
                long count=0;
                try(InputStream in=new FileInputStream(safe)){
                    byte[] buffer=new byte[32768];int n;
                    while((n=in.read(buffer))!=-1){guard.check();count+=n;if(count>length)throw new IOException("session grew");md.update(buffer,0,n);}
                }
                if(count!=length||safe.length()!=length)throw new IOException("session changed");
                bytes[0]+=count;
                out.append(diagnostic?"diagnostics/":"").append(name).append(' ').append(count).append(' ');
                for(byte b:md.digest()){int v=b&255;out.append(Character.forDigit(v>>>4,16)).append(Character.forDigit(v&15,16));}
                out.append('\n');
            }
        }
    }
    private static void copyBundle(File source,File dest,boolean diagnostic,Guard guard)throws IOException{
        guard.check();
        directory(dest);
        File[] files=source.listFiles();
        if(files==null)throw new IOException("cannot list source");
        for(File f:files){
            String name=f.getName();
            if(!diagnostic&&name.equals("diagnostics")){
                copyBundle(child(source,name),child(dest,name),true,guard);
            }else if(bundlePayload(name,diagnostic)){
                File input=child(source,name), output=child(dest,name);
                guard.check();
                if(output.exists())throw new IOException("partial payload conflict");
                try(InputStream in=new FileInputStream(input);FileOutputStream out=new FileOutputStream(output)){
                    byte[] buffer=new byte[32768];int n;long count=0;
                    while((n=in.read(buffer))!=-1){guard.check();count+=n;if(count>8L*1024*1024)throw new IOException("payload size limit");out.write(buffer,0,n);}
                    out.getFD().sync();
                }
            }
        }
        flushDirectory(dest,guard);
    }
    private static void clearBundlePartial(File root,boolean diagnostic,Guard guard)throws IOException{
        guard.check();
        File[] files=root.listFiles();
        if(files==null)throw new IOException("invalid partial directory");
        for(File f:files){
            File safe=child(root,f.getName());
            if(!diagnostic&&f.getName().equals("diagnostics")&&safe.isDirectory())clearBundlePartial(safe,true,guard);
            else if((bundlePayload(f.getName(),diagnostic)||(!diagnostic&&f.getName().equals("COMPLETE.txt")))&&safe.isFile()){
                guard.check();if(!safe.delete())throw new IOException("cannot remove partial file");
            }else throw new IOException("unknown partial entry; retained");
        }
        guard.check();if(!root.delete())throw new IOException("cannot remove partial directory");
    }
    private static String bundleMarker(File file)throws IOException{
        if(!file.isFile()||file.length()>65536)throw new IOException("invalid completion marker");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("marker too large");out.write(b,0,n);}
        }
        return out.toString("UTF-8");
    }
    static void exportBundle(File source,File root,Guard guard)throws Exception{
        if(!sessionFolder(source.getName()))throw new IOException("invalid session name");
        String manifest=bundleManifest(source,guard);
        guard.check();directory(root);
        File target=child(root,source.getName()),partial=child(root,source.getName()+".partial");
        if(!target.exists()){
            if(partial.exists())clearBundlePartial(partial,false,guard);
            if(root.getUsableSpace()<9L*1024*1024)throw new IOException("USB space low");
            copyBundle(source,partial,false,guard);
            if(!manifest.equals(bundleManifest(partial,guard))||!manifest.equals(bundleManifest(source,guard)))throw new IOException("bundle copy verification failed");
            guard.check();
            try(FileOutputStream out=new FileOutputStream(child(partial,"COMPLETE.txt"))){out.write(manifest.getBytes("UTF-8"));out.getFD().sync();}
            flushDirectory(partial,guard);
            guard.check();
            if(target.exists()||!partial.renameTo(target))throw new IOException("bundle publication failed");
            flushDirectory(root,guard);
        }
        guard.check();
        if(!manifest.equals(bundleMarker(child(target,"COMPLETE.txt")))||!manifest.equals(bundleManifest(target,guard)))throw new IOException("bundle destination conflict");
        guard.check();
        // Mark only after verified publication. A failed local marker can be retried.
        File marker=child(source,".exported");
        try(FileOutputStream out=new FileOutputStream(marker)){out.write("verified\n".getBytes("UTF-8"));out.getFD().sync();}
    }
}
