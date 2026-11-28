package hmgapi.mods.wireless.dipo;

import java.util.concurrent.atomic.AtomicInteger;

/** Invalidates queued attachment requests when a newer handover or teardown occurs. */
final class AttachGeneration {
    private final AtomicInteger generation = new AtomicInteger();
    int advance() { return generation.incrementAndGet(); }
    boolean current(int token, boolean client, boolean usb) {
        return token == generation.get() && client && !usb;
    }
    static boolean recoverable(int token, int current, String address, String activeAddress,
            boolean usb, boolean session, boolean client, boolean wireless, boolean enabled) {
        return token == current && address != null && address.equals(activeAddress)
                && !usb && !session && !client && !wireless && enabled;
    }
}
