package hmgapi.mods.wireless;

public final class StartWithdrawalTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError("withdrawal state");}
    public static void main(String[] args){
        StartWithdrawal w=new StartWithdrawal();
        check(!w.pending());
        w.setPending(true);
        check(w.clear(()->-15)==-15 && w.pending());
        try {w.clear(()->{throw new IllegalStateException("fixture");});throw new AssertionError("expected failure");}
        catch(IllegalStateException expected){check(w.pending());}
        check(w.clear(()->0)==0 && !w.pending());
        check(w.clear(()->-18)==-18 && w.pending());
        w.setPending(false); // confirmed native configure
        check(!w.pending());
        System.out.println("PASS native withdrawal retry state");
    }
}
