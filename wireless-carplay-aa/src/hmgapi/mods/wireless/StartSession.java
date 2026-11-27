package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from StartSession.java, CPUL 1.0.0 bundled EV Routing 0.4.3-ioniq6.15.
import android.content.Context;
import hmgapi.hook.ModContext;
import hmgapi.mods.wireless.api.CarPlayIdentification.ThemeIdentity;
import java.io.*;
import java.net.*;
import java.util.*;

/** K5-only runtime facts, obtained from the existing local OEM hotspot and receiver. */
final class StartSession {
    private static final String RECEIVER="/data/data/com.daudio.app.dipo/files/cpc-airplay";
    private static final String HOSTAPD="/data/misc/wifi/hostapd.conf";
    private static final String IAP="/system/lib/libiap2session.so";
    private static Boolean verified;
    private StartSession() {}
    static synchronized boolean verified() {
        if(verified!=null)return verified;
        try {
            String model=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class)
                .invoke(null,"ro.product.device");
            verified="wp_daudioplus_dl322_us".equals(model)
                && "fc7cb675b46a44bd893a1edc054c5e71".equals(ModContext.md5(IAP));
        }catch(Throwable e){verified=false;}
        return verified;
    }
    enum Blocker { NONE, CONTEXT, THEME, FIRMWARE, RECEIVER_IDENTITY, WIFI_SERVICE, WIFI_CONFIGURATION, WIFI_CREDENTIALS, NETWORK_ADDRESS }
    static final class Preparation {
        final byte[] message;
        final Blocker blocker;
        Preparation(byte[] message,Blocker blocker){this.message=message;this.blocker=blocker;}
    }
    private static Preparation blocked(Blocker blocker){return new Preparation(null,blocker);}
    static Preparation prepare(Context context, ThemeIdentity theme) {
        if(context==null)return blocked(Blocker.CONTEXT);
        if(theme==null)return blocked(Blocker.THEME);
        if(!verified())return blocked(Blocker.FIRMWARE);
        String pi=null, deviceId=null, sourceVersion=null;
        for(String line:lines(RECEIVER)){
            if(line.startsWith("pi="))pi=line.substring(3);
            else if(line.startsWith("deviceID="))deviceId=line.substring(9);
            else if(line.startsWith("sourceVersion="))sourceVersion=line.substring(14);
        }
        if(!valid(pi)||!valid(deviceId))return blocked(Blocker.RECEIVER_IDENTITY);
        if(!valid(sourceVersion))sourceVersion=null; // optional; never fabricate a receiver version
        Object config;
        try{
            Object wm=context.getSystemService("wifi");
            if(wm==null)return blocked(Blocker.WIFI_SERVICE);
            config=wm.getClass().getMethod("getWifiApConfiguration").invoke(wm);
        }catch(Throwable e){return blocked(Blocker.WIFI_CONFIGURATION);}
        if(config==null)return blocked(Blocker.WIFI_CONFIGURATION);
        String ssid=field(config,"ConnectivitySSID"),psk=field(config,"ConnectivityPSK");
        if(!valid(ssid))ssid=unquote(field(config,"SSID"));
        if(!valid(psk))psk=unquote(field(config,"preSharedKey"));
        if(!valid(ssid)||!valid(psk))return blocked(Blocker.WIFI_CREDENTIALS);
        List<String> ips=addresses("wlan0");
        // Early preparation can precede address assignment. Refresh supplies addresses when available.
        int channel=channel(config);
        return new Preparation(StartSessionCodec.encode(ssid,psk,channel,ips,-1,7000,deviceId,pi,sourceVersion,
                theme.sdkVersion,theme.assetIdentifier,theme.assetVersion),Blocker.NONE);
    }
    private static boolean valid(String s){return s!=null && !s.isEmpty() && s.indexOf(0)<0;}
    private static String field(Object o,String name){
        try{Object v=o.getClass().getField(name).get(o);return v instanceof String?(String)v:null;}
        catch(Throwable e){return null;}
    }
    private static String unquote(String s){
        return s!=null && s.length()>=2 && s.startsWith("\"") && s.endsWith("\"")?s.substring(1,s.length()-1):s;
    }
    private static int channel(Object config){
        for(String line:lines(HOSTAPD))if(line.startsWith("channel="))try{
            int n=Integer.parseInt(line.substring(8).trim());if(n>0&&n<=255)return n;
        }catch(NumberFormatException ignored){}
        for(java.lang.reflect.Field f:config.getClass().getFields())try{
            if(f.getType()==int.class&&f.getName().toLowerCase(Locale.US).contains("channel")){
                int n=f.getInt(config);if(n>0&&n<=255)return n;
            }
        }catch(Throwable ignored){}
        // Unknown is not a measured channel. The codec omits negative values.
        return -1;
    }
    private static List<String> addresses(String name){
        List<String> out=new ArrayList<String>();
        try{
            NetworkInterface nif=NetworkInterface.getByName(name);if(nif==null)return out;
            String v4=null;
            for(Enumeration<InetAddress> e=nif.getInetAddresses();e.hasMoreElements();){
                InetAddress a=e.nextElement();String s=a.getHostAddress();int pct=s.indexOf('%');
                if(pct>=0)s=s.substring(0,pct);
                if(a instanceof Inet6Address&&a.isLinkLocalAddress())out.add(s);
                else if(a instanceof Inet4Address&&!a.isLoopbackAddress())v4=s;
            }
            if(out.isEmpty()&&v4!=null)out.add(v4);
            Collections.sort(out);
        }catch(Throwable ignored){}
        return out;
    }
    private static List<String> lines(String path){
        List<String> out=new ArrayList<String>();
        File f=new File(path);if(!f.canRead()||f.length()>65536)return out;
        try{
            BufferedReader r=new BufferedReader(new FileReader(f));
            try{for(String l;(l=r.readLine())!=null;)out.add(l);}finally{r.close();}
        }catch(IOException ignored){}
        return out;
    }
}
