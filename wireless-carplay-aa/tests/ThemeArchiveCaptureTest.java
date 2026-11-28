package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

public final class ThemeArchiveCaptureTest {
    static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    interface Attempt { void run() throws IOException; }
    static void fails(Attempt a) throws IOException {
        try { a.run(); } catch (IOException expected) { return; }
        throw new AssertionError("expected rejection");
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("theme-capture-test").toFile();
        try {
            ThemeArchiveCapture good = new ThemeArchiveCapture(dir, 2, 3);
            check(good.append(0, new byte[]{97}, null) == null);
            File file = good.append(1, new byte[]{98,99}, ABC);
            check(file.getName().endsWith(".quarantine"));
            check(Arrays.equals(Files.readAllBytes(file.toPath()),new byte[]{97,98,99}));
            good.close();
            check(file.exists());
            fails(() -> good.append(2,new byte[]{1},null));
            check(file.delete());
            ThemeArchiveCapture bad = new ThemeArchiveCapture(dir,1,3);
            fails(() -> bad.append(0,new byte[]{97,98,100},ABC));
            check(dir.list().length == 0);
            ThemeArchiveCapture order = new ThemeArchiveCapture(dir,2,3);
            fails(() -> order.append(1,new byte[]{97},null));
            check(dir.list().length == 0);
            ThemeArchiveCapture size = new ThemeArchiveCapture(dir,1,2);
            fails(() -> size.append(0,new byte[]{97,98,99},ABC));
            ThemeArchiveCapture missing = new ThemeArchiveCapture(dir,1,3);
            fails(() -> missing.append(0,new byte[]{97,98,99},null));
            ThemeArchiveCapture early = new ThemeArchiveCapture(dir,2,3);
            fails(() -> early.append(0,new byte[]{97},ABC));
            ThemeArchiveCapture upper = new ThemeArchiveCapture(dir,1,3);
            fails(() -> upper.append(0,new byte[]{97,98,99},ABC.toUpperCase(java.util.Locale.ROOT)));
            ThemeArchiveCapture cancelled = new ThemeArchiveCapture(dir,2,3);
            cancelled.append(0,new byte[]{97},null);
            cancelled.close();
            check(dir.list().length == 0);
            fails(() -> new ThemeArchiveCapture(dir,0,3));
            fails(() -> new ThemeArchiveCapture(dir,1,ThemeArchiveCapture.MAX_BYTES+1));
            System.out.println("PASS theme archive quarantine: digest, bounds, sequence, cancellation");
        } finally {
            for(File f:dir.listFiles()) f.delete();
            dir.delete();
        }
    }
}
