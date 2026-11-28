package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

public final class ThemeQuarantineBudgetTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void reject(File dir)throws IOException{
        try{
            ThemeArchiveCapture c=ThemeQuarantineBudget.admit(dir,1,3);
            c.close();
        }catch(IOException expected){return;}
        throw new AssertionError("unexpected admission");
    }
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("theme-budget").toFile();
        ArrayList<ThemeArchiveCapture> active=new ArrayList<ThemeArchiveCapture>();
        try{
            for(int i=0;i<ThemeQuarantineBudget.MAX_SLOTS;i++)
                active.add(ThemeQuarantineBudget.admit(dir,1,3));
            reject(dir);check(dir.listFiles().length==4);
            active.remove(0).close();
            active.add(ThemeQuarantineBudget.admit(dir,1,3));
            reject(dir);
            for(ThemeArchiveCapture c:active)c.close();
            active.clear();
            ThemeTransferSession.Captured captured=ThemeCaptureMetadataTest.capture(dir);
            ThemeCaptureMetadata.persist(captured);
            for(int i=0;i<3;i++)active.add(ThemeQuarantineBudget.admit(dir,1,3));
            reject(dir);check(captured.archive.isFile());
            for(ThemeArchiveCapture c:active)c.close();
            active.clear();
            check(captured.archive.delete());
            // Orphan metadata still consumes its slot.
            for(int i=0;i<3;i++)active.add(ThemeQuarantineBudget.admit(dir,1,3));
            reject(dir);
            for(ThemeArchiveCapture c:active)c.close();
            active.clear();
            File unknown=new File(dir,"unexpected.txt");
            check(unknown.createNewFile());
            reject(dir);check(unknown.isFile());check(unknown.delete());
            File conflict=new File(dir,"theme-conflict.part");
            check(conflict.createNewFile());
            check(new File(dir,"theme-conflict.part.quarantine").createNewFile());
            reject(dir);check(conflict.isFile());
            System.out.println("PASS quarantine admission: active, completed, orphan, conflict, no eviction");
        }finally{
            for(ThemeArchiveCapture c:active)c.close();
            for(File f:dir.listFiles())f.delete();
            dir.delete();
        }
    }
}
