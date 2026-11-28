package hmgapi.mods.wireless;

/** Caller serializes access. Native uncertainty persists until an explicit acknowledgement. */
final class StartWithdrawal {
    interface NativeClear { int run(); }
    private boolean pending;
    boolean pending(){return pending;}
    void setPending(boolean value){pending=value;}
    int clear(NativeClear nativeClear){
        pending=true;
        int rc=nativeClear.run();
        if(rc==0)pending=false;
        return rc;
    }
}
