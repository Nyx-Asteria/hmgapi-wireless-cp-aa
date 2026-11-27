package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.util.Locale;

/** Directory recording adapter; root service alone publishes to USB. */
final class SessionRecording {
    private final SessionLogWriter writer;
    private final SessionLogPolicy policy;
    private final PreSessionEvents pre = new PreSessionEvents();
    private long generation, previousEnd;
    private boolean active;
    private final boolean focused;

    SessionRecording(Context context, String folder, boolean focused) {
        this.focused=focused;
        policy=new SessionLogPolicy(SessionLogPolicy.diagnosticCategories(true,false));
        writer=new SessionLogWriter(new File(context.getFilesDir(),folder),null,
                SessionLogPolicy.diagnosticCategories(true,false),new SessionLogWriter.Health(){
                    public void changed(String state){
                        // Independent of the session spool and its policy.
                        String message="Session recorder "+folder+": "+SessionLogPolicy.sanitize(state);
                        if("ready".equals(state))android.util.Log.i("HMG-API",message);
                        else android.util.Log.e("HMG-API",message);
                    }
                });
    }
    synchronized void beginAttempt(String detail) {
        end("superseded by next attempt");
        start(detail);
    }
    synchronized void start(String detail) {
        if(active)return;
        active=true;generation++;
        policy.reset();
        long now=System.currentTimeMillis();
        writer.start(generation,detail);
        for(PreSessionEvents.Event e:pre.drain(now,previousEnd))
            writer.recordAt(generation,SessionLogPolicy.Level.INFO,SessionLogPolicy.Category.CORE,"pre-session "+e.message,e.at);
    }
    synchronized void record(String message) { record(SessionLogPolicy.Level.INFO,message); }
    synchronized void error(String message) { record(SessionLogPolicy.Level.ERROR,message); }
    private void record(SessionLogPolicy.Level level,String message) {
        SessionLogPolicy.Category category=category(message);
        if(!active){
            if(!focused){
                long now=System.currentTimeMillis();
                pre.add(message,now,now,previousEnd);
            }
            return;
        }
        String safe=policy.accept(level,category,message,SystemClock.elapsedRealtime());
        writer.record(generation,level,category,safe);
    }
    synchronized void end(String reason) {
        if(!active)return;
        active=false;previousEnd=System.currentTimeMillis();
        writer.finishAt(generation,reason,policy.suppressed(),previousEnd);
    }
    private SessionLogPolicy.Category category(String message) {
        if(focused)return SessionLogPolicy.Category.IAP2;
        String text=message==null?"":message.toLowerCase(Locale.US);
        if(text.contains("now playing")||text.contains("nowplaying"))return SessionLogPolicy.Category.NOW_PLAYING;
        if(text.contains("bluetooth")||text.contains("btcm"))return SessionLogPolicy.Category.BLUETOOTH;
        if(text.contains("wifi")||text.contains("hotspot")||text.contains("wi-fi"))return SessionLogPolicy.Category.WIFI;
        if(text.contains("identity")||text.contains("identification")||text.contains("theme"))return SessionLogPolicy.Category.IDENTITY;
        if(text.contains("audio")||text.contains("opus")||text.contains("aac"))return SessionLogPolicy.Category.AUDIO;
        if(text.contains("latency")||text.contains("elapsed_ms"))return SessionLogPolicy.Category.LATENCY;
        if(text.contains("iap2"))return SessionLogPolicy.Category.IAP2;
        if(text.contains("transport")||text.contains("bridge")||text.contains("reattach"))return SessionLogPolicy.Category.TRANSPORT;
        if(text.contains("native"))return SessionLogPolicy.Category.NATIVE;
        return SessionLogPolicy.Category.CORE;
    }
}
