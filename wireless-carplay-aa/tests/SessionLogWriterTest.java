package hmgapi.mods.wireless.dipo;
import java.io.*;
import static hmgapi.mods.wireless.dipo.SessionLogPolicy.Level.*;
import static hmgapi.mods.wireless.dipo.SessionLogPolicy.Category.*;
public final class SessionLogWriterTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static String read(File f)throws Exception{
        BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"));
        StringBuilder b=new StringBuilder();try{String s;while((s=r.readLine())!=null)b.append(s).append('\n');}finally{r.close();}return b.toString();
    }
    public static void main(String[] args)throws Exception{
        File root=File.createTempFile("wcp-log-test","");check(root.delete());check(root.mkdir());
        try{
            File unfinished=new File(root,"old.open");check(unfinished.mkdir());
            SessionLogWriter.writeText(new File(unfinished,"session.log"),"old data");
            SessionLogWriter w=new SessionLogWriter(root,null,java.util.EnumSet.of(IAP2));
            w.start(7,"firmware=test");
            w.record(7,INFO,CORE,"connected");
            long nativeAt=System.currentTimeMillis()-2000;
            w.recordAt(7,INFO,CORE,"native event",nativeAt);
            w.record(6,INFO,CORE,"wrong generation");
            w.record(7,ERROR,IAP2,"failed");
            w.record(7,INFO,IDENTITY,"[milestone] identity_prepared");
            w.record(7,INFO,IDENTITY,"[milestone] identity_advertised");
            w.record(7,INFO,IDENTITY,"[milestone] transport_ready");
            long observedEnd=System.currentTimeMillis();
            w.record(7,INFO,CORE,"late teardown closed");
            w.finishAt(7,"unknown",3,observedEnd);
            w.shutdown();check(w.await(10000));check(w.failure()==null);
            check(read(new File(root,"old/summary.txt")).contains("recovered unfinished session"));
            File session=null;for(File f:root.listFiles())if(f.getName().contains("session-7_"))session=f;
            check(session!=null&&!session.getName().endsWith(".open"));
            check(read(new File(session,"session.log")).contains("connected"));
            check(read(new File(session,"session.log")).contains(nativeAt+" INFO CORE native event"));
            check(!read(new File(session,"session.log")).contains("wrong generation"));
            check(read(new File(session,"errors.log")).contains("failed"));
            check(read(new File(session,"diagnostics/iap2.log")).contains("failed"));
            check(!new File(session,"diagnostics/identity.log").exists());
            check(read(new File(session,"summary.txt")).contains("identity_prepared="));
            check(read(new File(session,"summary.txt")).contains("policy_suppressed=3"));
            check(read(new File(session,"summary.txt")).contains("end_ms="+observedEnd));
            check(read(new File(session,"session.log")).contains("late teardown closed"));
            String summary=read(new File(session,"summary.txt"));
            check(summary.contains("identity_advertised="));
            check(summary.contains("transport_ready="));
            check(summary.contains("events_identity=3"));
            check(summary.contains("error_count=1"));
            check(summary.contains("first_event_ms="+nativeAt));
            File stress=new File(root,"stress");check(stress.mkdir());
            SessionLogWriter burst=new SessionLogWriter(stress);
            burst.start(100,"first");
            for(int i=0;i<20000;i++)burst.record(100,INFO,CORE,"burst "+i);
            burst.finish(100,"first end",0);
            burst.start(101,"second");
            burst.record(101,INFO,CORE,"second only");
            burst.finish(101,"second end",0);
            burst.shutdown();check(burst.await(20000));check(burst.failure()==null);
            File first=null,second=null;
            for(File f:stress.listFiles()){
                if(f.getName().contains("session-100_"))first=f;
                if(f.getName().contains("session-101_"))second=f;
            }
            check(first!=null&&second!=null);
            check(read(new File(first,"summary.txt")).contains("termination=first end"));
            check(read(new File(second,"summary.txt")).contains("queue_dropped=0"));
            check(read(new File(second,"session.log")).contains("second only"));
            File broken=new File(root,"broken.open"),conflict=new File(root,"broken"),good=new File(root,"good.open");
            check(broken.mkdir()&&conflict.mkdir()&&good.mkdir());
            try{SessionLogWriter.recover(root);throw new AssertionError("conflict ignored");}catch(IOException expected){}
            check(broken.isDirectory());
            check(new File(root,"good/errors.log").isFile());
            File markerDir=new File(root,"marker-test");check(markerDir.mkdir());
            File marker=new File(markerDir,".exported");
            SessionLogWriter.writeText(marker,"");
            check(!SessionLogWriter.verifiedMarker(markerDir));
            SessionLogWriter.writeText(marker,"verif");
            check(!SessionLogWriter.verifiedMarker(markerDir));
            SessionLogWriter.writeText(marker,"verified\n");
            check(SessionLogWriter.verifiedMarker(markerDir));
            File unavailable=new File(root,"unavailable");SessionLogWriter.writeText(unavailable,"not a directory");
            final java.util.List<String> health=java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            SessionLogWriter retry=new SessionLogWriter(unavailable,null,java.util.EnumSet.noneOf(SessionLogPolicy.Category.class),
                new SessionLogWriter.Health(){public void changed(String state){health.add(state);}});
            retry.start(200,"unavailable");
            retry.finish(200,"end",0);
            long deadline=System.currentTimeMillis()+5000;
            while(health.isEmpty()&&System.currentTimeMillis()<deadline)Thread.sleep(20);
            check(!health.isEmpty()&&!health.get(0).equals("ready"));
            check(unavailable.delete()&&unavailable.mkdir());
            retry.start(201,"recovered");
            retry.record(201,INFO,CORE,"recording resumed");
            retry.finish(201,"end",0);
            retry.shutdown();check(retry.await(10000));check(retry.failure()==null);
            check(health.contains("ready"));
            System.out.println("SessionLogWriterTest passed");
        }finally{SessionLogWriter.delete(root);}
    }
}
