package hmgapi.mods.wireless;

import java.util.LinkedHashMap;
import java.util.Map;

import hmgapi.mods.wireless.api.CarPlayIdentification;

/** Process-local, validated contributions consumed by the native serializer. */
final class IdentityRegistry implements CarPlayIdentification {
    interface Logger { void log(String message); }
    private final Map<String, VehicleIdentity> vehicles = new LinkedHashMap<String, VehicleIdentity>();
    private final Map<String, ThemeIdentity> themes = new LinkedHashMap<String, ThemeIdentity>();
    private Runnable changed;
    private boolean sessionStart;
    private Logger logger;
    private long generation;
    private boolean suspended;
    private IdentityStatus status=new IdentityStatus(0,Readiness.UNAVAILABLE,"no contributions");

    private NegotiationStatus negotiation=new NegotiationStatus(0,false,false,false,false,false,false);
    @Override public synchronized IdentityStatus identityStatus() { return status; }
    @Override public synchronized NegotiationStatus negotiationStatus() { return negotiation; }

    synchronized boolean reportNegotiation(long expected,int identity,int capabilities,boolean armed) {
        if(expected!=generation || suspended)return false;
        boolean requested=themeSessionStart();
        boolean active=requested && armed;
        negotiation=new NegotiationStatus(generation,!vehicles.isEmpty() && (identity&5)==5,
                !themes.isEmpty() && (identity&2)!=0,requested,active,
                active && (capabilities&1)!=0,active && (capabilities&2)!=0);
        return true;
    }

    /** Immediately revoke session evidence before a native replacement or withdrawal. */
    synchronized boolean clearSessionEvidence(long expected) {
        if(expected!=generation || suspended)return false;
        negotiation=new NegotiationStatus(generation,negotiation.vehicleBuilt,negotiation.themeBuilt,
                themeSessionStart(),false,false,false);
        return true;
    }

    static final class Snapshot {
        final long generation;
        final VehicleIdentity vehicle;
        final ThemeIdentity theme;
        final boolean start;
        Snapshot(long generation, VehicleIdentity vehicle, ThemeIdentity theme, boolean start) {
            this.generation=generation; this.vehicle=vehicle; this.theme=theme; this.start=start;
        }
    }
    synchronized Snapshot snapshot() {
        VehicleIdentity v=vehicle();
        if(v!=null) v=new VehicleIdentity(v.displayName,v.engineType,v.connectors);
        return new Snapshot(generation,v,theme(),themeSessionStart());
    }

    private void invalidate() {
        generation++;
        negotiation=new NegotiationStatus(generation,false,false,!suspended && themeSessionStart(),false,false,false);
        status=new IdentityStatus(generation,suspended || (vehicles.isEmpty() && themes.isEmpty())
                ? Readiness.UNAVAILABLE : Readiness.REGISTERED,suspended?"identity suspended":"contributions changed");
    }

    /** Preserve contributions but invalidate all evidence across lifecycle transitions. */
    synchronized void setSuspended(boolean value) {
        if(suspended==value)return;
        suspended=value;
        invalidate();
    }

    /** A delayed native callback cannot mark a newer contribution as active. */
    synchronized boolean report(long expected, Readiness readiness, String reason) {
        if(expected!=generation || suspended) return false;
        status=new IdentityStatus(generation,readiness,reason);
        if(readiness==Readiness.FAILED || readiness==Readiness.UNAVAILABLE || readiness==Readiness.PREPARED)
            negotiation=new NegotiationStatus(generation,false,false,themeSessionStart(),false,false,false);
        return true;
    }

    @Override
    public void registerVehicle(String modId, VehicleIdentity vehicle) {
        checkModId(modId);
        log("API request from " + modId + ": register vehicle identity");
        if (vehicle == null) throw new IllegalArgumentException("vehicle");
        Runnable notify;
        synchronized (this) {
            if (!vehicles.isEmpty() && !vehicles.containsKey(modId))
                throw new IllegalStateException("CarPlay vehicle identity already belongs to " + vehicles.keySet());
            VehicleIdentity candidate=new VehicleIdentity(vehicle.displayName,vehicle.engineType,vehicle.connectors);
            if(sameVehicle(vehicles.get(modId),candidate))return;
            vehicles.put(modId,candidate); invalidate(); notify = changed;
        }
        if (notify != null) notify.run();
    }

    @Override
    public void removeVehicle(String modId) {
        checkModId(modId);
        log("API request from " + modId + ": remove vehicle identity");
        Runnable notify;
        synchronized (this) {
            if(vehicles.remove(modId)==null)return;
            invalidate(); notify = changed;
        }
        if (notify != null) notify.run();
    }

    @Override
    public void registerTheme(String modId, ThemeIdentity theme) {
        registerTheme(modId, theme, false);
    }

    @Override
    public void registerTheme(String modId, ThemeIdentity theme, boolean start) {
        checkModId(modId);
        log("API request from " + modId + ": register theme identity");
        if (theme == null) throw new IllegalArgumentException("theme");
        Runnable notify;
        synchronized (this) {
            if (!themes.isEmpty() && !themes.containsKey(modId))
                throw new IllegalStateException("CarPlay theme identity already belongs to " + themes.keySet());
            ThemeIdentity previous=themes.get(modId);
            if(previous!=null && previous.encoded().equals(theme.encoded()) && sessionStart==start)return;
            themes.put(modId, theme); sessionStart = start; invalidate(); notify = changed;
        }
        if (notify != null) notify.run();
    }

    @Override
    public void removeTheme(String modId) {
        checkModId(modId);
        log("API request from " + modId + ": remove theme identity");
        Runnable notify;
        synchronized (this) {
            if(themes.remove(modId)==null)return;
            sessionStart=false; invalidate(); notify = changed;
        }
        if (notify != null) notify.run();
    }

    /** Exactly one vehicle identity may win; ambiguity is an installation/configuration error. */
    synchronized VehicleIdentity vehicle() {
        if (vehicles.isEmpty()) return null;
        if (vehicles.size() != 1)
            throw new IllegalStateException("conflicting CarPlay vehicle identity contributors: " + vehicles.keySet());
        return vehicles.values().iterator().next();
    }

    synchronized ThemeIdentity theme() {
        if (themes.isEmpty()) return null;
        if (themes.size() != 1)
            throw new IllegalStateException("conflicting CarPlay theme contributors: " + themes.keySet());
        return themes.values().iterator().next();
    }

    synchronized boolean themeSessionStart() { return !themes.isEmpty() && sessionStart; }

    synchronized void onChange(Runnable listener) { changed = listener; }

    synchronized void logger(Logger value) { logger = value; }

    private void log(String message) {
        Logger value;
        synchronized (this) { value = logger; }
        if (value != null) value.log(message);
    }

    private static boolean sameVehicle(VehicleIdentity a,VehicleIdentity b) {
        if(a==null || !a.displayName.equals(b.displayName) || a.engineType!=b.engineType
                || a.connectors.length!=b.connectors.length)return false;
        for(int i=0;i<a.connectors.length;i++)
            if(a.connectors[i].type!=b.connectors[i].type
                    || a.connectors[i].maximumPowerW!=b.connectors[i].maximumPowerW)return false;
        return true;
    }

    private static void checkModId(String modId) {
        if (modId == null || modId.length() == 0 || modId.length() > 128 || modId.indexOf(0) >= 0)
            throw new IllegalArgumentException("modId");
    }
}
