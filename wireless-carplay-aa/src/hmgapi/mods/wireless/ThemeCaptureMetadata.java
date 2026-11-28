package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Private quarantine metadata sidecar, deliberately not an installer or USB exporter.
 * Local format, not Apple protocol. Caller owns an app-private directory and must
 * serialize capture/export/deletion; no peer-supplied path or session ID is persisted.
 * A checksum match proves consistency, not authenticity. No live caller yet.
 */
final class ThemeCaptureMetadata {
    private ThemeCaptureMetadata(){}
    static synchronized File persist(ThemeTransferSession.Captured c) throws IOException {
        if(c==null || c.archive==null || !c.archive.isFile() || c.size<=0
                || c.size>ThemeArchiveCapture.MAX_BYTES || c.checksum==null)
            throw new IOException("invalid completed capture");
        File archive=c.archive;
        String name=archive.getName();
        if(!name.startsWith("theme-") || !name.endsWith(".part.quarantine"))
            throw new IOException("not a quarantine filename");
        verify(c);
        File parent=archive.getParentFile();
        File target=new File(parent,name+".metadata.zip");
        if(target.exists())throw new IOException("metadata already published");
        File temporary=File.createTempFile("theme-meta-",".part",parent);
        boolean published=false;
        try {
            FileOutputStream raw=new FileOutputStream(temporary);
            try {
                ZipOutputStream zip=new ZipOutputStream(raw);
                try {
                    byte[] cert=c.header.certificate(), sig=c.header.signature(), additions=c.header.additions();
                    String manifest="format=1\narchive="+name+"\nversion="+c.header.version
                        +"\nsize="+c.size+"\nsha256="+c.checksum+"\nchunkCount="+c.header.chunkCount
                        +"\nassetIdentifier=unknown\nintegrity=checksum-matched\nauthenticity=unverified"
                        +"\ninstallation=not-performed\ncertificatePresent="+(cert!=null)
                        +"\nsignaturePresent="+(sig!=null)+"\nadditionsPresent="+(additions!=null)+"\n";
                    entry(zip,"manifest.properties",manifest.getBytes("US-ASCII"));
                    if(cert!=null)entry(zip,"certificate.bin",cert);
                    if(sig!=null)entry(zip,"signature.bin",sig);
                    if(additions!=null)entry(zip,"additions.bin",additions);
                    zip.finish();zip.flush();raw.getFD().sync();
                }finally{zip.close();}
            }finally{raw.close();}
            if(target.exists() || !temporary.renameTo(target))throw new IOException("metadata publication failed");
            published=true;
            return target;
        }finally{
            if(!published && temporary.exists() && !temporary.delete())
                throw new IOException("metadata staging cleanup failed");
        }
    }
    private static void entry(ZipOutputStream zip,String name,byte[] bytes)throws IOException{
        zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
    }
    private static void verify(ThemeTransferSession.Captured c)throws IOException{
        MessageDigest md;
        try{md=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new IOException("SHA-256 unavailable",e);}
        FileInputStream in=new FileInputStream(c.archive);
        long size=0;byte[] buf=new byte[32768];
        try {
            int n;
            while((n=in.read(buf))!=-1){
                size+=n;
                if(size>c.size)throw new IOException("archive size changed");
                md.update(buf,0,n);
            }
        }finally{in.close();}
        StringBuilder hex=new StringBuilder(64);
        for(byte v:md.digest()){hex.append(Character.forDigit((v>>>4)&15,16));hex.append(Character.forDigit(v&15,16));}
        if(size!=c.size || !hex.toString().equals(c.checksum))throw new IOException("archive changed after capture");
    }
}
