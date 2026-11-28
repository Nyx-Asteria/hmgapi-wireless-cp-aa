package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Decoded CarPlayUpdateData dictionary adapter; one archive per instance.
 * Bounded OPACK adapter; no transport, acknowledgement, installation or runtime caller.
 * Limits are local policy, not inferred Apple wire limits. Metadata stays
 * untrusted; the digest only establishes consistency with the sender's checksum.
 */
final class ThemeTransferSession implements Closeable {
    private static final int MAX_METADATA = 1024 * 1024;
    private static final int MAX_CHUNK = 4 * 1024 * 1024;
    private final File directory;
    private final long byteLimit;
    private ThemeArchiveCapture capture;
    private Header header;
    private boolean closed;

    static final class Header {
        final String sessionId;
        final long version;
        final int chunkCount;
        private final byte[] certificate, signature, additions;
        Header(String sessionId,long version,int chunkCount,byte[] certificate,byte[] signature,byte[] additions) {
            this.sessionId=sessionId;this.version=version;this.chunkCount=chunkCount;
            this.certificate=certificate;this.signature=signature;this.additions=additions;
        }
        byte[] certificate(){return copy(certificate);}
        byte[] signature(){return copy(signature);}
        byte[] additions(){return copy(additions);}
        private static byte[] copy(byte[] b){return b==null?null:b.clone();}
    }
    static final class Captured {
        final Header header;
        final File archive;
        final long size;
        final String checksum;
        Captured(Header header,File archive,long size,String checksum){
            this.header=header;this.archive=archive;this.size=size;this.checksum=checksum;
        }
    }
    ThemeTransferSession(File directory,long byteLimit){
        this.directory=directory;this.byteLimit=byteLimit;
    }

    /** Accept a single complete, already authenticated application body.
     * This method does not authenticate or frame the transport itself.
     * Malformed OPACK is fatal to this capture, just like a malformed dictionary.
     */
    synchronized Captured receiveEncoded(byte[] body) throws IOException {
        if(closed)throw new IOException("transfer closed");
        try {
            return receive(ThemeOpack.decode(body));
        } catch(IOException failure) {
            try{close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    /** Null means incomplete; only completed capture is returned, never an ACK. */
    synchronized Captured receive(Map<String,?> message) throws IOException {
        if(closed)throw new IOException("transfer closed");
        try {
            if(message==null || message.size()>32)throw new IOException("invalid dictionary");
            long type=integer(message,"messageType",1,2);
            if(type==1){
                if(header!=null)throw new IOException("duplicate header");
                integer(message,"payloadType",1,1);
                Header h=new Header(session(message),integer(message,"version",0,0xffffffffL),
                    (int)integer(message,"chunkCount",1,ThemeArchiveCapture.MAX_CHUNKS),
                    metadata(message,"certificate"),metadata(message,"signature"),metadata(message,"additions"));
                capture=ThemeQuarantineBudget.admit(directory,h.chunkCount,byteLimit);
                header=h;
                return null;
            }
            if(header==null || !header.sessionId.equals(session(message)))throw new IOException("session mismatch");
            int index=(int)integer(message,"chunkIndex",0,header.chunkCount-1);
            Object raw=message.get("data");
            if(!(raw instanceof byte[]) || ((byte[])raw).length>MAX_CHUNK)throw new IOException("invalid payload data");
            Object checksum=message.get("checksum");
            if(message.containsKey("checksum") && !(checksum instanceof String))throw new IOException("invalid checksum type");
            File result=capture.append(index,((byte[])raw).clone(),(String)checksum);
            if(result==null)return null;
            Captured complete=new Captured(header,result,result.length(),(String)checksum);
            close();
            return complete;
        }catch(IOException failure){
            try{close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private static long integer(Map<String,?> m,String key,long min,long max)throws IOException{
        Object value=m.get(key);
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw new IOException("invalid numeric field "+key);
        long n=((Number)value).longValue();
        if(n<min || n>max)throw new IOException("numeric field out of bounds "+key);
        return n;
    }
    private static String session(Map<String,?> m)throws IOException{
        Object v=m.get("sessionID");
        if(!(v instanceof String) || ((String)v).length()==0 || ((String)v).length()>128 || ((String)v).indexOf(0)>=0)
            throw new IOException("invalid session ID");
        return (String)v;
    }
    private static byte[] metadata(Map<String,?> m,String key)throws IOException{
        if(!m.containsKey(key))return null;
        Object v=m.get(key);
        if(!(v instanceof byte[]) || ((byte[])v).length>MAX_METADATA)
            throw new IOException("invalid metadata "+key);
        return ((byte[])v).clone();
    }
    @Override public synchronized void close()throws IOException{
        closed=true;
        ThemeArchiveCapture c=capture;
        capture=null;header=null;
        if(c!=null)c.close();
    }
}
