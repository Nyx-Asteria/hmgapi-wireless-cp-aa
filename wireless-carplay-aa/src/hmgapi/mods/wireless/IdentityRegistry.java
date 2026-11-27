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
    private IdentityStatus status=new IdentityStatus(0,Readiness.UNAVAILABLE,"no contributions");

    @Override public synchronized IdentityStatus identityStatus() { return status; }

    private void invalidate() {
        generation++;
        status=new IdentityStatus(generation,vehicles.isEmpty() && themes.isEmpty()
                ? Readiness.UNAVAILABLE : Readiness.REGISTERED,"contributions changed");
    }

    /** A delayed native callback cannot mark a newer contribution as active. */
    synchronized boolean report(long expected, Readiness readiness, String reason) {
        if(expected!=generation) return false;
        status=new IdentityStatus(generation,readiness,reason);
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
            vehicles.put(modId, vehicle); invalidate(); notify = changed;
        }
        if (notify != null) notify.run();
    }

    @Override
    public void removeVehicle(String modId) {
        checkModId(modId);
        log("API request from " + modId + ": remove vehicle identity");
        Runnable notify;
        synchronized (this) { if(vehicles.remove(modId)!=null) invalidate(); notify = changed; }
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
            themes.put(modId, theme); sessionStart = start; invalidate(); notify = changed;
        }
        if (notify != null) notify.run();
    }

    @Override
    public void removeTheme(String modId) {
        checkModId(modId);
        log("API request from " + modId + ": remove theme identity");
        Runnable notify;
        synchronized (this) { if (themes.remove(modId) != null) { sessionStart = false; invalidate(); } notify = changed; }
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

    private static void checkModId(String modId) {
        if (modId == null || modId.length() == 0 || modId.length() > 128 || modId.indexOf(0) >= 0)
            throw new IllegalArgumentException("modId");
    }
}
