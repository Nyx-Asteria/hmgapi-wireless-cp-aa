package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Experimental archive quarantine sink. Accepts already-decoded file chunks,
 * not raw OPACK or remote-control messages. No runtime caller, capability
 * advertisement, signature validation, installation or version acknowledgement.
 * Bounds and strict sequencing are local defensive policy, not wire constants.
 * Caller must supply an app-private directory, and route only one validated
 * header/session to this instance. Never use remote strings as filenames.
 */
final class ThemeArchiveCapture implements Closeable {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_CHUNKS = 65536;
    private final File partial, completed;
    private final int count;
    private final long limit;
    private final MessageDigest digest;
    private FileOutputStream out;
    private int next;
    private long bytes;

    ThemeArchiveCapture(File privateDir, int chunkCount, long byteLimit) throws IOException {
        if (privateDir == null || !privateDir.isDirectory() || chunkCount < 1
                || chunkCount > MAX_CHUNKS || byteLimit < 1 || byteLimit > MAX_BYTES)
            throw new IOException("invalid capture bounds");
        count = chunkCount;
        limit = byteLimit;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
        partial = File.createTempFile("theme-", ".part", privateDir);
        completed = new File(privateDir, partial.getName() + ".quarantine");
        try { out = new FileOutputStream(partial); }
        catch (IOException e) { partial.delete(); throw e; }
    }

    /** Returns a quarantined file only after final digest and durable write.
     * A null return means capture remains incomplete, not accepted/installed.
     */
    synchronized File append(int index, byte[] data, String finalChecksum) throws IOException {
        if (out == null) throw new IOException("capture closed");
        try {
            boolean last = next == count - 1;
            if (index != next || data == null || data.length > limit - bytes
                    || (!last && finalChecksum != null) || (last && !validHex(finalChecksum)))
                throw new IOException("invalid chunk sequence, bounds or checksum");
            out.write(data);
            digest.update(data);
            bytes += data.length;
            next++;
            if (!last) return null;
            if (bytes == 0 || !MessageDigest.isEqual(digest.digest(), decodeHex(finalChecksum)))
                throw new IOException("archive checksum mismatch");
            out.getFD().sync();
            out.close();
            out = null;
            if (completed.exists() || !partial.renameTo(completed))
                throw new IOException("quarantine publication failed");
            return completed;
        } catch (IOException e) {
            try { close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static boolean validHex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static byte[] decodeHex(String s) {
        byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte)((Character.digit(s.charAt(i*2),16)<<4) | Character.digit(s.charAt(i*2+1),16));
        return b;
    }

    @Override public synchronized void close() throws IOException {
        try { if (out != null) out.close(); }
        finally {
            out = null;
            if (partial.exists() && !partial.delete())
                throw new IOException("partial capture cleanup failed");
        }
    }
}
