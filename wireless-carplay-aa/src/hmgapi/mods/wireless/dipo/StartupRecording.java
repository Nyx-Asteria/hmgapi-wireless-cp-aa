package hmgapi.mods.wireless.dipo;

import android.content.Context;

/** Independent bounded startup segments; no projection callback required for export. */
public final class StartupRecording {
    private final SessionRecording recorder;
    private boolean active;
    public StartupRecording(Context context,String folder) {
        recorder=new SessionRecording(context,folder,false);
        record("WCP 2.2.1-rc.8 independent startup recorder");
        Thread worker=new Thread(new Runnable(){public void run(){
            try {
                for(;;){Thread.sleep(30000);rotate();}
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();rotate();}
        }},"wcp-startup-rotate");
        worker.setDaemon(true);
        worker.start();
    }
    public synchronized void record(String message){ append(message,false); }
    public synchronized void error(String message){ append(message,true); }
    private void append(String message,boolean error){
        try{
            if(!active){recorder.start("WCP 2.2.1-rc.8 pre-projection diagnostic segment");active=true;}
            if(error)recorder.error(message);else recorder.record(message);
        }catch(RuntimeException|LinkageError failure){
            android.util.Log.e("HMG-API","RC8 startup recording failed: "+failure.getClass().getSimpleName());
        }
    }
    public synchronized void rotate(){
        try{if(active)recorder.end("periodic startup checkpoint");}
        catch(RuntimeException|LinkageError failure){
            android.util.Log.e("HMG-API","RC8 startup checkpoint failed: "+failure.getClass().getSimpleName());
        }finally{active=false;}
    }
}
