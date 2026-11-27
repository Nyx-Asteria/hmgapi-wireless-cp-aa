package hmgapi.mods.wireless;

import hmgapi.mods.wireless.api.CarPlayIdentification;

/** Host fixture proving independent mods can own vehicle and theme contributions. */
public final class IdentityRegistryHarness {
    public static void main(String[] args) {
        IdentityRegistry registry = new IdentityRegistry();
        final StringBuilder log = new StringBuilder();
        registry.logger(new IdentityRegistry.Logger() {
            @Override public void log(String message) { log.append(message).append('\n'); }
        });
        CarPlayIdentification.Connector[] connectors = {
            new CarPlayIdentification.Connector(CarPlayIdentification.VehicleIdentity.CONNECTOR_CCS, 235000),
            new CarPlayIdentification.Connector(CarPlayIdentification.VehicleIdentity.CONNECTOR_AC, 10900)
        };
        registry.registerVehicle("fixture.ev", new CarPlayIdentification.VehicleIdentity("Fixture EV",
                CarPlayIdentification.VehicleIdentity.ENGINE_ELECTRIC, connectors));
        registry.registerTheme("fixture.theme", new CarPlayIdentification.ThemeIdentity("THEME1", 985, "19.0"));
        check("Fixture EV".equals(registry.vehicle().displayName), "vehicle contribution");
        check("THEME1:985:19.0".equals(registry.theme().encoded()), "theme contribution");
        try {
            registry.registerTheme("fixture.conflict", new CarPlayIdentification.ThemeIdentity("OTHER", 1, "19.0"));
            throw new AssertionError("second theme owner accepted");
        } catch (IllegalStateException expected) {}
        registry.removeTheme("fixture.theme");
        check(registry.theme() == null && registry.vehicle() != null, "independent removal");
        check(log.toString().contains("fixture.ev: register vehicle identity"), "vehicle request logged");
        check(log.toString().contains("fixture.theme: register theme identity"), "theme request logged");
        check(log.toString().contains("fixture.conflict: register theme identity"), "rejected request logged");
        check(log.toString().contains("fixture.theme: remove theme identity"), "removal request logged");
        registry.removeVehicle("fixture.ev");
        registry.registerVehicle("fixture.k5",new CarPlayIdentification.VehicleIdentity("Kia K5",
                CarPlayIdentification.VehicleIdentity.ENGINE_GASOLINE,new CarPlayIdentification.Connector[0]));
        check(registry.vehicle().connectors.length==0,"gasoline profile contains no charging connectors");
        try {
            new CarPlayIdentification.VehicleIdentity("Invalid gasoline",CarPlayIdentification.VehicleIdentity.ENGINE_GASOLINE,connectors);
            throw new AssertionError("gasoline connectors accepted");
        } catch(IllegalArgumentException expected){}
        try {
            new CarPlayIdentification.VehicleIdentity("Invalid EV",CarPlayIdentification.VehicleIdentity.ENGINE_ELECTRIC,new CarPlayIdentification.Connector[0]);
            throw new AssertionError("empty EV connectors accepted");
        } catch(IllegalArgumentException expected){}
        CarPlayIdentification.IdentityStatus before=registry.identityStatus();
        check(before.readiness==CarPlayIdentification.Readiness.REGISTERED,"registration not activation");
        check(registry.report(before.generation,CarPlayIdentification.Readiness.ARMED,"fixture"),"current generation accepted");
        registry.registerTheme("fixture.theme",new CarPlayIdentification.ThemeIdentity("THEME1",985,"19.0"));
        check(!registry.report(before.generation,CarPlayIdentification.Readiness.ADVERTISED,"stale"),"stale generation rejected");
        check(registry.identityStatus().readiness==CarPlayIdentification.Readiness.REGISTERED,"new contribution invalidates armed state");
        final int[] changes={0};
        registry.onChange(new Runnable(){@Override public void run(){changes[0]++;}});
        long stable=registry.identityStatus().generation;
        check(registry.report(stable,CarPlayIdentification.Readiness.ADVERTISED,"fixture"),"fixture advertisement");
        registry.registerTheme("fixture.theme",new CarPlayIdentification.ThemeIdentity("THEME1",985,"19.0"));
        registry.registerVehicle("fixture.k5",new CarPlayIdentification.VehicleIdentity("Kia K5",
                CarPlayIdentification.VehicleIdentity.ENGINE_GASOLINE,new CarPlayIdentification.Connector[0]));
        check(registry.identityStatus().generation==stable && changes[0]==0
                && registry.identityStatus().readiness==CarPlayIdentification.Readiness.ADVERTISED,"identical registration preserves readiness");
        registry.registerTheme("fixture.theme",new CarPlayIdentification.ThemeIdentity("THEME1",985,"19.0"),true);
        check(registry.identityStatus().generation!=stable && changes[0]==1
                && registry.identityStatus().readiness==CarPlayIdentification.Readiness.REGISTERED,"session opt-in change invalidates readiness");
        IdentityRegistry.Snapshot snapshot=registry.snapshot();
        check(snapshot.generation==registry.identityStatus().generation && snapshot.vehicle!=null && snapshot.theme!=null,"atomic snapshot");
        registry.removeTheme("fixture.theme");
        check(snapshot.theme!=null && snapshot.generation!=registry.identityStatus().generation,"old snapshot remains distinct");
        System.out.println("PASS mock mods compose independent vehicle and theme contributions");
    }

    private static void check(boolean value, String what) {
        if (!value) throw new AssertionError(what);
    }
}
