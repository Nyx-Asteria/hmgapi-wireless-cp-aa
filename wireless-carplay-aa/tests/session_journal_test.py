"""Compile and exercise the production journal with deterministic Android stubs."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / "src/hmgapi/mods/wireless/dipo/SessionJournal.java"
with tempfile.TemporaryDirectory() as tmp:
    base = Path(tmp)
    files = {
        "android/content/Context.java": """package android.content;
import java.io.File;
public class Context { private final File dir; public Context(File d){dir=d;}
public File getFilesDir(){return dir;} }""",
        "android/os/HandlerThread.java": """package android.os;
public class HandlerThread { public HandlerThread(String n){} public void start(){}
public Object getLooper(){return this;} }""",
        "android/os/Handler.java": """package android.os;
import java.util.ArrayDeque;
public class Handler { static final ArrayDeque<Runnable> queue=new ArrayDeque<>();
public Handler(Object l){} public boolean post(Runnable r){queue.add(r);return true;}
public static int pending(){return queue.size();} public static void drain(){while(!queue.isEmpty())queue.remove().run();} }""",
        "android/os/SystemClock.java": """package android.os;
public class SystemClock { public static long time=100; public static long elapsedRealtime(){return time;} }""",
        "CarPlayLog.java": """package hmgapi.mods.wireless.dipo; public class CarPlayLog { static boolean on=true; }""",
        "JournalTest.java": """package hmgapi.mods.wireless.dipo;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.io.*;
