package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;

public final class ThemeQuarantinePairTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void rejects(File dir,String name)throws IOException{
        try{ThemeQuarantinePair.open(dir,name);}catch(IOException expected){return;}
        throw new AssertionError("invalid quarantine pair accepted");
    }
    static void writeZip(File f,String key,byte[] value)throws IOException{
        ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f));
        try{z.putNextEntry(new ZipEntry(key));z.write(value);z.closeEntry();}finally{z.close();}
    }
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("theme-pair").toFile();
        try{
            ThemeTransferSession.Captured c=ThemeCaptureMetadataTest.capture(dir);
            String name=c.archive.getName();
            rejects(dir,name); // orphan archive
            File meta=ThemeCaptureMetadata.persist(c);
            ThemeQuarantinePair reopened=ThemeQuarantinePair.open(dir,name);
            check(reopened.size==3 && reopened.version==985 && reopened.chunks==2 && reopened.checksum.equals(c.checksum));
            rejects(dir,"../"+name);
            byte[] original=Files.readAllBytes(meta.toPath());
            Files.write(c.archive.toPath(),new byte[]{97,98,100});
            rejects(dir,name);
            Files.write(c.archive.toPath(),new byte[]{97,98,99});
            writeZip(meta,"../certificate.bin",new byte[]{1});rejects(dir,name);
            writeZip(meta,"manifest.properties",new byte[4097]);rejects(dir,name);
            writeZip(meta,"signature.bin",new byte[1024*1024+1]);rejects(dir,name);
            writeZip(meta,"manifest.properties","format=1\nformat=1\n".getBytes("US-ASCII"));rejects(dir,name);
            Files.write(meta.toPath(),original);
            check(ThemeQuarantinePair.open(dir,name).checksum.equals(c.checksum));
            check(meta.delete());rejects(dir,name);
            System.out.println("PASS reopen quarantine pair: integrity, bounds, path and orphan rejection");
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
}
