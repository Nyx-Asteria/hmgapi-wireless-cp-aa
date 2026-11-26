package hmgapi.mods.wireless.dipo;

public final class AttachGenerationTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        AttachGeneration policy = new AttachGeneration();
        int first = policy.advance();
        check(policy.current(first, true, false), "active wireless attachment");
        check(!policy.current(first, false, false), "inactive client");
        check(!policy.current(first, true, true), "USB active");
        int second = policy.advance();
        check(!policy.current(first, true, false), "superseded attachment");
        check(policy.current(second, true, false), "current reattachment");
        policy.advance();
        check(!policy.current(second, true, false), "teardown invalidates queued attachment even before client clears");
        int third = policy.advance();
        check(!policy.current(first, true, false), "old attachment cannot revive after reconnect");
        check(policy.current(third, true, false), "new session allowed");
        System.out.println("AttachGeneration: 8 policy checks passed");
    }
}
