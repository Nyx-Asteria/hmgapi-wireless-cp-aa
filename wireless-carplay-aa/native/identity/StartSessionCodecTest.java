package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
// Vectors from the bundled EV Routing StartSessionTest.java (LIVI codec fixture).
import java.util.Arrays;
import hmgapi.mods.wireless.api.CarPlayIdentification.ThemeIdentity;
public final class StartSessionCodecTest {
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    public static void main(String[] args){
        byte[] plain=StartSessionCodec.encode("LIVI","secret123",0x24,Arrays.asList("192.168.2.1"),3,49152,"dev-1","aabbcc","1.0",null,null,0);
        check(hex(plain).equals("40400060430100350001000900004c49564900000e0001736563726574313233000005000224001000033139322e3136382e322e31000005000403000800020000c000000a00036465762d3100000b00046161626263630000080005312e3000"),"LIVI wireless vector");
        ThemeIdentity theme=new ThemeIdentity("44C9FA31EC",985,"19.0");
        byte[] themed=StartSessionCodec.encode("S","p",-1,Arrays.asList("fe80::1","192.168.0.1"),-1,7000,"02:00:00:00:00:01","0001","950.7.1",theme.sdkVersion,theme.assetIdentifier,theme.assetVersion);
        String h=hex(themed);
        check(((themed[2]&255)<<8|(themed[3]&255))==themed.length&&h.substring(8,12).equals("4301"),"header");
        check(h.endsWith("001b0007000f0000343443394641333145430000080001000003d9"),"theme parameter");
        check(h.contains("0009000631392e3000"),"SDK parameter");
        IdentityRegistry r=new IdentityRegistry();
        r.registerTheme("fixture",theme);
        check(!r.themeSessionStart(),"old caller identification only");
        r.registerTheme("fixture",theme,true);
        check(r.themeSessionStart(),"session-start contribution");
        r.removeTheme("other");
        check(r.themeSessionStart(),"foreign withdrawal cannot clear owner");
        r.removeTheme("fixture");
        check(!r.themeSessionStart()&&r.theme()==null,"owner withdrawal");
        System.out.println("PASS session-start wire vectors and ownership");
    }
}
