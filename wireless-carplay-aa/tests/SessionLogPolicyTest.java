package hmgapi.mods.wireless.dipo;

import java.util.EnumSet;
import static hmgapi.mods.wireless.dipo.SessionLogPolicy.Level.*;
import static hmgapi.mods.wireless.dipo.SessionLogPolicy.Category.*;

public final class SessionLogPolicyTest {
    private static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        SessionLogPolicy p = new SessionLogPolicy(EnumSet.of(BLUETOOTH, PACKET_METADATA));
        check(p.accept(INFO, CORE, "connected", 0) != null);
        check(p.accept(INFO, CORE, "connected", 1) == null);
        check(p.accept(INFO, CORE, "connected", 10000) != null);
        check(p.accept(DEBUG, BLUETOOTH, "feedback", 0) != null);
        check(p.accept(DEBUG, WIFI, "getter", 0) == null);
        check(p.accept(TRACE, PROJECTION_METADATA, "stream metadata", 0) == null);
        check(p.accept(TRACE, PACKET_METADATA, SessionLogPolicy.packet("VS0", 1, 2, 100), 0) != null);
        check(p.accept(ERROR, WIFI, "failed", 0) != null);
        check(p.accept(ERROR, WIFI, "failed", 1) != null);
        check(p.accept(WARN, WIFI, "timeout", 0) != null);
        check(p.accept(WARN, WIFI, "timeout", 1) != null);
        check(SessionLogPolicy.sanitize("session key: ABC").equals("[sensitive message withheld]"));
        check(SessionLogPolicy.sanitize("https://example.com/private").equals("[sensitive message withheld]"));
        check(SessionLogPolicy.sanitize("one\ntwo").equals("one two"));
        check(SessionLogPolicy.sanitize("peer aa:bb:cc:dd:ee:ff").equals("peer [address withheld]"));
        check(SessionLogPolicy.sanitize("peer 192.168.1.10:7000").equals("peer [address withheld]:7000"));
        check(SessionLogPolicy.sanitize("peer [fe80::1234%wlan0]:7000").equals("peer [[address withheld]]:7000"));
        check(SessionLogPolicy.sanitize("peer 2001:db8:0:1:2:3:4:5").equals("peer [address withheld]"));
        check(SessionLogPolicy.sanitize("at 12:34:56").equals("at 12:34:56"));
        check(p.suppressed() == 3);
        p.reset();
        check(p.suppressed() == 0);
        check(p.accept(INFO, CORE, "connected", 1) != null);
        for (int i = 0; i < 300; i++) p.accept(INFO, CORE, "item " + i, 2);
        check(p.accept(INFO, CORE, "connected", 3) != null);
        check(SessionLogPolicy.diagnosticCategories(false,false).isEmpty());
        check(SessionLogPolicy.diagnosticCategories(true,false).equals(EnumSet.of(BLUETOOTH,WIFI,IAP2,AUDIO,IDENTITY,TRANSPORT,NOW_PLAYING,NATIVE,LATENCY)));
        check(SessionLogPolicy.diagnosticCategories(false,true).equals(EnumSet.of(PACKET_METADATA,PROJECTION_METADATA)));
        check(SessionLogPolicy.diagnosticCategories(true,true).size()==11);
        SessionLogPolicy detail=new SessionLogPolicy(SessionLogPolicy.diagnosticCategories(true,false));
        check(detail.enabled(DEBUG,IDENTITY) && detail.enabled(DEBUG,TRANSPORT));
        check(!detail.enabled(TRACE,PACKET_METADATA) && !detail.enabled(TRACE,PROJECTION_METADATA));
        check(detail.enabled(WARN,PROJECTION_METADATA));
        check(SessionLogPolicy.sanitize("ssid=private").equals("[sensitive message withheld]"));
        check(SessionLogPolicy.sanitize("passphrase=private").equals("[sensitive message withheld]"));
        PreSessionEvents pre=new PreSessionEvents();
        pre.add("password=private",100,100,0);
        pre.add("stale",1,20000,0);
        check(pre.drain(101,0).get(0).message.equals("[sensitive message withheld]"));
        check(pre.drain(101,0).isEmpty());
        for(int i=0;i<100;i++) pre.add("event "+i,100+i,200,0);
        check(pre.drain(200,0).size()==PreSessionEvents.LIMIT);
        System.out.println("SessionLogPolicyTest passed");
    }
}
