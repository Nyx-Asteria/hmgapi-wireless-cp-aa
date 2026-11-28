package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.*;

/**
 * Reopen a private quarantine pair without the original in-memory session.
 * Local format only. Never extracts, installs, or establishes authenticity.
 * Caller must serialize capture/export/deletion in its app-private directory.
 */
final class ThemeQuarantinePair {
    private static final long MAX_SIDECAR=4L*1024*1024;
    private static final int MAX_BLOB=1024*1024, MAX_MANIFEST=4096;
    final File archive, metadata;
    final long size, version;
    final int chunks;
    final String checksum;
    private ThemeQuarantinePair(File archive,File metadata,long size,long version,int chunks,String checksum){
        this.archive=archive;this.metadata=metadata;this.size=size;this.version=version;this.chunks=chunks;this.checksum=checksum;
    }

    static ThemeQuarantinePair open(File directory,String name)throws IOException{
        if(directory==null || !directory.isDirectory() || name==null ||
                !name.matches("theme-[A-Za-z0-9-]{1,100}\\.part\\.quarantine"))
            throw new IOException("invalid quarantine pair name");
        File root=directory.getCanonicalFile();
        File archive=child(root,name), meta=child(root,name+".metadata.zip");
        if(!archive.isFile() || !meta.isFile() || meta.length()<=0 || meta.length()>MAX_SIDECAR)
            throw new IOException("missing or oversized quarantine pair");
        Map<String,byte[]> entries=new HashMap<String,byte[]>();
        ZipFile zip=new ZipFile(meta);
        try{
            if(zip.size()<1 || zip.size()>4)throw new IOException("metadata entry count");
            Enumeration<? extends ZipEntry> it=zip.entries();
            while(it.hasMoreElements()){
                ZipEntry e=it.nextElement();String n=e.getName();
                int max="manifest.properties".equals(n)?MAX_MANIFEST:MAX_BLOB;
                if(!("manifest.properties".equals(n)||"certificate.bin".equals(n)||"signature.bin".equals(n)||"additions.bin".equals(n))
                        || e.isDirectory() || entries.containsKey(n) || e.getSize()<0 || e.getSize()>max)
                    throw new IOException("invalid metadata entry");
                InputStream in=zip.getInputStream(e);
                try{
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    CRC32 crc=new CRC32();
                    byte[] buf=new byte[8192];int count;
                    while((count=in.read(buf))!=-1){
                        if(count>max-out.size())throw new IOException("metadata expanded beyond limit");
                        out.write(buf,0,count);crc.update(buf,0,count);
                    }
                    if(out.size()!=e.getSize() || crc.getValue()!=e.getCrc())throw new IOException("metadata entry integrity");
                    entries.put(n,out.toByteArray());
                }finally{in.close();}
            }
        }finally{zip.close();}
        byte[] raw=entries.get("manifest.properties");
        if(raw==null)throw new IOException("missing manifest");
        for(byte v:raw)if(v!=10 && (v<32 || v>126))throw new IOException("invalid manifest byte");
        String[] lines=new String(raw,"US-ASCII").split("\n",-1);
        Map<String,String> m=new HashMap<String,String>();
        for(int i=0;i<lines.length;i++){
            String line=lines[i];
            if(i==lines.length-1 && line.isEmpty())break;
            int eq=line.indexOf('=');
            if(eq<=0 || m.put(line.substring(0,eq),line.substring(eq+1))!=null)
                throw new IOException("duplicate or malformed manifest field");
        }
        if(m.size()!=13)throw new IOException("unexpected manifest fields");
        expect(m,"format","1");expect(m,"archive",name);
        expect(m,"assetIdentifier","unknown");expect(m,"integrity","checksum-matched");
        expect(m,"authenticity","unverified");expect(m,"installation","not-performed");
        expect(m,"certificatePresent",Boolean.toString(entries.containsKey("certificate.bin")));
        expect(m,"signaturePresent",Boolean.toString(entries.containsKey("signature.bin")));
        expect(m,"additionsPresent",Boolean.toString(entries.containsKey("additions.bin")));
        long size=number(m,"size",1,ThemeArchiveCapture.MAX_BYTES);
        long version=number(m,"version",0,0xffffffffL);
        int chunks=(int)number(m,"chunkCount",1,ThemeArchiveCapture.MAX_CHUNKS);
        String sum=m.get("sha256");
        if(sum==null || !sum.matches("[0-9a-f]{64}"))throw new IOException("invalid digest");
        if(archive.length()!=size || !sum.equals(digest(archive,size)))throw new IOException("archive integrity mismatch");
        return new ThemeQuarantinePair(archive,meta,size,version,chunks,sum);
    }
    private static File child(File root,String n)throws IOException{
        File f=new File(root,n);
        if(!f.equals(f.getCanonicalFile()))throw new IOException("unsafe quarantine path");
        return f;
    }
    private static void expect(Map<String,String> m,String k,String expected)throws IOException{
        if(!expected.equals(m.get(k)))throw new IOException("invalid manifest "+k);
    }
    private static long number(Map<String,String> m,String k,long min,long max)throws IOException{
        String v=m.get(k);
        if(v==null || !v.matches("0|[1-9][0-9]{0,19}"))throw new IOException("invalid manifest number");
        try{
            long n=Long.parseLong(v);
            if(n<min || n>max)throw new IOException("manifest numeric bounds");
            return n;
        }catch(NumberFormatException e){throw new IOException("manifest numeric overflow",e);}
    }
    private static String digest(File file,long expected)throws IOException{
        MessageDigest md;
        try{md=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new IOException("SHA-256 unavailable",e);}
        InputStream in=new FileInputStream(file);
        long total=0;
        try{
            byte[] buf=new byte[32768];int n;
            while((n=in.read(buf))!=-1){total+=n;if(total>expected)throw new IOException("archive grew");md.update(buf,0,n);}
        }finally{in.close();}
        if(total!=expected)throw new IOException("archive shrank");
        StringBuilder hex=new StringBuilder(64);
        for(byte v:md.digest()){hex.append(Character.forDigit((v>>>4)&15,16));hex.append(Character.forDigit(v&15,16));}
        return hex.toString();
    }
}
