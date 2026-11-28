package hmgapi.mods.wireless;
// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Conservative private-directory admission budget. Never deletes evidence.
 * Four slots, each reserving 64 MiB archive + 4 MiB metadata (272 MiB total).
 * Orphans and interrupted writes also occupy slots. Caller must use a dedicated
 * app-private directory and serialize other writers across process boundaries.
 * This monitor serializes admission only within this Java process.
 */
final class ThemeQuarantineBudget {
    static final int MAX_SLOTS=4;
    static final long MAX_METADATA=4L*1024*1024;
    private static final String STEM="theme-[A-Za-z0-9-]{1,100}";

    private ThemeQuarantineBudget(){}

    static synchronized ThemeArchiveCapture admit(File directory,int chunks,long byteLimit)throws IOException{
        if(directory==null || !directory.isDirectory())throw new IOException("missing quarantine directory");
        File root=directory.getCanonicalFile();
        if(!root.equals(directory.getAbsoluteFile()))throw new IOException("unsafe quarantine directory");
        File[] files=root.listFiles();
        if(files==null || files.length>MAX_SLOTS*3)throw new IOException("quarantine directory unavailable or over budget");
        Set<String> slots=new HashSet<String>();
        for(File f:files){
            if(!f.equals(f.getCanonicalFile()) || !f.isFile())
                throw new IOException("unexpected quarantine directory entry");
            String name=f.getName(),slot;
            long max;
            if(name.matches("theme-meta-[A-Za-z0-9-]{1,100}\\.part")){
                // A metadata staging file cannot be assigned to a capture safely.
                slot=name;max=MAX_METADATA;
            }else if(name.matches(STEM+"\\.part\\.quarantine\\.metadata\\.zip")){
                slot=name.substring(0,name.length()-".quarantine.metadata.zip".length());
                max=MAX_METADATA;
            }else if(name.matches(STEM+"\\.part\\.quarantine")){
                slot=name.substring(0,name.length()-".quarantine".length());
                max=ThemeArchiveCapture.MAX_BYTES;
            }else if(name.matches(STEM+"\\.part")){
                slot=name;max=ThemeArchiveCapture.MAX_BYTES;
                if(new File(root,name+".quarantine").exists())
                    throw new IOException("conflicting partial and completed capture");
            }else{
                throw new IOException("unrecognized quarantine entry; preserve for inspection");
            }
            if(f.length()>max)throw new IOException("existing quarantine entry exceeds limit");
            slots.add(slot);
        }
        if(slots.size()>=MAX_SLOTS)throw new IOException("quarantine capacity exhausted; preserve captures");
        // No eviction and no implicit cleanup. Existing captures keep their slots.
        return new ThemeArchiveCapture(root,chunks,byteLimit);
    }
}
