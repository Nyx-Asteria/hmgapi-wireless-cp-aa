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
    private final AtomicInteger queued = new AtomicInteger(), dropped = new AtomicInteger();
    private File active;
    private String header;
    private boolean writeFailed;
    private volatile boolean accepting;

    SessionJournal(Context context, String folder) {
        dir = new File(context.getFilesDir(), folder);
        HandlerThread thread = new HandlerThread(folder);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(new Runnable() { public void run() {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) if (f.getName().endsWith(".active")) {
                if (append(f, row("END interrupted process/service restart"), true))
                    f.renameTo(new File(dir, f.getName().replace(".active", "-interrupted.log")));
            }
            prune();
        }});
    }

    synchronized void start(final String detail) {
        if (accepting) return;
        accepting = true;
        final long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        worker.post(new Runnable() { public void run() {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            active = new File(dir, "session-" + wall + "-" + UUID.randomUUID().toString() + ".active");
            header = wall + "\t" + elapsed + "\tSTART " + detail + "\n";
            writeFailed = !append(active, header, true);
            if (CarPlayLog.on) {
                File marker = new File(dir, active.getName().replace(".active", ".native-enabled"));
                append(marker, "enabled\n".replace("\n", ""), false);
            }
        }});
    }

    synchronized void record(String message) {
        if (!accepting) return;
        if (queued.incrementAndGet() > QUEUE) { queued.decrementAndGet(); dropped.incrementAndGet(); return; }
        final String line = row(message);
        if (!worker.post(new Runnable() { public void run() {
            try { if (active != null) {
                int lost = dropped.getAndSet(0);
                if (lost != 0) write(row("LOGGER dropped queued events=" + lost));
                write(line);
            }} finally { queued.decrementAndGet(); }
        }})) queued.decrementAndGet();
    }

    synchronized void end(String reason) {
        if (!accepting) return;
        accepting = false;
        final String line = row("END " + reason);
        worker.post(new Runnable() { public void run() {
            if (active == null) return;
            int lost = dropped.getAndSet(0);
            if (lost != 0) write(row("LOGGER dropped queued events=" + lost));
            write(line);
            // Sync final bytes before exposing a completed file to the USB exporter.
            if (!append(active, "", true)) writeFailed = true;
            if (writeFailed && !append(active, row("LOGGER incomplete recording: disk write failed"), true)) {
                active = null;
                return;
            }
            File completed = new File(dir, active.getName().replace(".active", writeFailed ? "-interrupted.log" : ".log"));
            if (active.renameTo(completed)) {
                new File(dir, active.getName().replace(".active", ".native-enabled")).delete();
            }
            active = null;
            prune();
        }});
    }

    private void write(String line) {
        if (active == null) return;
        if (active.length() + line.length() * 4L > LIMIT) {
            // Preserve the header and newest segment; explicitly flag truncation.
            try (FileOutputStream out = new FileOutputStream(active, false)) {
                out.write((header + row("LOGGER earlier events truncated at 2 MiB limit")).getBytes("UTF-8"));
            } catch (IOException ignored) { writeFailed = true; return; }
        }
        if (!append(active, line, false)) writeFailed = true;
    }

    private static boolean append(File file, String line, boolean sync) {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes("UTF-8"));
            if (sync) out.getFD().sync();
        } catch (IOException ignored) { return false; }
        return true;
    }

    private static String row(String message) {
        String clean = message.replace('\n',' ').replace('\r',' ').replace('\t',' ')
            .replaceAll("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}", "[MAC]")
            .replaceAll("(?i)(password|passphrase|psk|token|secret|ssid)\\s*[:=]\\s*[^ ,;]+", "$1=[redacted]");
        if (clean.length() > 4096) clean = clean.substring(0,4096) + " [truncated]";
        return System.currentTimeMillis() + "\t" + SystemClock.elapsedRealtime() + "\t" + clean + "\n";
    }

    private void prune() {
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File d, String n) { return n.endsWith(".log"); }
        });
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        for (int i=0; i<files.length-KEEP; i++) files[i].delete();
    }
}
