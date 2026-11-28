package hmgapi.mods.wireless;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import hmgapi.mods.wireless.api.CarPlayIdentification;

/** Arms the native serializer from one validated snapshot of all contributions. */
final class IdentityInstaller {
    private IdentityInstaller() {}
    private static boolean loaded;
    private static hmgapi.mods.wireless.dipo.StartupRecording startup;
    private static void log(ModContext mod,String message){ mod.log(message); if(startup!=null) startup.record(message); }
    private static boolean groupsWanted(ModContext mod, IdentityRegistry registry){ return mod.enabled() && (mod.settingBoolean("rc7_force_vehicle_groups",false) || registry.vehicle()!=null); }
    private static volatile boolean armed;
    private static boolean suspended, shutdownPending;
    private static final ResumeRecovery resumeRecovery=new ResumeRecovery();
    private static long armedGeneration=-1;
    private static byte[] lastStart;
    private static final StartWithdrawal withdrawal=new StartWithdrawal();
    private static String lastStartNotice = "";
    private static boolean watcherStarted;

    private static final class NativeFailure extends IllegalStateException {
        final int code;
        NativeFailure(int code) { super("native CarPlay identification=" + code); this.code = code; }
    }

    static void install(final ModContext mod, final LoadedApp app, IdentityRegistry registry) {
        final IdentityRegistry contributions = registry;
        if(app.context!=null && startup==null)try { startup=new hmgapi.mods.wireless.dipo.StartupRecording(app.context,"wcp-iap2-startup-bundles"); }catch(Throwable t){mod.error("RC8 iAP2 startup recorder unavailable",t);}
        log(mod,"RC8 iAP2 identity installer entering");
        registry.onChange(new Runnable() {
            @Override public void run() {
                try {
                    apply(mod, app, contributions);
                    if(groupsWanted(mod,contributions))enableGroups(app.classLoader);
                }
                catch (Throwable t) { failure(mod, "CarPlay identification contribution change", t); }
            }
        });
        // The verified gate is safe while unarmed and permits consumers loaded live later.
        mod.nativePatch(app, "libiap2session.so");
        // Do not let the JNI library be the first code to dlopen the target: native dlopen
        // bypasses HMG-API's Runtime.load hooks, leaving the queued recipe unapplied.
        try {
            NativeIdentity.loadTarget();
            apply(mod, app, registry);
        }
        catch (Throwable t) {
            // Identification is an optional extension of the iAP2 server. A native ABI
            // mismatch must not prevent Wireless from installing its bridge and TBT hooks.
            failure(mod, "CarPlay identification unavailable; Wireless continues without it", t);
        }
        MethodHook groups = new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                try {
                    // appLoaded may precede the iAP2 server's native initialization. This
                    // point runs after it and before the identification message is built.
                    if (!armed) apply(mod, app, contributions);
                    else refreshStart(mod, app, contributions);
                    if(groupsWanted(mod,contributions))enableGroups(app.classLoader);
                } catch (Throwable t) {
                    failure(mod, "CarPlay identification late initialization", t);
                }
            }
        };
        mod.hook(app.classLoader, "iap2.init_info.check_supports_features", groups);
        try { if(groupsWanted(mod,contributions))enableGroups(app.classLoader); }
        catch (Exception e) { mod.error("CarPlay stock vehicle groups unavailable; retaining stock behavior", e); }
        log(mod,armed ? "CarPlay identification: contribution serializer installed"
                : "CarPlay identification: waiting for iAP2 native initialization");
        mod.hook(app.classLoader, "iap2.service.receive_connect", new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                if (p.args.length != 1 || !(p.args[0] instanceof Integer) || ((Integer)p.args[0]) != 4) return;
                try {
                    if (!armed) apply(mod, app, contributions);
                    refreshStart(mod, app, contributions);
                } catch (Throwable t) { failure(mod, "CarPlayStart Bluetooth pre-connect", t); }
                // Stock vehicle groups are independent of the optional session extension.
                // A missing group hook must not prevent session preparation.
                try { if(groupsWanted(mod,contributions))enableGroups(app.classLoader); }
                catch (Throwable t) { failure(mod, "CarPlay stock vehicle groups before connect", t); }
            }
        });
        watchStart(mod, app, registry);
    }

    private static synchronized void apply(ModContext mod, LoadedApp app, IdentityRegistry registry) {
        if(!mod.enabled()){suspend(mod,registry);return;}
        if(shutdownPending && !finishShutdown(mod))return;
        if(suspended){registry.setSuspended(false);suspended=false;resumeRecovery.begin();}
        IdentityRegistry.Snapshot snapshot=registry.snapshot();
        CarPlayIdentification.VehicleIdentity vehicle=snapshot.vehicle;
        CarPlayIdentification.ThemeIdentity theme=snapshot.theme;
        if(armed && armedGeneration==snapshot.generation) { refreshStart(mod,app,registry); return; }
        armed=false; armedGeneration=-1;
        try {
        if(loaded && (mod.settingBoolean("rc7_rearm",false) || (vehicle==null && theme==null))){shutdownPending=true;if(!finishShutdown(mod))throw new IllegalStateException("native shutdown pending");}
        lastStart=null;
        withdrawal.setPending(loaded);
        if(vehicle==null && theme==null){resumeRecovery.stop();return;}
        int dcType = -1, dcPower = 0, acType = -1, acPower = 0;
        if (vehicle != null) {
            for (CarPlayIdentification.Connector connector : vehicle.connectors) {
                if (connector.type == CarPlayIdentification.VehicleIdentity.CONNECTOR_AC) {
                    if (acType != -1) throw new IllegalStateException("more than one CarPlay AC connector");
                    acType = connector.type; acPower = connector.maximumPowerW;
                } else {
                    if (dcType != -1) throw new IllegalStateException("more than one CarPlay DC connector");
                    dcType = connector.type; dcPower = connector.maximumPowerW;
                }
            }
            if (vehicle.engineType == CarPlayIdentification.VehicleIdentity.ENGINE_ELECTRIC && (dcType == -1 || acType == -1))
                throw new IllegalStateException("CarPlay EV identity requires one DC and one AC connector");
        }
        if (!loaded) {
            NativeIdentity.load();
            int mode=(mod.settingBoolean("rc7_wireless_callbacks",false)?1:0)
                |(mod.settingBoolean("rc7_theme_replace",false)?2:0)
                |(mod.settingBoolean("rc7_capability_probe",false)?4:0);
            NativeIdentity.compatibilityMode(mode); loaded = true;
            log(mod,"RC8 native RC7 opt-in bits="+mode);
        }
        try {
            byte[] name = vehicle == null ? new byte[0] : vehicle.displayName.getBytes("UTF-8");
            byte[] themeId = theme == null ? new byte[0] : theme.encoded().getBytes("US-ASCII");
            if(!registry.report(snapshot.generation,CarPlayIdentification.Readiness.PREPARED,"validated native inputs")) return;
            int rc = NativeIdentity.configure(name, vehicle == null ? -1 : vehicle.engineType,
                    dcType, dcPower, acType, acPower, themeId, true); // RC6 verified full-tuple identity format
            if (rc != 0) throw new NativeFailure(rc);
            if(!registry.report(snapshot.generation,CarPlayIdentification.Readiness.ARMED,"native configure succeeded")) {
                shutdownPending=true;finishShutdown(mod);return;
            }
            withdrawal.setPending(false); // successful native configure clears its payload
            armed = true;
            resumeRecovery.stop();
            armedGeneration=snapshot.generation;
            log(mod,"CarPlay identity: generation="+armedGeneration+" armed");
            if (theme != null) {
                log(mod,"CarPlay theme: configured to report as " + theme.encoded()
                        + " (legacy parameters 5/6; verified K5 format)");
            }
            refreshStart(mod, app, registry);
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        } catch(Throwable error) {
            armed=false; armedGeneration=-1; lastStart=null; withdrawal.setPending(loaded);
            if(loaded){shutdownPending=true;finishShutdown(mod);}
            // Only retry the known transient native-gate error automatically.
            resumeRecovery.failed(error instanceof NativeFailure ? ((NativeFailure)error).code : 0);
            registry.report(snapshot.generation,CarPlayIdentification.Readiness.FAILED,
                    error instanceof NativeFailure ? "native configure="+((NativeFailure)error).code : error.getClass().getSimpleName());
            throw error;
        }
    }

    private static synchronized void suspend(ModContext mod,IdentityRegistry registry) {
        if(!suspended){
            suspended=true;
            resumeRecovery.stop();
            registry.setSuspended(true);
            armed=false;armedGeneration=-1;
            lastStart=null;
            shutdownPending=loaded;
            withdrawal.setPending(loaded);
        }
        if(shutdownPending)finishShutdown(mod);
        if(withdrawal.pending())clearStart(mod,registry);
    }

    private static boolean finishShutdown(ModContext mod){
        try {
            int rc=NativeIdentity.shutdown();
            if(rc!=0){notice(mod,"native shutdown pending ("+rc+")");return false;}
            shutdownPending=false;
            return true;
        }catch(Throwable error){
            notice(mod,"native shutdown pending ("+error.getClass().getSimpleName()+")");
            return false;
        }
    }

    private static boolean clearStart(ModContext mod,IdentityRegistry registry) {
        registry.clearSessionEvidence(armedGeneration);
        lastStart=null;
        if(!loaded){withdrawal.setPending(false);return true;}
        withdrawal.setPending(true);
        try {
            int rc=withdrawal.clear(new StartWithdrawal.NativeClear(){
                @Override public int run(){return NativeIdentity.startSession(new byte[0]);}
            });
            if(rc!=0){notice(mod,"native withdrawal pending ("+rc+")");return false;}
            return true;
        }catch(Throwable error){
            notice(mod,"native withdrawal pending ("+error.getClass().getSimpleName()+")");
            return false;
        }
    }

    private static void notice(ModContext mod, String status) {
        if (!status.equals(lastStartNotice)) {
            log(mod,"CarPlayStart: " + status);
            lastStartNotice = status;
        }
    }

    private static synchronized void refreshStart(ModContext mod, LoadedApp app, IdentityRegistry registry) {
        if(!mod.enabled()){suspend(mod,registry);return;}
        if(suspended)return;
        if (!loaded) return;
        if(withdrawal.pending() && !clearStart(mod,registry))return;
        if(!armed)return;
        IdentityRegistry.Snapshot snapshot=registry.snapshot();
        if(snapshot.generation!=armedGeneration) { clearStart(mod,registry); return; }
        CarPlayIdentification.ThemeIdentity theme = snapshot.theme;
        if (!mod.enabled() || !mod.settingBoolean("bridge", true) || theme == null || !snapshot.start) {
            if (lastStart != null && !clearStart(mod,registry)) return;
            notice(mod, "extension not requested");
            return;
        }
        try {
            StartSession.Preparation preparation=StartSession.prepare(app.context, theme, mod);
            byte[] msg=preparation.message;
            if (msg == null) {
                if (lastStart != null && !clearStart(mod,registry)) return;
                notice(mod, "preparation blocked=" + preparation.blocker.name() + "; stock startup retained");
                return;
            }
            if (java.util.Arrays.equals(lastStart, msg)) return;
            registry.clearSessionEvidence(armedGeneration);
            withdrawal.setPending(true); // failed/throwing native call may leave an older payload
            int result = NativeIdentity.startSession(msg);
            if (result != 0) {
                if(!clearStart(mod,registry))return;
                notice(mod, "native session extension refused (" + result + "); stock startup retained");
                return;
            }
            withdrawal.setPending(false);
            lastStart = msg;
            notice(mod, "armed 0x4300/0x4301 (" + msg.length + " bytes; contents not logged)");
        } catch (Throwable failure) {
            if(!clearStart(mod,registry))return;
            notice(mod, "extension unavailable (" + failure.getClass().getSimpleName() + "); stock startup retained");
        }
    }

    private static synchronized void watchStart(final ModContext mod, final LoadedApp app, final IdentityRegistry registry) {
        if (watcherStarted) return;
        watcherStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int offers = 0, sends = 0, noPayload = 0, allocation = 0, stale = 0, lock = 0, dispatched = 0, refused = 0;
                int evidence=-1, capabilityEvidence=-1;long evidenceGeneration=-1;
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        synchronized(IdentityInstaller.class){
                            if(!mod.enabled())suspend(mod,registry);
                            else if(suspended || resumeRecovery.pending()){
                                if(shutdownPending && !finishShutdown(mod)){
                                    // Do not consume the configure budget while shutdown is blocked.
                                }else if(!suspended && !resumeRecovery.canAttempt()){
                                    resumeRecovery.stop();
                                    log(mod,"CarPlay identity: automatic resume retries exhausted; next connection may retry");
                                }else{
                                    try{apply(mod,app,registry);}
                                    catch(Throwable t){failure(mod,"CarPlay identity resume",t);}
                                    resumeRecovery.attempted();
                                }
                            }else{
                                if(shutdownPending)finishShutdown(mod);
                                refreshStart(mod,app,registry);
                            }
                        }
                        synchronized (IdentityInstaller.class) {
                            if (loaded) {
                                int observed=NativeIdentity.negotiationEvidence();
                                if(armed && observed>=0 && registry.identityStatus().readiness==CarPlayIdentification.Readiness.ARMED
                                        && (observed&64)!=0
                                        && registry.report(armedGeneration,CarPlayIdentification.Readiness.ADVERTISED,"native identification builder completed; delivery not confirmed")) {
                                    log(mod,"CarPlay identity: generation="+armedGeneration+" identification built; phone acceptance unconfirmed");
                                }
                                int currentEvidence=observed&7;
                                if(observed>=0){
                                if(currentEvidence!=evidence || armedGeneration!=evidenceGeneration){
                                    log(mod,"CarPlay identity: generation="+armedGeneration
                                            +" vehicle values built="+((currentEvidence&5)==5)
                                            +" theme value built="+((currentEvidence&2)!=0));
                                    evidence=currentEvidence;evidenceGeneration=armedGeneration;
                                }
                                int capability=(observed>>3)&3;
                                if(armed && !shutdownPending)
                                    registry.reportNegotiation(armedGeneration,currentEvidence,capability,
                                            (observed&32)!=0 && lastStart!=null && !withdrawal.pending());
                                if(capability!=capabilityEvidence){
                                    log(mod,"CarPlayStart: capability builder evidence sent="+((capability&1)!=0)
                                            +" received="+((capability&2)!=0)+"; delivery unconfirmed");
                                    capabilityEvidence=capability;
                                }
                                }
                                int o = NativeIdentity.startSessionStatus(0), n = NativeIdentity.startSessionStatus(1);
                                int p=NativeIdentity.startSessionStatus(3), a=NativeIdentity.startSessionStatus(4);
                                int s=NativeIdentity.startSessionStatus(5), l=NativeIdentity.startSessionStatus(6);
                                int ok=NativeIdentity.startSessionStatus(7), rejected=NativeIdentity.startSessionStatus(8);
                                if (o != offers || n != sends || p != noPayload || a != allocation || s != stale || l != lock || ok != dispatched || rejected != refused) {
                                    log(mod,"CarPlayStart: availability offers=" + o + " answers attempted=" + n
                                            + " OEM dispatch accepted=" + ok + " OEM dispatch refused=" + rejected
                                            + " no payload=" + p + " allocation failures=" + a
                                            + " stale drops=" + s + " lock failures=" + l
                                            + " last result=" + NativeIdentity.startSessionStatus(2));
                                    offers=o; sends=n; noPayload=p; allocation=a; stale=s; lock=l; dispatched=ok; refused=rejected;
                                }
                            }
                        }
                        Thread.sleep(2000);
                    }
                } catch (InterruptedException ended) { Thread.currentThread().interrupt(); }
                catch (Throwable error) { log(mod,"CarPlayStart: watcher stopped (" + error.getClass().getSimpleName() + ")"); }
                finally {
                    synchronized (IdentityInstaller.class) {
                        try{suspend(mod,registry);}finally{watcherStarted = false;}
                    }
                }
            }
        }, "carplay-start-session");
        t.setDaemon(true);
        t.start();
    }

    private static void failure(ModContext mod, String message, Throwable t) {
        if(startup!=null)startup.record(message+" error="+t.getClass().getSimpleName());
        if (t instanceof NativeFailure && ((NativeFailure) t).code == -21)
            log(mod,message + ": native gate not ready; will retry before identification");
        else mod.error(message, t);
    }

    // These are stock capability flags, independent of the custom serializer.
    // Resolve both fields before changing either; roll back on a failed write.
    private static synchronized void enableGroups(ClassLoader cl) throws Exception {
        java.lang.reflect.Field info=Points.field(cl,"iap2.init_info.use_vehicle_information");
        java.lang.reflect.Field status=Points.field(cl,"iap2.init_info.use_vehicle_status");
        int oldInfo=info.getInt(null), oldStatus=status.getInt(null);
        try {
            info.setInt(null,1);
            status.setInt(null,1);
        } catch(Exception failure) {
            try {info.setInt(null,oldInfo);}catch(Exception ignored){}
            try {status.setInt(null,oldStatus);}catch(Exception ignored){}
            throw failure;
        }
    }
}
