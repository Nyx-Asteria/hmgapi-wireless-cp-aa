package hmgapi.mods.wireless.dipo;

import java.util.concurrent.atomic.AtomicInteger;

/** Invalidates queued attachment requests when a newer handover or teardown occurs. */
final class AttachGeneration {
    private final AtomicInteger generation = new AtomicInteger();
    int advance() { return generation.incrementAndGet(); }
    boolean current(int token, boolean client, boolean usb) {
        return token == generation.get() && client && !usb;
    }
}
