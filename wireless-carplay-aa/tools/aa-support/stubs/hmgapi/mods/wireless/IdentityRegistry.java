package hmgapi.mods.wireless;

final class IdentityRegistry {
    interface Logger { void log(String message); }
    void logger(Logger value) {}
    void registerVehicle(String id, hmgapi.mods.wireless.api.CarPlayIdentification.VehicleIdentity value) {}
}
