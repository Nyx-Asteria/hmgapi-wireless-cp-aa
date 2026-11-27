package hmgapi.mods.wireless;
import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;

public final class ThemeOpackTest {
    static byte[] hex(String s){
        s=s.replace(" ","");
        byte[] b=new byte[s.length()/2];
        for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return b;
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void bad(String s)throws Exception{
        try{ThemeOpack.decode(hex(s));}catch(IOException expected){return;}
        throw new AssertionError("accepted invalid OPACK "+s);
    }
    public static void main(String[] args)throws Exception{
        Map<String,Object> m=ThemeOpack.decode(hex("e2 4161 09 4162 a0"));
        check(m.get("a").equals(1L) && m.get("b").equals("a"));
        m=ThemeOpack.decode(hex("ef 4161 310903 4162 73616263 03"));
        check(m.get("a").equals(777L));
        check(java.util.Arrays.equals((byte[])m.get("b"),new byte[]{97,98,99}));
        bad("e2 4161 09 a0 0a");bad("e1 4161 a1");bad("e1 41ff 09");
        bad("e1 4161 94 ffffffff");bad("ef 4161 09");bad("e0 03");
        bad("e1 4161 d0");bad("e1 4161 73 6162");bad("d0");
        // Hand-encoded synthetic header and one-chunk archive; not phone capture.
        File dir=Files.createTempDirectory("theme-opack-test").toFile();
        ThemeTransferSession session=new ThemeTransferSession(dir,3);
        try{
            String header="e5 4b6d65737361676554797065 09 4b7061796c6f616454797065 09 4973657373696f6e4944 4173 4776657273696f6e 31d903 4a6368756e6b436f756e74 09";
            check(session.receive(ThemeOpack.decode(hex(header)))==null);
            String chunk="e5 4b6d65737361676554797065 0a 4973657373696f6e4944 4173 4a6368756e6b496e646578 08 4464617461 73616263 48636865636b73756d 6140";
            byte[] prefix=hex(chunk),sum="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".getBytes("US-ASCII");
            byte[] frame=java.util.Arrays.copyOf(prefix,prefix.length+sum.length);
            System.arraycopy(sum,0,frame,prefix.length,sum.length);
            ThemeTransferSession.Captured c=session.receive(ThemeOpack.decode(frame));
            check(c.header.version==985 && java.util.Arrays.equals(Files.readAllBytes(c.archive.toPath()),new byte[]{97,98,99}));
            check(c.archive.delete());
        }finally{session.close();for(File f:dir.listFiles())f.delete();dir.delete();}
        System.out.println("PASS synthetic OPACK dictionary quarantine and malformed inputs");
    }
}
