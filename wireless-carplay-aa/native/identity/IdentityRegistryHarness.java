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
        registry.removeTheme("fixture.foreign");
        registry.removeVehicle("fixture.foreign");
        check(registry.identityStatus().generation==stable && changes[0]==0
                && registry.identityStatus().readiness==CarPlayIdentification.Readiness.ADVERTISED,"foreign removal is a no-op");
        registry.registerTheme("fixture.theme",new CarPlayIdentification.ThemeIdentity("THEME1",985,"19.0"),true);
        check(registry.identityStatus().generation!=stable && changes[0]==1
                && registry.identityStatus().readiness==CarPlayIdentification.Readiness.REGISTERED,"session opt-in change invalidates readiness");
        long enabledGeneration=registry.identityStatus().generation;
        registry.setSuspended(true);
        long disabledGeneration=registry.identityStatus().generation;
        check(disabledGeneration!=enabledGeneration && registry.identityStatus().readiness==CarPlayIdentification.Readiness.UNAVAILABLE,"disable invalidates readiness");
        check(!registry.report(enabledGeneration,CarPlayIdentification.Readiness.ADVERTISED,"stale"),"old evidence rejected");
        check(!registry.report(disabledGeneration,CarPlayIdentification.Readiness.ARMED,"disabled"),"disabled reports rejected");
        check(registry.snapshot().theme!=null && registry.snapshot().vehicle!=null && registry.snapshot().start,"contributions retained");
        registry.setSuspended(true);
        check(registry.identityStatus().generation==disabledGeneration,"repeated disable idempotent");
        registry.setSuspended(false);
        check(registry.identityStatus().generation!=disabledGeneration && registry.identityStatus().readiness==CarPlayIdentification.Readiness.REGISTERED,"resume requires new evidence");
        IdentityRegistry.Snapshot snapshot=registry.snapshot();
        check(snapshot.generation==registry.identityStatus().generation && snapshot.vehicle!=null && snapshot.theme!=null,"atomic snapshot");
        registry.removeTheme("fixture.theme");
        check(snapshot.theme!=null && snapshot.generation!=registry.identityStatus().generation,"old snapshot remains distinct");
        long removed=registry.identityStatus().generation;
        int notified=changes[0];
        registry.removeTheme("fixture.theme");
        check(registry.identityStatus().generation==removed && changes[0]==notified,"repeated withdrawal is a no-op");
        registry.removeVehicle("fixture.k5");
        check(registry.identityStatus().readiness==CarPlayIdentification.Readiness.UNAVAILABLE
                && changes[0]==notified+1,"last owner withdrawal clears readiness");
        // Exact CPUL RC11 consumer tuple, reviewed at 554ec88f.
        CarPlayIdentification.ThemeIdentity cpul=new CarPlayIdentification.ThemeIdentity("44C9FA31EC",985L,"19.0");
        registry.registerTheme("fixture.cpul",cpul,true);
        IdentityRegistry.Snapshot cpulSnapshot=registry.snapshot();
        check("44C9FA31EC:985:19.0".equals(cpulSnapshot.theme.encoded()) && cpulSnapshot.start,"CPUL tuple and StartSession opt-in");
        check(registry.identityStatus().readiness==CarPlayIdentification.Readiness.REGISTERED,"CPUL registration is not native activation");
        CarPlayIdentification.NegotiationStatus empty=registry.negotiationStatus();
        check(empty.startRequested && !empty.themeBuilt && !empty.startArmed,"registration not construction");
        check(registry.reportNegotiation(cpulSnapshot.generation,7,3,true),"current evidence accepted");
        CarPlayIdentification.NegotiationStatus observed=registry.negotiationStatus();
        check(observed.themeBuilt && !observed.vehicleBuilt && observed.startArmed
                && observed.startSendCapability && observed.startReceiveCapability,"independent theme evidence");
        check(registry.clearSessionEvidence(cpulSnapshot.generation),"immediate session invalidation");
        check(registry.negotiationStatus().themeBuilt && !registry.negotiationStatus().startArmed
                && !registry.negotiationStatus().startReceiveCapability,"withdrawal preserves theme only");
        check(registry.reportNegotiation(cpulSnapshot.generation,2,3,false),"withdrawal report accepted");
        check(!registry.negotiationStatus().startSendCapability && !registry.negotiationStatus().startArmed,"withdrawal masks capability evidence");
        check(registry.reportNegotiation(cpulSnapshot.generation,2,3,true),"session rearmed");
        check(registry.report(cpulSnapshot.generation,CarPlayIdentification.Readiness.FAILED,"fixture"),"failure report");
        check(!registry.negotiationStatus().themeBuilt && !registry.negotiationStatus().startArmed,"failure invalidates evidence");
        registry.removeTheme("fixture.cpul");
        check(!registry.clearSessionEvidence(cpulSnapshot.generation),"stale withdrawal rejected");
        check(registry.identityStatus().readiness==CarPlayIdentification.Readiness.UNAVAILABLE,"CPUL withdrawal");
        check(!registry.reportNegotiation(cpulSnapshot.generation,7,3,true),"stale evidence rejected");
        check(!registry.negotiationStatus().themeBuilt && !registry.negotiationStatus().startRequested
                && registry.negotiationStatus().generation==registry.identityStatus().generation,"withdrawal invalidates snapshot");
        System.out.println("PASS mock mods compose independent vehicle and theme contributions");
    }

    private static void check(boolean value, String what) {
        if (!value) throw new AssertionError(what);
    }
}
