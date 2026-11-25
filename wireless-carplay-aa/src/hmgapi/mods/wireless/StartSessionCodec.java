package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from StartSession.java in the CPUL 1.0.0 bundled EV Routing 0.4.3-ioniq6.15 source.
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Pure encoder for the LIVI-compatible iAP2 CarPlayStartSession control message. */
final class StartSessionCodec {
    private StartSessionCodec() {}
    static byte[] encode(String ssid,String passphrase,int channel,List<String> ips,int security,int port,
                         String deviceId,String publicKey,String sourceVersion,String sdkVersion,String assetId,long assetVersion) {
        ByteArrayOutputStream wifi=new ByteArrayOutputStream();
        if(ssid!=null)param(wifi,0,str(ssid));
        if(passphrase!=null)param(wifi,1,str(passphrase));
        if(channel>=0)param(wifi,2,new byte[]{(byte)channel});
        ByteArrayOutputStream list=new ByteArrayOutputStream();
        for(String ip:ips){byte[] b=str(ip);list.write(b,0,b.length);}
        param(wifi,3,list.toByteArray());
        if(security>=0)param(wifi,4,new byte[]{(byte)security});
        ByteArrayOutputStream p=new ByteArrayOutputStream();
        param(p,1,wifi.toByteArray());
        if(port>=0)param(p,2,u32(port));
        if(deviceId!=null)param(p,3,str(deviceId));
        if(publicKey!=null)param(p,4,str(publicKey));
        if(sourceVersion!=null)param(p,5,str(sourceVersion));
        if(sdkVersion!=null)param(p,6,str(sdkVersion));
        if(assetId!=null){
            ByteArrayOutputStream asset=new ByteArrayOutputStream();
            param(asset,0,str(assetId));param(asset,1,u32(assetVersion));param(p,7,asset.toByteArray());
        }
        byte[] body=p.toByteArray();int n=body.length+6;
        if(n>512)throw new IllegalArgumentException("session-start message exceeds native capacity");
        byte[] out=new byte[n];
        out[0]=0x40;out[1]=0x40;out[2]=(byte)(n>>8);out[3]=(byte)n;out[4]=0x43;out[5]=0x01;
        System.arraycopy(body,0,out,6,body.length);
        return out;
    }
    private static void param(ByteArrayOutputStream out,int id,byte[] payload){
        int n=payload.length+4;out.write(n>>8);out.write(n);out.write(id>>8);out.write(id);out.write(payload,0,payload.length);
    }
    private static byte[] str(String s){
        if(s.indexOf(0)>=0)throw new IllegalArgumentException("embedded NUL");
        try{byte[] b=s.getBytes("UTF-8"),z=new byte[b.length+1];System.arraycopy(b,0,z,0,b.length);return z;}
        catch(java.io.UnsupportedEncodingException e){throw new AssertionError(e);}
    }
    private static byte[] u32(long v){return new byte[]{(byte)(v>>24),(byte)(v>>16),(byte)(v>>8),(byte)v};}
}
