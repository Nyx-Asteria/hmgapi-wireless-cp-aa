"""Production startup recorder -> checkpoint -> verified export without projection."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
src = root / "src/hmgapi/mods"
stubs = {
    "android/content/Context.java": """package android.content;
import java.io.File;
public class Context { private final File dir; public Context(File d){dir=d;} public File getFilesDir(){return dir;} }""",
    "android/os/SystemClock.java": """package android.os;
public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000L;} }""",
    "android/util/Log.java": """package android.util;
public class Log { public static int i(String t,String m){return 0;} public static int e(String t,String m){System.err.println(m);return 0;} }""",
    "StartupExportTest.java": """package hmgapi.mods.sessionexport;
import android.content.Context;
import hmgapi.mods.wireless.dipo.StartupRecording;
import java.io.*;
import java.nio.file.*;
public class StartupExportTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),"UTF-8");}
 static File waitFinal(File spool,int expected)throws Exception{
    long deadline=System.nanoTime()+10000000000L;
    while(System.nanoTime()<deadline){
       File[] files=spool.listFiles(f->f.isDirectory()&&!f.getName().endsWith(".open"));
       if(files!=null && files.length==expected){java.util.Arrays.sort(files);return files[files.length-1];}
       Thread.sleep(20);
    }
    throw new AssertionError("checkpoint did not finalize "+spool);
 }
 public static void main(String[] args)throws Exception{
    File root=new File(args[0]);
    SessionExporter.directorySync=dir->{};
    for(String name:new String[]{"startup-dipo","startup-iap2"}){
       StartupRecording recorder=new StartupRecording(new Context(root),name);
       File spool=new File(root,name);
       recorder.record("pre-projection receiver readiness");
       recorder.error("application transport failure");
       recorder.record("psk=PRIVATE_TEST_VALUE");
       recorder.rotate();
       File source=waitFinal(spool,1);
       check(read(new File(source,"session.log")).contains("receiver readiness"),"missing pre-projection event");
       check(read(new File(source,"errors.log")).contains("application transport failure"),"error not preserved");
       check(!read(new File(source,"session.log")).contains("PRIVATE_TEST_VALUE"),"secret escaped");
       File dest=new File(root,"usb/"+name);
       SessionExporter.exportBundles(spool,dest,()->{});
       check(new File(dest,source.getName()+"/COMPLETE.txt").isFile(),"verified USB completion missing");
       check(SessionExporter.verifiedBundleMarker(source),"source marker missing");
       recorder.record("second failed negotiation");
       recorder.rotate();
       waitFinal(spool,2);
       SessionExporter.exportBundles(spool,dest,()->{});
       File[] exported=dest.listFiles(File::isDirectory);
       check(exported!=null && exported.length==2,"second failed attempt not exported");
    }
    System.out.println("RC8 startup checkpoints and verified export without projection passed");
 }
}"""
}
with tempfile.TemporaryDirectory() as tmp:
    base = Path(tmp)
    for name, body in stubs.items():
        p = base / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body)
    dipo = src / "wireless/dipo"
    files = [dipo / n for n in ("StartupRecording.java", "SessionRecording.java",
            "SessionLogWriter.java", "SessionLogPolicy.java", "PreSessionEvents.java")]
    files += [src / "sessionexport/SessionExporter.java"]
    subprocess.run(["javac", "--release", "8", "-d", str(base / "classes"),
                    *map(str, files), *[str(base / n) for n in stubs]], check=True)
    subprocess.run(["java", "-cp", str(base / "classes"),
                    "hmgapi.mods.sessionexport.StartupExportTest", str(base)], check=True)
