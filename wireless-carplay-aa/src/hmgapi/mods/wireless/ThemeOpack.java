package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Narrow OPACK dictionary decoder for quarantined theme reception.
 * Tag mapping and reference semantics adapted from CPUL OpackCodec.java,
 * e3f5772829d0fd23efa78cf8c8e35a20cb4525e5, based on LIVI by Lasse Heitgres
 * (https://github.com/f-io/LIVI). Only scalar dictionary values supported.
 * Bounds are local policy. Apple FileTransfer wire interoperability not verified.
 */
final class ThemeOpack {
    static final int MAX_MESSAGE=5*1024*1024, MAX_FIELDS=32;
    private final byte[] bytes;
    private int pos;
    private final ArrayList<Object> seen=new ArrayList<Object>();
    private ThemeOpack(byte[] bytes){this.bytes=bytes;}
    static Map<String,Object> decode(byte[] bytes)throws IOException{
        if(bytes==null || bytes.length<1 || bytes.length>MAX_MESSAGE)throw new IOException("OPACK size");
        ThemeOpack p=new ThemeOpack(bytes);
        int tag=p.take();
        if(tag<0xe0 || tag>0xef)throw new IOException("OPACK root must be dictionary");
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        boolean open=tag==0xef;
        for(int i=0;open || i<tag-0xe0;i++){
            if(open && p.pos<bytes.length && (bytes[p.pos]&255)==3){p.pos++;break;}
            if(i>=MAX_FIELDS)throw new IOException("OPACK dictionary limit");
            Object k=p.scalar();
            if(!(k instanceof String) || ((String)k).length()>128 || result.containsKey(k))
                throw new IOException("OPACK key invalid or duplicated");
            result.put((String)k,p.scalar());
        }
        if(p.pos!=bytes.length)throw new IOException("OPACK trailing bytes");
        return result;
    }
    private int take()throws IOException{
        if(pos==bytes.length)throw new IOException("OPACK truncated");
        return bytes[pos++]&255;
    }
    private long little(int width)throws IOException{
        long n=0;
        for(int i=0;i<width;i++)n|=(long)take()<<(8*i);
        return n;
    }
    private byte[] data(int tag,int base,int max)throws IOException{
        int n=tag-base;
        long length=n<=32?n:little(n-32);
        if(length<0 || length>max || length>bytes.length-pos)throw new IOException("OPACK length");
        byte[] b=java.util.Arrays.copyOfRange(bytes,pos,pos+(int)length);
        pos+=(int)length;
        return b;
    }
    private Object remember(Object o)throws IOException{
        if(seen.size()>=MAX_FIELDS*2)throw new IOException("OPACK reference limit");
        seen.add(o);return o;
    }
    private Object scalar()throws IOException{
        int t=take();
        if(t==1)return Boolean.TRUE;
        if(t==2)return Boolean.FALSE;
        if(t==4)return null;
        if(t==7)return -1L;
        if(t>=8 && t<0x30)return (long)(t-8);
        if(t>=0x30 && t<=0x33){
            int width=1<<(t-0x30),shift=64-width*8;
            return remember((little(width)<<shift)>>shift);
        }
        if(t>=0x40 && t<=0x64){
            byte[] b=data(t,0x40,4096);
            return remember(Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString());
        }
        if(t>=0x70 && t<=0x94)return remember(data(t,0x70,4*1024*1024));
        if(t>=0xa0 && t<=0xc4){
            long index=t<=0xc0?t-0xa0:little(t-0xc0);
            if(index<0 || index>=seen.size())throw new IOException("OPACK reference");
            return seen.get((int)index);
        }
        // No nested containers, floating-point values, UUIDs, dates or unknown tags.
        throw new IOException("unsupported OPACK scalar tag");
    }
}
