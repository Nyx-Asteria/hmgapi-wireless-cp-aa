package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipFile;

public final class ThemeCaptureMetadataTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static ThemeTransferSession.Captured capture(File dir)throws IOException{
        ThemeTransferSession s=new ThemeTransferSession(dir,3);
        try{
            Map<String,Object> h=ThemeTransferSessionTest.header();
            h.put("signature",new byte[]{8,9});h.put("additions",new byte[]{});
            s.receive(h);s.receive(ThemeTransferSessionTest.chunk(0,new byte[]{97}));
            return s.receive(ThemeTransferSessionTest.chunk(1,new byte[]{98,99}));
        }finally{s.close();}
    }
    static byte[] read(ZipFile z,String name)throws IOException{
        InputStream in=z.getInputStream(z.getEntry(name));
        try{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[128];int n;
            while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toByteArray();}
        finally{in.close();}
    }
    static void reject(ThemeTransferSession.Captured c)throws IOException{
        try{ThemeCaptureMetadata.persist(c);}catch(IOException expected){return;}
        throw new AssertionError("metadata unexpectedly published");
    }
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("theme-meta-test").toFile();
        try{
            ThemeTransferSession.Captured c=capture(dir);
            File side=ThemeCaptureMetadata.persist(c);
            ZipFile z=new ZipFile(side);
            try{
                Properties p=new Properties();
                p.load(new java.io.ByteArrayInputStream(read(z,"manifest.properties")));
                check(p.getProperty("version").equals("985") && p.getProperty("size").equals("3"));
                check(p.getProperty("sha256").equals(c.checksum));
                check(p.getProperty("assetIdentifier").equals("unknown"));
                check(p.getProperty("authenticity").equals("unverified"));
                check(p.getProperty("installation").equals("not-performed"));
                check(p.getProperty("sessionID")==null && z.size()==4);
                check(read(z,"certificate.bin")[0]==5 && read(z,"signature.bin")[1]==9);
                check(read(z,"additions.bin").length==0);
            }finally{z.close();}
            reject(c);check(side.isFile());
            ThemeTransferSession.Captured tampered=capture(dir);
            Files.write(tampered.archive.toPath(),new byte[]{97,98,100});
            reject(tampered);
            check(!new File(dir,tampered.archive.getName()+".metadata.zip").exists());
            for(File f:dir.listFiles())check(!f.getName().endsWith(".part"));
            System.out.println("PASS private quarantine metadata, digest recheck, no overwrite");
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
}
