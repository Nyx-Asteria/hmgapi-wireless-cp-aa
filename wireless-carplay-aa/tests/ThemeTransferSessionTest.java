package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

public final class ThemeTransferSessionTest {
    private static final String ABC="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static Map<String,Object> header(){
        Map<String,Object> m=new HashMap<String,Object>();
        m.put("messageType",1);m.put("payloadType",1);m.put("sessionID","test");
        m.put("version",985L);m.put("chunkCount",2);m.put("certificate",new byte[]{5});
        return m;
    }
    static Map<String,Object> chunk(int index,byte[] data){
        Map<String,Object> m=new HashMap<String,Object>();
        m.put("messageType",2);m.put("sessionID","test");m.put("chunkIndex",index);m.put("data",data);
        if(index==1)m.put("checksum",ABC);
        return m;
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void reject(ThemeTransferSession t,Map<String,Object> m)throws IOException{
        try{t.receive(m);}catch(IOException expected){return;}
        throw new AssertionError("accepted invalid message");
    }
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("theme-dictionary").toFile();
        try{
            ThemeTransferSession t=new ThemeTransferSession(dir,3);
            Map<String,Object> h=header();
            check(t.receive(h)==null);
            ((byte[])h.get("certificate"))[0]=9;
            check(t.receive(chunk(0,new byte[]{97}))==null);
            ThemeTransferSession.Captured c=t.receive(chunk(1,new byte[]{98,99}));
            check(c.header.version==985 && c.header.certificate()[0]==5);
            c.header.certificate()[0]=1;
            check(c.header.certificate()[0]==5);
            check(Arrays.equals(Files.readAllBytes(c.archive.toPath()),new byte[]{97,98,99}));
            reject(t,header());check(c.archive.delete());
            t=new ThemeTransferSession(dir,3);reject(t,chunk(0,new byte[]{97}));
            t=new ThemeTransferSession(dir,3);h=header();h.put("payloadType",2);reject(t,h);
            t=new ThemeTransferSession(dir,3);h=header();h.put("version",985.0);reject(t,h);
            t=new ThemeTransferSession(dir,3);h=header();h.put("chunkCount",0);reject(t,h);
            t=new ThemeTransferSession(dir,3);h=header();h.put("signature","bad");reject(t,h);
            t=new ThemeTransferSession(dir,3);t.receive(header());reject(t,header());
            t=new ThemeTransferSession(dir,3);t.receive(header());
            Map<String,Object> bad=chunk(0,new byte[]{97});bad.put("sessionID","other");reject(t,bad);
            t=new ThemeTransferSession(dir,3);t.receive(header());
            bad=chunk(0,new byte[]{97});bad.put("checksum",null);reject(t,bad);
            t=new ThemeTransferSession(dir,3);t.receive(header());t.receive(chunk(0,new byte[]{97}));t.close();
            check(dir.list().length==0);
            System.out.println("PASS decoded theme session: schema, isolation, metadata, quarantine");
        }finally{for(File file:dir.listFiles())file.delete();dir.delete();}
    }
}
