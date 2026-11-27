package hmgapi.mods.wireless.dipo;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Single writer; callers never perform disk IO. One instance owns one local spool. */
final class SessionLogWriter {
    interface Exporter {
        File destinationRoot() throws Exception;
        void exportOne(File source,File root) throws Exception;
    }
    static boolean sessionName(String name) {
        return name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}_session-[0-9]+_[0-9a-f-]{36}");
    }
    static final int MAX_SESSIONS = 20;
    static final long MAX_TOTAL = 64L * 1024 * 1024;
    static final long MAX_SESSION = 8L * 1024 * 1024;
    static final int MAX_QUEUE = 2048;
    private static final long ERROR_RESERVE = 512 * 1024;
    private final File root;
    private final Exporter exporter;
    private final Object exportMonitor=new Object();
    private final Object spoolLock=new Object();
    private volatile File exporting;
    private volatile boolean spoolReady;
    private Thread exportWorker;
    private boolean exportRequested;
    private volatile String exportFailure;
    String exportFailure() { return exportFailure; }
    private final ArrayBlockingQueue<Event> queue = new ArrayBlockingQueue<Event>(MAX_QUEUE);
    private AtomicLong admittedDrops;
    private long admittedGeneration;
    private AtomicLong activeDrops = new AtomicLong();
    private final Thread worker;
    private volatile String failure;
    private volatile boolean closed;
    private File directory;
    private FileOutputStream normal, errors;
    private final EnumMap<SessionLogPolicy.Category,FileOutputStream> diagnostics = new EnumMap<SessionLogPolicy.Category,FileOutputStream>(SessionLogPolicy.Category.class);
    private final EnumSet<SessionLogPolicy.Category> enabledDiagnostics;
    private long diagnosticBytes;
    private long start, written, errorBytes, suppressed, generation;
    private final LinkedHashMap<String,String> milestones=new LinkedHashMap<String,String>();
    private String currentId;
    private final EnumMap<SessionLogPolicy.Category,Long> eventCounts=new EnumMap<SessionLogPolicy.Category,Long>(SessionLogPolicy.Category.class);
    private long warningCount,errorCount,firstEventAt,lastEventAt;

    private static final class Event {
        final int kind;
        final long time, generation;
        final String text;
        final boolean important;
        SessionLogPolicy.Category category;
        AtomicLong drops;
        Event(int kind, long time, long generation, String text, boolean important) {
            this.kind=kind; this.time=time; this.generation=generation; this.text=text; this.important=important;
        }
    }
    SessionLogWriter(File root) { this(root,null); }
    SessionLogWriter(File root, Exporter exporter) { this(root,exporter,EnumSet.noneOf(SessionLogPolicy.Category.class)); }
    SessionLogWriter(File root, Exporter exporter, EnumSet<SessionLogPolicy.Category> enabledDiagnostics) {
        this.root=root;
        this.enabledDiagnostics=enabledDiagnostics.clone();
        this.exporter=exporter;
        worker=new Thread(new Runnable(){ public void run(){ loop(); } }, "wcp-session-log");
        worker.setDaemon(true);
        worker.start();
        if(exporter!=null) {
            exportWorker=new Thread(new Runnable(){public void run(){exportLoop();}},"wcp-usb-export");
            exportWorker.setDaemon(true); exportWorker.start();
        }
    }
    String failure() { return failure; }
    void start(long generation, String metadata) {
        offer(new Event(1,System.currentTimeMillis(),generation,SessionLogPolicy.sanitize(metadata),true));
    }
    void record(long generation, SessionLogPolicy.Level level, SessionLogPolicy.Category category, String safe) {
        recordAt(generation,level,category,safe,System.currentTimeMillis());
    }
    void recordAt(long generation, SessionLogPolicy.Level level, SessionLogPolicy.Category category, String safe,long at) {
        if (safe == null) return;
        Event e=new Event(0,at,generation,level+" "+category+" "+SessionLogPolicy.sanitize(safe),
            level==SessionLogPolicy.Level.ERROR || level==SessionLogPolicy.Level.WARN);
        e.category=category;
        offer(e);
    }
    void finish(long generation, String reason, long suppressed) {
        finishAt(generation,reason,suppressed,System.currentTimeMillis());
    }
    void finishAt(long generation,String reason,long suppressed,long endMillis) {
        offer(new Event(2,endMillis,generation,SessionLogPolicy.sanitize(reason)+"; policy_suppressed="+suppressed,true));
    }
    // Reserve capacity for lifecycle and error events. On overload evict ordinary messages first.
    private synchronized void offer(Event e) {
        if(closed)return;
        if(e.kind==1){
            // Reserve one end slot for every admitted generation; no unbounded lifecycle backlog.
            if(admittedDrops!=null || queue.remainingCapacity()<2){
                failure="session start rejected: lifecycle backlog";return;
            }
            admittedGeneration=e.generation;
            admittedDrops=new AtomicLong();
            e.drops=admittedDrops;
            if(!queue.offer(e)){admittedDrops=null;failure="session start admission failed";}
            return;
        }
        if(admittedDrops==null || admittedGeneration!=e.generation)return;
        e.drops=admittedDrops;
        if(e.kind==2){
            if(!queue.offer(e))failure="reserved session end admission failed";
            admittedDrops=null;
            return;
        }
        int reserve=e.important?1:128;
        if(queue.remainingCapacity()<=reserve){e.drops.incrementAndGet();return;}
        if(!queue.offer(e))e.drops.incrementAndGet();
    }
    synchronized void shutdown() { closed=true; requestExport(); }
    boolean await(long millis) throws InterruptedException { worker.join(millis); return !worker.isAlive(); }
    private void loop() {
        long last=androidIndependentMonotonic();
        try {
            ensureDir(root);
            try { recover(root); } catch(IOException ex) { failure=ex.getMessage(); }
            spoolReady=true;
            requestExport();
            try { prune(false); } catch(IOException ex) { failure=ex.getMessage(); }
            while(!closed || !queue.isEmpty()) {
                Event e=queue.poll(250,TimeUnit.MILLISECONDS);
                if(e!=null) {
                    try {
                        if(e.kind==1) {
                            if(directory!=null) complete(e.time,"superseded without finalization");
                            begin(e);
                        } else if(directory!=null && generation==e.generation) {
                            if(e.kind==2) complete(e.time,e.text);
                            else append(e);
                        }
                    } catch(IOException ex) {
                        failure=ex.toString(); abandon();
                    }
                }
                long now=androidIndependentMonotonic();
                if(now-last>=5000) {
                    try { sync(); } catch(IOException ex){ failure=ex.toString(); abandon(); }
                    last=now;
                }
            }
            if(directory!=null) complete(System.currentTimeMillis(),"logger shutdown; session end not observed");
        } catch(Exception e) { failure=e.toString(); }
        finally { abandon(); }
    }
    private static long androidIndependentMonotonic(){ return System.nanoTime()/1000000L; }
    private void begin(Event e) throws IOException {
        try { recover(root); } catch(IOException ex) { failure=ex.getMessage(); }
        prune(true);
        milestones.clear(); eventCounts.clear(); warningCount=errorCount=0;
        firstEventAt=lastEventAt=e.time;
        activeDrops=e.drops;
        start=e.time; generation=e.generation; written=0; errorBytes=0; diagnosticBytes=0; suppressed=0;
        currentId=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date(start))
            +"_session-"+generation+"_"+UUID.randomUUID().toString();
        directory=new File(root,currentId+".open");
        ensureDir(directory);
        normal=new FileOutputStream(new File(directory,"session.log"));
        errors=new FileOutputStream(new File(directory,"errors.log"));
        writeText(new File(directory,"summary.txt"),"generation="+generation+"\nstart_ms="+start+"\n"+e.text+"\n");
        append(new Event(0,e.time,generation,"INFO CORE session started",false));
        sync();
    }
    private void append(Event e) throws IOException {
        summarize(e);
        byte[] b=(e.time+" "+e.text+"\n").getBytes("UTF-8");
        if(written+diagnosticBytes+b.length <= MAX_SESSION-ERROR_RESERVE-65536) { normal.write(b); written+=b.length; }
        else suppressed++;
        if(e.important && errorBytes+b.length<=ERROR_RESERVE) { errors.write(b); errorBytes+=b.length; }
        else if(e.important) suppressed++;
        if(e.category!=null && enabledDiagnostics.contains(e.category)) {
            if(written+diagnosticBytes+b.length <= MAX_SESSION-ERROR_RESERVE-65536) {
                FileOutputStream stream=diagnostics.get(e.category);
                if(stream==null) {
                    File d=new File(directory,"diagnostics"); ensureDir(d);
                    stream=new FileOutputStream(new File(d,e.category.name().toLowerCase(Locale.US)+".log"));
                    diagnostics.put(e.category,stream);
                }
                stream.write(b); diagnosticBytes+=b.length;
            } else suppressed++;
        }
    }
    private void summarize(Event e) {
        if(e.category==null)return;
        Long count=eventCounts.get(e.category);
        eventCounts.put(e.category,count==null?1L:count+1);
        firstEventAt=Math.min(firstEventAt,e.time); lastEventAt=Math.max(lastEventAt,e.time);
        String s=e.text;
        if(s.startsWith("WARN "))warningCount++;
        if(s.startsWith("ERROR "))errorCount++;
        String key=null;
        if(s.contains("[milestone] identity_prepared"))key="identity_prepared";
        else if(s.contains("[milestone] identity_advertised"))key="identity_advertised";
        else if(s.contains("[milestone] transport_ready"))key="transport_ready";
        else if(e.important)key="last_"+e.category.name().toLowerCase(Locale.US)+"_warning";
        if(key!=null)milestones.put(key,e.time+" "+(s.length()>512?s.substring(0,512):s));
    }
    private void sync() throws IOException {
        if(normal!=null) { normal.flush(); normal.getFD().sync(); }
        if(errors!=null) { errors.flush(); errors.getFD().sync(); }
        for(FileOutputStream stream:diagnostics.values()){stream.flush();stream.getFD().sync();}
    }
    private void complete(long end,String reason) throws IOException {
        File folder=directory;
        append(new Event(0,end,generation,"INFO CORE session finalized: "+reason,false));
        sync();
        normal.close(); normal=null; errors.close(); errors=null;
        closeDiagnostics();
        FileOutputStream out=new FileOutputStream(new File(folder,"summary.txt"),true);
        try {
            out.write(("end_ms="+end+"\nduration_ms="+Math.max(0,end-start)+"\ntermination="+reason+
                "\nqueue_dropped="+activeDrops.get()+"\nsize_dropped="+suppressed+"\n").getBytes("UTF-8"));
            out.write(("first_event_ms="+firstEventAt+"\nlast_event_ms="+lastEventAt+"\nwarning_count="+warningCount+"\nerror_count="+errorCount+"\n").getBytes("UTF-8"));
            for(Map.Entry<SessionLogPolicy.Category,Long> item:eventCounts.entrySet())out.write(("events_"+item.getKey().name().toLowerCase(Locale.US)+"="+item.getValue()+"\n").getBytes("UTF-8"));
            for(Map.Entry<String,String> item:milestones.entrySet())out.write((item.getKey()+"="+item.getValue()+"\n").getBytes("UTF-8"));
            out.getFD().sync();
        } finally { out.close(); }
        if(!folder.renameTo(new File(root,currentId))) throw new IOException("session finalization rename failed");
        directory=null;
        requestExport();
        prune(false);
    }
    private void requestExport() {
        synchronized(exportMonitor){exportRequested=true;exportMonitor.notifyAll();}
    }
    private void exportLoop() {
        while(!closed) {
            synchronized(exportMonitor) {
                if(!exportRequested)try{exportMonitor.wait(15000);}catch(InterruptedException e){return;}
                exportRequested=false;
            }
            if(!spoolReady||closed)continue;
            try {
                File dest=exporter.destinationRoot();
                if(dest==null)continue;
                File[] files=root.listFiles();
                if(files==null)continue;
                Arrays.sort(files);
                for(File f:files) {
                    if(closed)break;
                    synchronized(spoolLock) {
                        if(!f.isDirectory()||!sessionName(f.getName())||verifiedMarker(f))continue;
                        exporting=f;
                    }
                    try {exporter.exportOne(f,dest);exportFailure=null;}
                    catch(Exception e){exportFailure=e.getClass().getSimpleName();break;}
                    finally{synchronized(spoolLock){exporting=null;}}
                }
            } catch(Exception e){exportFailure=e.getClass().getSimpleName();}
        }
    }
    private void closeDiagnostics() {
        for(FileOutputStream stream:diagnostics.values())try{stream.close();}catch(IOException ignored){}
        diagnostics.clear();
    }
    private void abandon() {
        closeDiagnostics();
        try { if(normal!=null) normal.close(); } catch(IOException ignored){}
        try { if(errors!=null) errors.close(); } catch(IOException ignored){}
        normal=null; errors=null; directory=null;
    }
    static void ensureDir(File dir) throws IOException {
        if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create log directory");
    }
    static void writeText(File file,String text) throws IOException {
        FileOutputStream out=new FileOutputStream(file);
        try { out.write(text.getBytes("UTF-8")); out.getFD().sync(); } finally { out.close(); }
    }
    static void recover(File root) throws IOException {
        File[] files=root.listFiles();
        if(files==null)return;
        IOException failure=null;
        for(File f:files) if(f.isDirectory() && f.getName().endsWith(".open")) {
            try {
                File target=new File(root,f.getName().substring(0,f.getName().length()-5));
                if(target.exists())throw new IOException("recovery destination conflict");
                for(String name:new String[]{"session.log","errors.log"}){
                    File payload=new File(f,name);
                    if(!payload.exists())writeText(payload,"");
                }
                File summary=new File(f,"summary.txt");
                // Bounded recovery annotation, even if repeated rename attempts fail.
                if(summary.length()<32768){
                    try(FileOutputStream out=new FileOutputStream(summary,true)){
                        out.write("\ntermination=unknown; recovered unfinished session\n".getBytes("UTF-8"));
                        out.getFD().sync();
                    }
                }
                if(!f.renameTo(target))throw new IOException("recovery rename failed");
            }catch(IOException ex){if(failure==null)failure=ex;}
        }
        if(failure!=null)throw failure;
    }
    static boolean verifiedMarker(File session) {
        File marker=new File(session,".exported");
        try {
            if(!marker.isFile()||marker.length()!=9)return false;
            if(!marker.getCanonicalFile().equals(new File(session.getCanonicalFile(),".exported")))return false;
            try(DataInputStream in=new DataInputStream(new FileInputStream(marker))){
                byte[] bytes=new byte[9];in.readFully(bytes);
                return Arrays.equals(bytes,"verified\n".getBytes("UTF-8"))&&in.read()==-1;
            }
        }catch(IOException|SecurityException ex){return false;}
    }
    private void prune(boolean reserve) throws IOException {
        synchronized(spoolLock) {
        File[] files=root.listFiles();
        if(files==null) return;
        List<File> sessions=new ArrayList<File>();
        long size=0;
        int interrupted=0;
        for(File f:files) if(f.isDirectory()) {
            if(sessionName(f.getName())) { sessions.add(f); size+=size(f); }
            else if(f.getName().endsWith(".open")) { interrupted++;size+=size(f); }
        }
        Collections.sort(sessions,new Comparator<File>(){public int compare(File a,File b){boolean ae=verifiedMarker(a), be=verifiedMarker(b); if(ae!=be)return ae?-1:1; return a.getName().compareTo(b.getName());}});
        // Reserve one full session while a writer may be active.
        while(sessions.size()+interrupted>(reserve?MAX_SESSIONS-1:MAX_SESSIONS) || size>MAX_TOTAL-(reserve?MAX_SESSION:0)) {
            File f=null;
            for(File candidate:sessions)if(!candidate.equals(exporting)&&verifiedMarker(candidate)){f=candidate;break;}
            if(f==null)throw new IOException("log retention full: unexported recordings preserved");
            sessions.remove(f); long n=size(f); delete(f); size-=n;
        }
        }
    }
    static long size(File f) {
        if(!f.isDirectory()) return f.length();
        long n=0; File[] children=f.listFiles(); if(children!=null) for(File c:children)n+=size(c); return n;
    }
    static void delete(File f) throws IOException {
        if(f.isDirectory()) { File[] children=f.listFiles(); if(children!=null)for(File c:children)delete(c); }
        if(!f.delete() && f.exists()) throw new IOException("log retention delete failed");
    }
}
