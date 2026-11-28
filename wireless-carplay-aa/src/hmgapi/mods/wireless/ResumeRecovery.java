package hmgapi.mods.wireless;

/** Bounded automatic resume policy. Caller serializes access. */
final class ResumeRecovery {
    private static final int MAX_ATTEMPTS=5;
    private boolean pending;
    private int attempts;
    void begin(){pending=true;attempts=0;}
    void stop(){pending=false;attempts=0;}
    boolean pending(){return pending;}
    boolean canAttempt(){return pending && attempts<MAX_ATTEMPTS;}
    void attempted(){if(pending && attempts<MAX_ATTEMPTS)attempts++;}
    void failed(int code){if(code!=-21)stop();}
}
