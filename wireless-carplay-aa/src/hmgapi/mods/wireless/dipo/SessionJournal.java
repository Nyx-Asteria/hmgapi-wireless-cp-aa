package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded private session journal. All disk work runs off projection callbacks. */
final class SessionJournal {
    private static final long LIMIT = 2L * 1024 * 1024;
    private static final int KEEP = 12, QUEUE = 256;
    private final Handler worker;
    private final File dir;
    private final AtomicInteger queued = new AtomicInteger();
    // Each accepted generation owns its own drop counter, even while old work drains.
    private AtomicInteger dropped;
    private File active;
    private String header;
    private boolean writeFailed;
    private volatile boolean accepting;

    SessionJournal(Context context, String folder) {
        dir = new File(context.getFilesDir(), folder);
        HandlerThread thread = new HandlerThread(folder);
        thread.start();
        worker = new Handler(thread.getLooper());
        post(new Runnable() { public void run() {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            recoverInterrupted();
            prune();
        }});
    }

    /** A new creation attempt is a recording boundary, even without finalization. */
    synchronized void beginAttempt(String detail) {
        end("superseded by session creation callback");
        start(detail);
    }

    synchronized void start(final String detail) {
        if (accepting) return;
        accepting = true;
        dropped = new AtomicInteger();
        final long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        post(new Runnable() { public void run() {
            active = null;
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            recoverInterrupted();
            if (!prune()) return;
            active = new File(dir, "session-" + wall + "-" + UUID.randomUUID().toString() + ".active");
            header = row("START " + bounded(detail), wall, elapsed);
            writeFailed = !append(active, header, true);
            if (!writeFailed && CarPlayLog.on) {
                File marker = new File(dir, active.getName().replace(".active", ".native-enabled"));
                append(marker, "enabled\n".replace("\n", ""), false);
            }
        }});
    }

    synchronized void record(String message) {
        if (!accepting) return;
        final AtomicInteger sessionDropped = dropped;
        if (queued.incrementAndGet() > QUEUE) { queued.decrementAndGet(); sessionDropped.incrementAndGet(); return; }
        final long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        final String raw = bounded(message);
        if (!post(new Runnable() { public void run() {
            try { if (active != null) {
                int lost = sessionDropped.getAndSet(0);
                if (lost != 0) write(row("LOGGER dropped queued events=" + lost));
                write(row(raw, wall, elapsed));
            }} finally { queued.decrementAndGet(); }
        }})) { queued.decrementAndGet(); sessionDropped.incrementAndGet(); }
    }

    synchronized void end(String reason) {
        if (!accepting) return;
        accepting = false;
        final AtomicInteger sessionDropped = dropped;
        dropped = null;
        final long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        final String raw = bounded("END " + reason);
        post(new Runnable() { public void run() {
            if (active == null) return;
            // Stop native capture even when journal finalization fails.
            retireMarker(active);
            int lost = sessionDropped.getAndSet(0);
            if (lost != 0) write(row("LOGGER dropped queued events=" + lost));
            write(row(raw, wall, elapsed));
            // Sync final bytes before exposing a completed file to the USB exporter.
            if (!append(active, "", true)) writeFailed = true;
            if (writeFailed && !append(active, row("LOGGER incomplete recording: disk write failed"), true)) {
                active = null;
                prune();
                return;
            }
            File completed = new File(dir, active.getName().replace(".active", writeFailed ? "-interrupted.log" : ".log"));
            if (active.renameTo(completed)) {
                retireMarker(active);
            }
            active = null;
            prune();
        }});
    }

    // Filesystem permission failures must not kill the journal HandlerThread.
    private boolean post(final Runnable task) {
        return worker.post(new Runnable() { public void run() {
            try { task.run(); }
            catch (SecurityException failure) {
                active = null;
                writeFailed = true;
            }
        }});
    }

    private void write(String line) {
        if (active == null) return;
        if (active.length() + line.length() * 4L > LIMIT) {
            // Preserve the header and newest segment; explicitly flag truncation.
            try (FileOutputStream out = new FileOutputStream(active, false)) {
                out.write((header + row("LOGGER earlier events truncated at 2 MiB limit")).getBytes("UTF-8"));
            } catch (IOException | SecurityException ignored) { writeFailed = true; return; }
        }
        if (!append(active, line, false)) writeFailed = true;
    }

    private static boolean append(File file, String line, boolean sync) {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes("UTF-8"));
            if (sync) out.getFD().sync();
        } catch (IOException | SecurityException ignored) { return false; }
        return true;
    }

    private static String bounded(String message) {
        if (message == null) return "";
        return message.length() > 8192 ? message.substring(0,8192) + " [truncated]" : message;
    }

    private static String row(String message) {
        return row(bounded(message), System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    private static String row(String message, long wall, long elapsed) {
        String clean = message.replace('\n',' ').replace('\r',' ').replace('\t',' ')
            .replaceAll("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}", "[MAC]")
            .replaceAll("(?i)(password|passphrase|psk|token|secret|ssid)\\s*[:=]\\s*[^ ,;]+", "$1=[redacted]");
        if (clean.length() > 4096) clean = clean.substring(0,4096) + " [truncated]";
        return wall + "\t" + elapsed + "\t" + clean + "\n";
    }

    /** Retry interrupted recordings before opening another session on the worker. */
    private void recoverInterrupted() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".active")) continue;
            File target = new File(dir, f.getName().replace(".active", "-interrupted.log"));
            retireMarker(f);
            // Bound recovery growth even when the final rename repeatedly fails.
            if (!target.exists() && append(f, f.length() < LIMIT ? row("END interrupted process/service restart or failed finalization") : "", true)
                    && f.renameTo(target)) {
                retireMarker(f);
            }
        }
    }

    /** Keep failed recordings separate from the completed retention budget. */
    private boolean pruneOrphans() {
        return pruneFiles(".active", true);
    }

    private boolean pruneFiles(final String suffix, boolean orphan) {
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File d, String n) {
                return n.startsWith("session-") && n.endsWith(suffix) && new File(d, n).isFile();
            }
        });
        if (files == null) return false;
        int count = files.length;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        for (File f : files) {
            if (count <= KEEP) break;
            if (f.equals(active)) continue;
            if (orphan) retireMarker(f);
            if (f.delete() || !f.exists()) count--;
        }
        return count <= KEEP;
    }

    private boolean prune() {
        boolean orphans = pruneOrphans();
        boolean completed = pruneFiles(".log", false);
        return orphans && completed;
    }

    private void retireMarker(File recording) {
        File marker = new File(dir, recording.getName().replace(".active", ".native-enabled"));
        if (marker.exists() && !marker.delete()) {
            // Fail closed when unlink is denied but the file remains writable.
            try (FileOutputStream out = new FileOutputStream(marker, false)) {
                out.write("disabled".getBytes("UTF-8"));
                out.getFD().sync();
            } catch (IOException | SecurityException ignored) { }
        }
    }
}
