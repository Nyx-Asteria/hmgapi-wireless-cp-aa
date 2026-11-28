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
        check(AttachGeneration.recoverable(1,1,"phone","phone",false,false,false,false,true), "idle SPP recovery");
        check(!AttachGeneration.recoverable(1,2,"phone","phone",false,false,false,false,true), "stale delayed recovery");
        check(!AttachGeneration.recoverable(1,1,"phone","other",false,false,false,false,true), "different phone");
        check(!AttachGeneration.recoverable(1,1,null,null,false,false,false,false,true), "missing address");
        check(!AttachGeneration.recoverable(1,1,"phone","phone",true,false,false,false,true), "USB takeover");
        check(!AttachGeneration.recoverable(1,1,"phone","phone",false,true,false,false,true), "new BT attachment");
        check(!AttachGeneration.recoverable(1,1,"phone","phone",false,false,true,false,true), "new client");
        check(!AttachGeneration.recoverable(1,1,"phone","phone",false,false,false,true,true), "new projection");
        check(!AttachGeneration.recoverable(1,1,"phone","phone",false,false,false,false,false), "disabled");
        System.out.println("AttachGeneration: 17 policy checks passed");
    }
}