import java.nio.file.*;
public class JournalTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static File find(File d,String suffix){for(File f:d.listFiles())if(f.getName().endsWith(suffix))return f;throw new AssertionError(suffix);}
 static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),"UTF-8");}
 public static void main(String[] args)throws Exception{
  File root=new File(args[0]); File dir=new File(root,"journal");dir.mkdirs();
  Files.write(new File(dir,"session-old.active").toPath(),"old\\n".getBytes("UTF-8"));
  Files.write(new File(dir,"session-old.native-enabled").toPath(),"enabled".getBytes("UTF-8"));
  SessionJournal j=new SessionJournal(new Context(root),"journal");Handler.drain();
  check(!new File(dir,"session-old.native-enabled").exists(),"recovered marker removed");
  check(read(find(dir,"-interrupted.log")).contains("END interrupted"),"restart recovery");
  j.start("ssid=private");j.start("duplicate");SystemClock.time=200;
  j.record("token=secret 11:22:33:44:55:66");SystemClock.time=900;Handler.drain();
  check(find(dir,".native-enabled").isFile(),"native opt-in marker");
  String active=read(find(dir,".active"));
  check(active.contains("\\t200\\t"),"capture time preserved");
  check(!active.contains("private")&&!active.contains("token=secret")&&!active.contains("11:22:33:44:55:66"),"redaction");
  check(!active.contains("duplicate"),"duplicate start");
  for(int i=0;i<300;i++)j.record("event "+i);
  j.end("done");Handler.drain();
  File complete=null;for(File f:dir.listFiles())if(f.getName().endsWith(".log")&&!f.getName().endsWith("-interrupted.log"))complete=f;
  check(complete!=null&&read(complete).contains("END done"),"finalized");
  check(read(complete).contains("dropped queued events="),"bounded queue");
  for(File f:dir.listFiles())check(!f.getName().endsWith(".native-enabled"),"completed marker removed");
  j.beginAttempt("attempt one");j.record("first-only");
  j.beginAttempt("attempt two");j.record("second-only");
  j.start("startup continuation");j.end("second finished");Handler.drain();
  boolean first=false,second=false;
  for(File f:dir.listFiles())if(f.getName().endsWith(".log")){
   String content=read(f);
   if(content.contains("START attempt one")){
    check(content.contains("first-only")&&!content.contains("second-only")&&content.contains("superseded by session creation callback"),"first attempt isolation");first=true;
   }
   if(content.contains("START attempt two")){
    check(content.contains("second-only")&&!content.contains("first-only")&&!content.contains("startup continuation")&&content.contains("END second finished"),"second attempt isolation");second=true;
   }
  }
  check(first&&second,"both attempts finalized");
  j.beginAttempt("drop generation A");
  for(int i=0;i<256;i++)j.record("old-event "+i);
  j.beginAttempt("drop generation B");
  for(int i=0;i<7;i++)j.record("new-dropped "+i);
  j.end("drop test end");Handler.drain();
  boolean generationA=false,generationB=false;
  for(File f:dir.listFiles())if(f.getName().endsWith(".log")){
   String content=read(f);
   if(content.contains("START drop generation A")){
    check(!content.contains("dropped queued events="),"new drops must not appear in old generation");generationA=true;
   }
   if(content.contains("START drop generation B")){
    check(content.contains("dropped queued events=7"),"new generation owns its drops");generationB=true;
   }
  }
  check(generationA&&generationB,"both drop generations finalized");
  j.start("failure");Handler.drain();
  File blocked=find(dir,".active");check(blocked.delete()&&blocked.mkdir(),"inject unwritable path");
  j.record("cannot write");j.end("failed");Handler.drain();
  check(blocked.isDirectory(),"failed sync must not publish completed file");
  check(!new File(dir,blocked.getName().replace(".active",".native-enabled")).exists(),"failed finalization stops native capture");
  check(blocked.delete(),"cleanup");
  File orphan=new File(dir,"session-orphan.active");
  File orphanMarker=new File(dir,"session-orphan.native-enabled");
  Files.write(orphan.toPath(),"orphan".getBytes("UTF-8"));
  Files.write(orphanMarker.toPath(),"enabled".getBytes("UTF-8"));
  j.start("retry recovery");Handler.drain();
  check(!orphan.exists()&&new File(dir,"session-orphan-interrupted.log").isFile(),"retry orphan recovery without restart");
  check(!orphanMarker.exists(),"orphan native marker removed after finalization");
  j.end("done");Handler.drain();
  File failDir=new File(root,"retention");check(failDir.mkdir(),"retention fixture");
  for(int k=0;k<15;k++){
   String name=String.format("session-%03d",k);
   File src=new File(failDir,name+".active");
   Files.write(src.toPath(),"retained".getBytes("UTF-8"));
   check(new File(failDir,name+"-interrupted.log").mkdir(),"block rename");
   Files.write(new File(failDir,name+".native-enabled").toPath(),"enabled".getBytes("UTF-8"));
  }
  SessionJournal retained=new SessionJournal(new Context(root),"retention");Handler.drain();
  int count=0;
  for(File f:failDir.listFiles())if(f.isFile()&&f.getName().endsWith(".active"))count++;
  check(count==12,"orphan retention capped");
  check(!new File(failDir,"session-000.active").exists()&&new File(failDir,"session-014.active").isFile(),"oldest orphan pruned first");
  for(File f:failDir.listFiles())check(!f.getName().endsWith(".native-enabled"),"orphan native marker retired even when rename blocked");
  retained.start("live after failure");Handler.drain();
  count=0;for(File f:failDir.listFiles())if(f.isFile()&&f.getName().endsWith(".active"))count++;
  check(count==13,"live session has independent slot");
  retained.end("done");Handler.drain();
  File burstDir=new File(root,"burst");
  SessionJournal burst=new SessionJournal(new Context(root),"burst");Handler.drain();
  for(int k=0;k<1000;k++){burst.beginAttempt("burst "+k);burst.end("end");}
  check(Handler.pending()<=32,"lifecycle backlog bounded");
  Handler.drain();
  burst.beginAttempt("after backlog");burst.record("fresh event");burst.end("done");Handler.drain();
  boolean recovered=false;
  for(File f:burstDir.listFiles())if(f.getName().endsWith(".log")){
   String content=read(f);
   if(content.contains("START after backlog")){
    check(content.contains("fresh event")&&content.contains("END done")&&content.contains("skipped sessions due to lifecycle backlog="),"backlog recovery and summary");recovered=true;
   }
  }
  check(recovered,"new session accepted after backlog");
  System.out.println("Session journal recovery, redaction, timestamps, queue and write failure passed");
 }
}""",
    }
    for name, body in files.items():
        f = base / name
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(body)
    subprocess.run(["javac", "--release", "8", "-d", str(base / "classes"), str(source),
                    *[str(base / name) for name in files]], check=True)
    subprocess.run(["java", "-cp", str(base / "classes"),
                    "hmgapi.mods.wireless.dipo.JournalTest", str(base)], check=True)
