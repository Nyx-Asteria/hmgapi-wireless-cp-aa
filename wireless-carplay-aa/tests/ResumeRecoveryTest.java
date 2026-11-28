package hmgapi.mods.wireless;

public final class ResumeRecoveryTest {
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        ResumeRecovery r=new ResumeRecovery();
        check(!r.pending() && !r.canAttempt(),"initially idle");
        r.failed(-21);
        check(!r.pending(),"unrelated error does not start recovery");
        r.begin();
        for(int i=0;i<5;i++){
            check(r.canAttempt(),"attempt "+i);
            r.failed(-21);
            r.attempted();
        }
        check(r.pending() && !r.canAttempt(),"bounded exhaustion");
        r.stop();
        check(!r.pending(),"stop after exhaustion");
        r.begin();
        r.failed(-15);
        r.attempted();
        check(!r.pending() && !r.canAttempt(),"permanent failure stops");
        r.begin();
        r.failed(-21);
        r.attempted();
        r.stop();
        check(!r.pending(),"successful arm or suspension stops");
        r.begin();
        for(int i=0;i<5;i++){check(r.canAttempt(),"fresh budget");r.attempted();}
        check(!r.canAttempt(),"new cycle bounded");
        System.out.println("PASS bounded resume recovery policy");
    }
}
