package hmgapi.mods.wireless;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import hmgapi.mods.wireless.api.CarPlayIdentification;

/** Arms the native serializer from one validated snapshot of all contributions. */
final class IdentityInstaller {
    private IdentityInstaller() {}
    private static boolean loaded, armed;
    private static byte[] lastStart;
    private static String lastStartNotice = "";
    private static boolean watcherStarted;

    private static final class NativeFailure extends IllegalStateException {
        final int code;
        NativeFailure(int code) { super("native CarPlay identification=" + code); this.code = code; }
    }

    static void install(final ModContext mod, final LoadedApp app, IdentityRegistry registry) {
        final IdentityRegistry contributions = registry;
        registry.onChange(new Runnable() {
            @Override public void run() {
                try {
                    apply(mod, app, contributions);
                    if (contributions.vehicle() != null) enableGroups(app.classLoader);
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
                    if (armed && contributions.vehicle() != null) enableGroups(app.classLoader);
                } catch (Throwable t) {
                    failure(mod, "CarPlay identification late initialization", t);
                }
            }
        };
        mod.hook(app.classLoader, "iap2.init_info.check_supports_features", groups);
        try { if (armed && registry.vehicle() != null) enableGroups(app.classLoader); }
        catch (Exception e) { throw new IllegalStateException("CarPlay identification groups", e); }
        mod.log(armed ? "CarPlay identification: contribution serializer installed"
                : "CarPlay identification: waiting for iAP2 native initialization");
        mod.hook(app.classLoader, "iap2.service.receive_connect", new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                if (p.args.length != 1 || !(p.args[0] instanceof Integer) || ((Integer)p.args[0]) != 4) return;
                try {
                    if (!armed) apply(mod, app, contributions);
                    refreshStart(mod, app, contributions);
                    mod.log("CarPlayStart: Bluetooth pre-connect preparation complete");
                } catch (Throwable t) { failure(mod, "CarPlayStart Bluetooth pre-connect", t); }
            }
        });
        watchStart(mod, app, registry);
    }

    private static synchronized void apply(ModContext mod, LoadedApp app, IdentityRegistry registry) {
        CarPlayIdentification.VehicleIdentity vehicle = registry.vehicle();
        CarPlayIdentification.ThemeIdentity theme = registry.theme();
        if (vehicle == null && theme == null) { if (loaded) NativeIdentity.shutdown(); lastStart = null; armed = false; return; }
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
            if (dcType == -1 || acType == -1)
                throw new IllegalStateException("CarPlay EV identity requires one DC and one AC connector");
        }
        if (!loaded) { NativeIdentity.load(); loaded = true; }
        try {
            byte[] name = vehicle == null ? new byte[0] : vehicle.displayName.getBytes("UTF-8");
            byte[] themeId = theme == null ? new byte[0] : theme.encoded().getBytes("US-ASCII");
            // Changing identity invalidates the cached session-start payload.
            clearStart();
            armed = false;
            int rc = NativeIdentity.configure(name, vehicle == null ? -1 : vehicle.engineType,
                    dcType, dcPower, acType, acPower, themeId, true); // RC6 verified full-tuple identity format
            if (rc != 0) throw new NativeFailure(rc);
            armed = true;
            if (theme != null) {
                mod.log("CarPlay theme: configured to report as " + theme.encoded()
                        + " (legacy parameters 5/6; verified K5 format)");
            }
            refreshStart(mod, app, registry);
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void clearStart() {
        if (loaded) NativeIdentity.startSession(new byte[0]);
        lastStart = null;
    }

    private static void notice(ModContext mod, String status) {
        if (!status.equals(lastStartNotice)) {
            mod.log("CarPlayStart: " + status);
            lastStartNotice = status;
        }
    }

    private static synchronized void refreshStart(ModContext mod, LoadedApp app, IdentityRegistry registry) {
        if (!loaded || !armed) return;
        CarPlayIdentification.ThemeIdentity theme = registry.theme();
        if (!mod.enabled() || theme == null || !registry.themeSessionStart()) {
            if (lastStart != null) clearStart();
            notice(mod, "extension not requested");
            return;
        }
        try {
            byte[] msg = StartSession.build(app.context, theme);
            if (msg == null) {
                if (lastStart != null) clearStart();
                notice(mod, "waiting for verified K5 receiver and hotspot; stock startup retained");
                return;
            }
            if (java.util.Arrays.equals(lastStart, msg)) return;
            int result = NativeIdentity.startSession(msg);
            if (result != 0) {
                clearStart();
                notice(mod, "native session extension refused (" + result + "); stock startup retained");
                return;
            }
            lastStart = msg;
            notice(mod, "armed 0x4300/0x4301 (" + msg.length + " bytes; contents not logged)");
        } catch (Throwable failure) {
            try { clearStart(); } catch (Throwable ignored) { lastStart = null; }
            notice(mod, "extension unavailable (" + failure.getClass().getSimpleName() + "); stock startup retained");
        }
    }

    private static synchronized void watchStart(final ModContext mod, final LoadedApp app, final IdentityRegistry registry) {
        if (watcherStarted) return;
        watcherStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int offers = 0, sends = 0;
                try {
                    while (mod.enabled()) {
                        refreshStart(mod, app, registry);
                        synchronized (IdentityInstaller.class) {
                            if (loaded) {
                                int o = NativeIdentity.startSessionStatus(0), n = NativeIdentity.startSessionStatus(1);
                                if (o != offers || n != sends) {
                                    mod.log("CarPlayStart: availability offers=" + o + " answers attempted=" + n
                                            + " last result=" + NativeIdentity.startSessionStatus(2));
                                    offers = o; sends = n;
                                }
                            }
                        }
                        Thread.sleep(2000);
                    }
                } catch (InterruptedException ended) { Thread.currentThread().interrupt(); }
                catch (Throwable error) { mod.log("CarPlayStart: watcher stopped (" + error.getClass().getSimpleName() + ")"); }
                finally {
                    synchronized (IdentityInstaller.class) {
                        try { clearStart(); } catch (Throwable ignored) {}
                        watcherStarted = false;
                    }
                }
            }
        }, "carplay-start-session");
        t.setDaemon(true);
        t.start();
    }

    private static void failure(ModContext mod, String message, Throwable t) {
        if (t instanceof NativeFailure && ((NativeFailure) t).code == -21)
            mod.log(message + ": native gate not ready; will retry before identification");
        else mod.error(message, t);
    }

    private static void enableGroups(ClassLoader cl) throws Exception {
        Points.field(cl, "iap2.init_info.use_vehicle_information").setInt(null, 1);
        Points.field(cl, "iap2.init_info.use_vehicle_status").setInt(null, 1);
    }
}
