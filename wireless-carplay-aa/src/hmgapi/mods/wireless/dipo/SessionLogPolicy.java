package hmgapi.mods.wireless.dipo;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Host-testable policy. Never pass secret-bearing protocol bodies to this interface. */
final class SessionLogPolicy {
    enum Level { ERROR, WARN, INFO, DEBUG, TRACE }
    enum Category { CORE, BLUETOOTH, WIFI, IAP2, AUDIO, IDENTITY, TRANSPORT, NOW_PLAYING, NATIVE, LATENCY, PACKET_METADATA, PROJECTION_METADATA }
    private static final int MAX_KEYS = 256;
    private static final int MAX_MESSAGE = 2048;
    private static final long WINDOW_MS = 10000;
    private static final Pattern SECRET = Pattern.compile("(?i)(authorization|cookie|password|passwd|secret|token|psk|session[ _-]?key|pairing[ _-]?(?:id|identifier)|device[ _-]?(?:id|identifier)|serial[ _-]?number|private[ _-]?key|ssid|passphrase|\\bvin\\b|https?://|mac[ _-]?address|bluetooth[ _-]?address)");
    private final EnumSet<Category> diagnostics;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<String, Long>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_KEYS;
        }
    };
    private long suppressed;

    SessionLogPolicy(EnumSet<Category> diagnostics) {
        this.diagnostics = diagnostics.clone();
    }

    static EnumSet<Category> diagnosticCategories(boolean detail,boolean packets) {
        EnumSet<Category> categories=EnumSet.noneOf(Category.class);
        if(detail)categories.addAll(EnumSet.of(Category.BLUETOOTH,Category.WIFI,Category.IAP2,Category.AUDIO,Category.IDENTITY,Category.TRANSPORT,Category.NOW_PLAYING,Category.NATIVE,Category.LATENCY));
        if(packets)categories.addAll(EnumSet.of(Category.PACKET_METADATA,Category.PROJECTION_METADATA));
        return categories;
    }

    boolean enabled(Level level, Category category) {
        if (level == Level.ERROR || level == Level.WARN) return true;
        if (category == Category.PACKET_METADATA || category == Category.PROJECTION_METADATA)
            return diagnostics.contains(category);
        return level == Level.INFO || diagnostics.contains(category);
    }

    /** Conservative whole-message withholding instead of attempting to parse arbitrary credentials. */
    static String sanitize(String message) {
        if (message == null) return "[null]";
        if (message.length() > 8192 || SECRET.matcher(message).find()) return "[sensitive message withheld]";
        String safe = message.replaceAll("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])", "[address withheld]")
            .replaceAll("(?i)(?<![0-9a-z:])(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{0,4}::(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{0,4})(?:%[0-9a-z_.-]+)?(?![0-9a-z:])", "[address withheld]")
            .replaceAll("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])", "[address withheld]")
            .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return safe.length() > MAX_MESSAGE ? safe.substring(0, MAX_MESSAGE) + " [truncated]" : safe;
    }

    /** Metadata only: do not decode, stringify or persist packet bodies. */
    static String packet(String lane, int kind, int type, int bytes) {
        return lane + " kind=" + kind + " type=" + type + " bytes=" + bytes;
    }

    synchronized String accept(Level level, Category category, String message, long elapsedMs) {
        if (!enabled(level, category)) { suppressed++; return null; }
        String safe = sanitize(message);
        // Errors and warnings always survive repetition filtering.
        if (level != Level.ERROR && level != Level.WARN) {
            String key = level.name() + '/' + category.name() + '/' + safe;
            Long last = seen.get(key);
            if (last != null && elapsedMs >= last && elapsedMs - last < WINDOW_MS) {
                suppressed++;
                return null;
            }
            seen.put(key, elapsedMs);
        }
        return safe;
    }

    synchronized long suppressed() { return suppressed; }
    synchronized void reset() { seen.clear(); suppressed = 0; }
}
