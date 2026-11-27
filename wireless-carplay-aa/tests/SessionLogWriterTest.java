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
            w.record(7,INFO,IDENTITY,"[theme-sync] negotiated traffic panels=[Cluster_Display]");
            w.record(7,INFO,IDENTITY,"[projection-theme] offered altScreen=1");
            w.record(7,INFO,IDENTITY,"[listener] cluster ready port=7132 elapsed_ms=1");
            w.record(7,INFO,CLIMATE,"[seat-command] #1 driver BCM matched requested=8 actual=8");
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
            check(read(new File(session,"summary.txt")).contains("theme_panels="));
            check(read(new File(session,"summary.txt")).contains("policy_suppressed=3"));
            check(read(new File(session,"summary.txt")).contains("end_ms="+observedEnd));
            check(read(new File(session,"session.log")).contains("late teardown closed"));
            String summary=read(new File(session,"summary.txt"));
            check(summary.contains("setup_offered="));
            check(summary.contains("cluster_listener_ready="));
            check(summary.contains("last_seat_bcm_result="));
            check(summary.contains("events_themes=3"));
            check(summary.contains("error_count=1"));
            check(summary.contains("first_event_ms="+nativeAt));
            System.out.println("SessionLogWriterTest passed");
        }finally{SessionLogWriter.delete(root);}
    }
}
