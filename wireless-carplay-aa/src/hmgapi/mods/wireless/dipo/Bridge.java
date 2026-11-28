package hmgapi.mods.wireless.dipo;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceStatusListener;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import java.lang.reflect.Method;
import java.util.ArrayList;

import hmgapi.hook.Points;
import android.net.wifi.WifiConfiguration;

/**
 * Wireless CarPlay's Bluetooth bridge, in DiPO (from the bridge build, com.ev6.cpwl).
 *
 * An iPhone pairs over Bluetooth; wireless CarPlay starts with iAP2 over Bluetooth
 * (RFCOMM/SPP), which the iAP2 server here can't do by itself. The bridge carries it:
 * SPP data from Broadcom's Bluetooth manager goes into the iAP2 server as transport data
 * (Iap2SvcTransportReadDone), and what the server sends back (notification 80) goes out
 * over SPP. The iAP2 server opens the session for BT_ATTACHED (HMG-API's hook there, part
 * "bridge"). Once the phone has joined the hotspot and the CarPlay session runs, iAP2
 * moves into the CarPlay session's own channel: bytes the phone sends there
 * (oniAPSendMessage) go to the server, and what the server sends for it
 * (notification 81) goes out through the CarPlay library.
 *
 * HMG-API binds the iAP2 server and hooks Broadcom's manager (a library), and only
 * observes DiPO's CarPlay callbacks; DiPO's own iAP2 listener is told to ignore what
 * belongs to the bridge's sessions (Dipo's hooks).
 *
 * persist.ev6.cpwl.enable=0 turns it off; persist.ev6.cpwl.handover=created moves iAP2
 * into the CarPlay channel as soon as the session is created (default: on its first data).
 *
 * Re-attach (Reattach): when the CarPlay channel's iAP2 link fails while the CarPlay
 * session is up (the server's ACTION_IAP2_CONNECTFAIL after LimitMaxResend, its session
 * ending, or a CarPlay-channel send blocked in the library for STUCK_MS), the handover is
 * asked for again with a backoff; a re-attach counts only once the server reports the new
 * session ready (ACTION_IAP2_CONNECTSUCCESS / identification accepted), then route guidance
 * and Now Playing are asked for again. persist.ev6.cpwl.reattach=0 turns that off;
 * persist.ev6.cpwl.stuck_ms=0 turns off only the blocked-send trigger.
 * CarPlay-channel sends (CpTx): a sender thread + queue per link; a re-attach or the
 * session's end abandons the current one (never waits for a blocked native call) and the
 * next link gets a fresh one. Every send that blocks more than 2 s is logged.
 */
final class Bridge {
    static final String ACTION_ATTACH = "com.ev6.cpwl.BT_ATTACHED", ACTION_DETACH = "com.ev6.cpwl.BT_DETACHED";
    static final String IAP2_PKG = "com.tcc.iap2server";
    static final int APPLE_VENDOR_ID = 76;
    /** iAP2 components: 1 USB, 4 Bluetooth CarPlay, 5 the CarPlay session's channel */
    static final int COMPONENT_USB = 1, COMPONENT_BT_CARPLAY = 4, COMPONENT_CARPLAY_CLIENT = 5;
    static final int LISTENER_EXTERNAL_BT_TRANSPORT = 1024, LISTENER_EXTERNAL_CARPLAY_TRANSPORT = 2048;
    static final int NOTI_IDENTIFICATION_ACCEPTED = 0, NOTI_TRANSPORT_IDENTIFIER = 24;
    static final int NOTI_BT_TRANSPORT_DATA = 80, NOTI_CARPLAY_CLIENT_TRANSPORT_DATA = 81, OBJ_BLOB = 11;
    static final int MAX_PENDING = 64;
    static final long SPP_CONNECT_DELAY_MS = 3000;
    static final int SPP_DISCONNECTED = 0, SPP_CONNECTED = 2, EVENT_BTCM_CONNECTED = 2;
    /** the iAP2 server's broadcast when a session's link fails (Iap2Const.ACTION_IAP2_CONNECTFAIL), and its component extra */
    static final String ACTION_IAP2_CONNECTFAIL = "com.tcc.iap2.connectedfail", EXTRA_IAP2_COMPONENT = "com.telechips.android.iap2.component";
    /** the iAP2 server's broadcast once a session is identified and authenticated (Iap2Authentication.sendAfterAuthenticationSuccess) */
    static final String ACTION_IAP2_CONNECTSUCCESS = "com.tcc.iap2.connectedsuccess";
    /** a CarPlay-channel send blocked longer than this is logged */
    static final long SEND_SLOW_MS = 2000;
    /** ... and longer than this (default) counts as the link lost: re-attach (persist.ev6.cpwl.stuck_ms, 0 = off) */
    static final long STUCK_MS = 5000;

    static volatile String sAddr;
    static volatile HfDeviceBTCMManager sBtcm;
    static volatile Object sCp;
    static volatile boolean sCpClient, sCpConnected, sCpWireless, sSession, sUsb;
    static volatile IIap2Service sIap2;
    /** the phone's Bluetooth address from its last iAP2 session (DiPO's first-connect notice) */
    static volatile String sLastBtId;
    /** the serial the CarPlay channel's session was handed over with (a re-attach asks with the same) */
    static volatile String sCpSerial;
    static final Reattach sReattach = new Reattach(new ReattachHost());
    static ClassLoader cl;
    static Context context;
    private static Handler sHandler;
    private static Method sNativeSend;
    private static final ArrayList<byte[]> sPendingIn = new ArrayList<byte[]>();
    // Process-lifetime count; guarded by sPendingIn.
    private static long diagnosticRxOverflow;
    private static long diagnosticRxAttempts, diagnosticRxFailures, diagnosticRxTotalMs, diagnosticRxMaxMs;
    private static BtListener sBt;
    private static TxListener sTx;
    /** CarPlay-channel sends: one sender thread + queue per link (a re-attach abandons the dead link's) */
    static final CpTx sCpTx = new CpTx(new TxHost(), new NativeSink(), "hmgapi-cp-tx");
    /** the blocked send last logged, and last treated as a lost link (its start time) */
    private static volatile long sTxReported, sTxStuck;
    private static boolean sTxWatch;

    private Bridge() {}

    static void log(String m) {
        Dipo.log("bridge: " + m);
    }

    static void error(String m, Throwable t) {
        Dipo.error("bridge: " + m, t);
    }

    /** DiPO registered its listener with Broadcom's manager (the manager is connected): ours goes next to it */
    static void onBtcmReady(HfDeviceBTCMManager m) {
        if (m == null) return;
        try {
            sBtcm = m;
            synchronized (Bridge.class) {
                if (sBt == null) sBt = new BtListener();
            }
            m.registerHfDeviceStatusListener(sBt);
            log("BTCM listener registered");
            BluetoothDevice d = m.IbtcmGetHfpConnectedDevice();
            if (d != null && isApple(d)) scheduleSppConnect(d.getAddress());
        } catch (Throwable t) {
            error("onBtcmReady", t);
        }
    }

    static boolean isOurs(Object listener) {
        return listener != null && listener == sBt;
    }

    /** the iAP2 server is bound: register for Bluetooth and CarPlay-channel transport data */
    static void onIap2Bound(IIap2Service s) {
        if (s == null) return;
        try {
            replaceReceiveService(s);
            synchronized (Bridge.class) {
                if (sTx == null) sTx = new TxListener();
            }
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_EXTERNAL_BT_TRANSPORT | LISTENER_EXTERNAL_CARPLAY_TRANSPORT);
            info.onSetListenerName("EV6_CPWL_BT");
            log("Iap2SvcInit(EV6_CPWL_BT, 0xc00) = " + s.Iap2SvcInit(sTx, info));
            flushPending();
        } catch (Throwable t) {
            error("onIap2Bound", t);
        }
    }

    static boolean enabled() {
        return !"0".equals(prop("persist.ev6.cpwl.enable", "1"));
    }

    /** a system property (android.os.SystemProperties isn't in the public SDK) */
    static String prop(String key, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("hmgapi-cpwl");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    static boolean isApple(BluetoothDevice d) {
        if (d == null) return false;
        try {
            Object v = BluetoothDevice.class.getMethod("getVendor").invoke(d);
            if (v instanceof Integer) return (Integer) v == APPLE_VENDOR_ID;
        } catch (Throwable t) {
            // no vendor id on this build: by name
        }
        try {
            String n = d.getName();
            return n != null && (n.contains("iPhone") || n.contains("iPad"));
        } catch (Throwable t) {
            return false;
        }
    }

    static void scheduleSppConnect(final String addr) {
        if (addr == null || !enabled() || sUsb || sSession || sCpWireless) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m == null || sUsb || sSession || sCpWireless || !enabled()) return;
                    if (m.IbtcmIsSppConnectedDevice(addr)) log("SPP already up for " + addr);
                    else log("IbtcmSppConnect(" + addr + ") = " + m.IbtcmSppConnect(addr));
                } catch (Throwable t) {
                    error("sppConnect", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    private static final java.util.concurrent.atomic.AtomicInteger btGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean currentBtAttach(int generation, String addr) {
        return btGeneration.get() == generation && sSession && addr.equals(sAddr)
                && !sUsb && !sCpClient && !sCpWireless && enabled();
    }

    private static synchronized void failBtAttach(int generation) {
        if (btGeneration.get() == generation) sSession = false;
    }

    private static synchronized void invalidateBtAttach() {
        btGeneration.incrementAndGet();
        sSession = false;
    }

    static synchronized void onSppUp(final String addr) {
        if (addr == null || sSession) return;
        sAddr = addr;
        if (sCpClient || sCpWireless || !enabled() || sUsb) return;
        sSession = true;
        final int generation = btGeneration.incrementAndGet();
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!currentBtAttach(generation, addr)) {
                        failBtAttach(generation);
                        return;
                    }
                    Context c = context;
                    if (c == null) {
                        log("no context, cannot start session");
                        failBtAttach(generation);
                        return;
                    }
                    // Keep the verified K5 connectivity AP startup sequence.
                    ensureHotspot(c);
                    if (!currentBtAttach(generation, addr)) {
                        failBtAttach(generation);
                        return;
                    }
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_BT_CARPLAY);
                    i.putExtra("serial", addr);
                    if (!currentBtAttach(generation, addr)) {
                        failBtAttach(generation);
                        return;
                    }
                    c.sendBroadcast(i);
                    log("BT_ATTACHED sent for " + addr);
                } catch (Throwable t) {
                    failBtAttach(generation);
                    error("onSppUp", t);
                }
            }
        });
    }

    static void onUsbGone() {
        final String addr = sAddr;
        if (addr == null || sSession) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m != null && !sUsb && !sSession && addr.equals(sAddr) && m.IbtcmIsSppConnectedDevice(addr)) onSppUp(addr);
                } catch (Throwable t) {
                    error("onUsbGone", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    private static volatile long diagnosticCreatedAt, diagnosticStartedAt;
    private static final AttachGeneration attachGeneration = new AttachGeneration();

    private static boolean currentAttach(int generation) {
        return attachGeneration.current(generation, sCpClient, sUsb);
    }

    static void onCpSessionCreated(Object cp) {
        diagnosticCreatedAt = now();
        diagnosticStartedAt = 0;
        if (cp != null) sCp = cp;
        log("CarPlay session created (usb=" + sUsb + " btAddr=" + sAddr + ")");
        if ("created".equals(prop("persist.ev6.cpwl.handover", "data")) && !sUsb && sAddr != null) {
            startCpClient("session created");
        }
    }

    static void onCpSessionStarted(int transport) {
        if (diagnosticStartedAt == 0) diagnosticStartedAt = now();
        log("CarPlay milestone session-start createdToStartMs="
                + (diagnosticCreatedAt == 0 ? -1 : diagnosticStartedAt - diagnosticCreatedAt));
        sCpWireless = transport == 1 && !sUsb;
        log("CarPlay session started transport=" + transport + " wireless=" + sCpWireless);
    }

    /** iAP2 bytes from the phone over the CarPlay channel: to the iAP2 server (buffered until its session is up) */
    static void onCpIapIn(Object cp, byte[] data, int len) {
        try {
            if (data == null || data.length == 0 || len <= 0) return;
            final long generation;
            synchronized (sPendingIn) {
                if (sUsb) return;
                generation = rxGeneration;
            }
            if (len >= data.length) len = data.length;
            byte[] b = new byte[len];
            System.arraycopy(data, 0, b, 0, len);
            acceptReceive(cp, b, generation);
        } catch (Throwable t) {
            error("onCpIapIn", t);
        }
    }

    // Generation is sampled before copying, then revalidated at admission.
    private static void acceptReceive(Object cp, byte[] packet, long generation) {
        synchronized (sPendingIn) {
            if (generation != rxGeneration || sUsb) return;
        }
        if (!sCpClient) startCpClient("iAP2 data from CarPlay channel", generation);
        synchronized (sPendingIn) {
            if (generation != rxGeneration || !sCpClient || sUsb) return;
            if (cp != null) sCp = cp;
            if (sPendingIn.size() >= MAX_PENDING) {
                diagnosticRxOverflow++;
                return;
            }
            sPendingIn.add(packet);
        }
        flushPending();
    }

    static void onCpSessionFinalized(int type) {
        attachGeneration.advance();
        try {
            int pendingCount;
            long rxOverflow, rxAttempts, rxFailures, rxTotalMs, rxMaxMs;
            synchronized (sPendingIn) {
                pendingCount = sPendingIn.size();
                rxOverflow = diagnosticRxOverflow;
                rxAttempts = diagnosticRxAttempts;
                rxFailures = diagnosticRxFailures;
                rxTotalMs = diagnosticRxTotalMs;
                rxMaxMs = diagnosticRxMaxMs;
            }
            long elapsed = diagnosticStartedAt == 0 ? -1 : now() - diagnosticStartedAt;
            log("CarPlay teardown snapshot type=" + type + " durationMs=" + elapsed
                    + " wireless=" + sCpWireless + " client=" + sCpClient
                    + " connected=" + sCpConnected + " rxPending=" + pendingCount
                    + " rxOverflowTotal=" + rxOverflow
                     + " rxAttemptsTotal=" + rxAttempts + " rxFailuresTotal=" + rxFailures
                     + " rxBinderTotalMs=" + rxTotalMs + " rxBinderMaxMs=" + rxMaxMs
                    + " " + sCpTx.diagnosticSnapshot());
            diagnosticStartedAt = 0;
            diagnosticCreatedAt = 0;
            log("CarPlay session finalized type=" + type + " cpClient=" + sCpClient);
            sReattach.sessionEnd();
            Dipo.rgSessionEnd();
            if (sCpClient) sCpTx.reset("CarPlay session ended", false);
            boolean client = sCpClient, wireless = sCpWireless;
            sCpWireless = false;
            synchronized (sPendingIn) {
                sCpClient = false;
                resetReceive();
            }
            if (client || wireless) {
                // The old Bluetooth attachment belongs to the ended projection.
                // Invalidate queued attach work before scheduling the existing
                // SPP-present recovery; do not initiate a new Bluetooth connection.
                invalidateBtAttach();
                log("CarPlay teardown: Bluetooth attachment invalidated");
            }
            if (client) sendDetach();
            if (client || wireless) onUsbGone();
        } catch (Throwable t) {
            error("onCpSessionFinalized", t);
        }
    }

    /** DiPO's iAP2 listener: ignore the Bluetooth and CarPlay-channel sessions ending while wireless CarPlay is up */
    static boolean dipoIgnoreDisconnect(int component) {
        boolean ignore = (sCpClient || sCpWireless) && (component == COMPONENT_BT_CARPLAY || component == COMPONENT_CARPLAY_CLIENT);
        if (ignore) log("DiPO: ignoring iAP2 disconnect comp=" + component + " (wireless CarPlay up)");
        return ignore;
    }

    /** DiPO's iAP2 listener: the CarPlay-channel session's identification isn't a new phone */
    static boolean dipoDropNoti(int what) {
        // the server sends identification accepted to the listeners only once the session is identified and authenticated
        if (what == NOTI_IDENTIFICATION_ACCEPTED && sCpClient && !sUsb) {
            // a re-attach under way counts; RgWatch only takes it once the channel's link is up (comp 5's, not comp 4's)
            comp5Ready("identification accepted", sCpConnected);
        }
        boolean drop = sCpClient && (what == NOTI_IDENTIFICATION_ACCEPTED || what == NOTI_TRANSPORT_IDENTIFIER);
        if (drop) log("DiPO: dropping iAP2 noti " + what + " from the CarPlay-channel session");
        return drop;
    }

    static void startCpClient(final String why) {
        final long receiveGeneration;
        synchronized (sPendingIn) { receiveGeneration = rxGeneration; }
        startCpClient(why, receiveGeneration);
    }

    private static void startCpClient(final String why, long receiveGeneration) {
        final String addr;
        final int generation;
        synchronized (sPendingIn) {
            if (receiveGeneration != rxGeneration || sCpClient || sUsb) return;
            sCpClient = true;
            sCpConnected = false;
            addr = sAddr;
            sCpSerial = addr;
            generation = attachGeneration.advance();
        }
        if (reattachEnabled()) sReattach.sessionUp();
        Dipo.rgSessionUp();
        txWatch();
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!currentAttach(generation)) return;
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    if (!currentAttach(generation)) return;
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested (" + why + ")");
                } catch (Throwable t) {
                    error("startCpClient", t);
                }
            }
        });
    }

    static boolean onCpClientConnected(long generation) {
        synchronized (sPendingIn) {
            if (generation != rxGeneration || !sCpClient || sUsb) return false;
            sCpConnected = true;
        }
        flushPending();
        synchronized (sPendingIn) {
            return generation == rxGeneration && sCpClient && !sUsb && sCpConnected;
        }
    }

    // Exactly one drain owner; callbacks can enqueue during Binder delivery.
    private static boolean rxDraining;
    private static long rxGeneration;

    // A new service must establish its own link before consuming wireless packets.
    private static void replaceReceiveService(IIap2Service service) {
        synchronized (sPendingIn) {
            if (sIap2 != null && sIap2 != service) resetReceive();
            sIap2 = service;
        }
    }

    private static void resetReceive() {
        synchronized (sPendingIn) {
            sCpConnected = false;
            sPendingIn.clear();
            rxGeneration++;
            rxDraining = false;
        }
    }

    private static void flushPending() {
        final long generation;
        synchronized (sPendingIn) {
            if (rxDraining || !sCpConnected || sIap2 == null || !sCpClient || sUsb) return;
            rxDraining = true;
            generation = rxGeneration;
        }
        while (true) {
            final byte[] packet;
            final IIap2Service service;
            synchronized (sPendingIn) {
                if (generation != rxGeneration) return;
                service = sIap2;
                if (!sCpClient || sUsb || !sCpConnected || service == null || sPendingIn.isEmpty()) {
                    rxDraining = false;
                    return;
                }
                packet = sPendingIn.remove(0);
            }
            long began = now();
            boolean failed = false;
            try {
                service.Iap2SvcTransportReadDone(packet, packet.length);
            } catch (Throwable t) {
                failed = true;
                error("ordered receive delivery", t);
            } finally {
                long elapsed = Math.max(0, now() - began);
                synchronized (sPendingIn) {
                    diagnosticRxAttempts++;
                    if (failed) diagnosticRxFailures++;
                    diagnosticRxTotalMs += elapsed;
                    diagnosticRxMaxMs = Math.max(diagnosticRxMaxMs, elapsed);
                }
            }
        }
    }

    private static Method nativeSend() {
        if (sNativeSend == null) sNativeSend = Points.method(cl, "dipo.carplay.native_iap_send");
        return sNativeSend;
    }

    /** iAP2 bytes from the server for the CarPlay channel: out through the CarPlay library (CpTx, the current link's sender) */
    static void writeCp(byte[] data) {
        final Object cp = sCp;
        if (cp == null || data == null || data.length == 0 || !sCpClient) return;
        sCpTx.send(cp, data.clone());
    }

    /** CpTx's native send: TccCarPlay_iAPSendMessage through DiPO's CarPlay class */
    static final class NativeSink implements CpTx.Sink {
        @Override
        public int send(Object cp, byte[] b, int len) throws Throwable {
            Object r = nativeSend().invoke(cp, b, len);
            return r instanceof Integer ? (Integer) r : -1;
        }
    }

    static final class TxHost implements CpTx.Host {
        @Override
        public long now() {
            return Bridge.now();
        }

        @Override
        public int tid() {
            return android.os.Process.myTid();
        }

        @Override
        public void log(String m) {
            Bridge.log(m);
        }
    }

    static long now() {
        return android.os.SystemClock.elapsedRealtime();
    }

    static long stuckMs() {
        try {
            return Long.parseLong(prop("persist.ev6.cpwl.stuck_ms", String.valueOf(STUCK_MS)).trim());
        } catch (Throwable t) {
            return STUCK_MS;
        }
    }

    /**
     * while the CarPlay channel's session is ours: a send still in the library after 2 s is
     * logged once, as it happens; after STUCK_MS the link counts as lost (re-attach, which
     * abandons that sender) instead of waiting for the server's LimitMaxResend
     */
    private static void txWatch() {
        synchronized (Bridge.class) {
            if (sTxWatch) return;
            sTxWatch = true;
        }
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    long since = sCpTx.blockedSince();
                    long ms = since == 0 ? 0 : now() - since;
                    if (since != 0 && since != sTxReported && ms > SEND_SLOW_MS) {
                        sTxReported = since;
                        log("CarPlay-channel iAP2 send blocked for " + ms + " ms so far (" + sCpTx.describe() + ")");
                    }
                    long stuck = stuckMs();
                    if (since != 0 && since != sTxStuck && stuck > 0 && ms > stuck && sCpClient && !sUsb && sReattach.active()) {
                        sTxStuck = since;
                        log("CarPlay-channel iAP2 send blocked " + ms + " ms: the link counts as lost (tx thread " + sCpTx.threadNumber() + ")");
                        Dipo.rgLinkDown("send blocked");
                        sReattach.lost("CarPlay-channel send blocked " + ms + " ms");
                    }
                } catch (Throwable t) {
                    error("txWatch", t);
                }
                if (sCpClient) {
                    handler().postDelayed(this, 1000);
                } else {
                    synchronized (Bridge.class) {
                        sTxWatch = false;
                    }
                }
            }
        }, 1000);
    }

    static boolean reattachEnabled() {
        return !"0".equals(prop("persist.ev6.cpwl.reattach", "1"));
    }

    /** the iAP2 server's ACTION_IAP2_CONNECTFAIL (on DiPO's main thread) */
    static void onIap2ConnectFail(int component) {
        try {
            if (component == COMPONENT_CARPLAY_CLIENT && sCpClient && !sUsb) Dipo.rgLinkDown("ACTION_IAP2_CONNECTFAIL");
            if (component != COMPONENT_CARPLAY_CLIENT || !sCpClient || sUsb || !sReattach.active()) {
                log("iAP2 connect fail comp=" + component + " (cpClient=" + sCpClient + " usb=" + sUsb + "): not the CarPlay channel's, left alone");
                return;
            }
            sReattach.lost("ACTION_IAP2_CONNECTFAIL / LimitMaxResend");
        } catch (Throwable t) {
            error("onIap2ConnectFail", t);
        }
    }

    /** the iAP2 server's ACTION_IAP2_CONNECTSUCCESS (on DiPO's main thread): the session is identified and authenticated */
    static void onIap2ConnectSuccess(int component) {
        try {
            log("iAP2 connect success comp=" + component);
            if (component == COMPONENT_CARPLAY_CLIENT && sCpClient && !sUsb) {
                long created = diagnosticCreatedAt, started = diagnosticStartedAt, stamp = now();
                log("CarPlay milestone iAP2 authenticated createdToAuthMs="
                        + (created == 0 ? -1 : stamp - created)
                        + " startToAuthMs=" + (started == 0 ? -1 : stamp - started));
            }
            if (component == COMPONENT_CARPLAY_CLIENT && sCpClient && !sUsb) comp5Ready("ACTION_IAP2_CONNECTSUCCESS", true);
        } catch (Throwable t) {
            error("onIap2ConnectSuccess", t);
        }
    }

    /**
     * comp 5 is ready while the channel session is the bridge's: a re-attach under way counts (Reattach, which asks for
     * route guidance 1 s later); any other ready (the first handover) has RgWatch ask 1 s later. One ask per ready.
     */
    static void comp5Ready(String how, boolean linkUp) {
        RgWatch.ready(sReattach, Dipo.rgWatch, how, linkUp);
    }

    /** Reattach's host: ask for the CarPlay channel's session again, as startCpClient did */
    static void reattachCpClient(final int attempt) {
        if (!sCpClient || sUsb) {
            log("re-attach #" + attempt + " skipped (cpClient=" + sCpClient + " usb=" + sUsb + ")");
            return;
        }
        resetReceive();
        Dipo.rgLinkDown("re-attach #" + attempt);
        // the dead link's sender is abandoned (a native call blocked in it is never waited for); the new link gets a fresh thread and queue
        sCpTx.reset("re-attach #" + attempt, true);
        final String addr = sCpSerial != null ? sCpSerial : sAddr;
        final int generation = attachGeneration.advance();
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    if (!currentAttach(generation)) return;
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested again (re-attach #" + attempt + ")");
                } catch (Throwable t) {
                    error("reattachCpClient", t);
                }
            }
        });
    }

    static final class ReattachHost implements Reattach.Host {
        @Override
        public long now() {
            return Bridge.now();
        }

        @Override
        public Object schedule(Runnable r, long delayMs) {
            handler().postDelayed(r, delayMs);
            return r;
        }

        @Override
        public void cancel(Object token) {
            handler().removeCallbacks((Runnable) token);
        }

        @Override
        public void handover(int attempt) {
            reattachCpClient(attempt);
        }

        @Override
        public void askAgain() {
            Dipo.rgAsk("re-attach");
        }

        @Override
        public void log(String m) {
            Bridge.log(m);
        }
    }

    static synchronized void onSppDown(String addr) {
        if (addr == null || !addr.equals(sAddr)) return;
        btGeneration.incrementAndGet();
        if (sCpWireless) {
            sSession = false;
            startCpClient("bluetooth dropped during wireless CarPlay");
            return;
        }
        sAddr = null;
        if (sSession) {
            sSession = false;
            sendDetach();
        }
    }

    static void sendDetach() {
        final int generation = btGeneration.get();
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (btGeneration.get() != generation || sCpClient || sCpWireless) return;
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_DETACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    if (btGeneration.get() != generation || sCpClient || sCpWireless) return;
                    c.sendBroadcast(i);
                    log("BT_DETACHED sent");
                } catch (Throwable t) {
                    error("sendDetach", t);
                }
            }
        });
    }

    /** SPP data from the phone: to the iAP2 server */
    static void onSppData(BluetoothDevice d, byte[] data, int len) {
        try {
            String addr = sAddr;
            IIap2Service s = sIap2;
            if (addr == null || s == null || d == null || data == null || len <= 0 || !sSession || !addr.equals(d.getAddress())) return;
            if (len >= data.length) len = data.length;
            if (len != data.length) {
                byte[] b = new byte[len];
                System.arraycopy(data, 0, b, 0, len);
                data = b;
            }
            s.Iap2SvcTransportReadDone(data, len);
        } catch (Throwable t) {
            error("onSppData", t);
        }
    }

    /** iAP2 bytes from the server for the Bluetooth session: out over SPP */
    static void writeSpp(byte[] data) {
        try {
            String addr = sAddr;
            HfDeviceBTCMManager m = sBtcm;
            if (addr == null || m == null || data == null || data.length == 0 || !sSession) return;
            if (!m.IbtcmSppOutPutStreamWrite(addr, data)) log("SPP write failed, " + data.length + " bytes");
        } catch (Throwable t) {
            error("writeSpp", t);
        }
    }

    /** the head unit's hotspot in mode 2 (for connectivity), which wireless CarPlay joins */
    static void ensureHotspot(Context c) {
        try {
            Object wifi = c.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            Class<?> k = wifi.getClass();
            boolean on = false;
            int mode;
            try {
                on = (Boolean) k.getMethod("isWifiApEnabled").invoke(wifi);
            } catch (Throwable t) {
                // no such call: treat as off
            }
            try {
                mode = (Integer) k.getMethod("getWifiApMode").invoke(wifi);
            } catch (Throwable t) {
                mode = -1;
            }
            if (on && mode == 2) {
                log("hotspot already up (mode 2)");
                return;
            }
            Object conf = k.getMethod("getWifiApConfiguration").invoke(wifi);
            Object r = k.getMethod("setWifiApEnabledForConnectivity", WifiConfiguration.class, boolean.class).invoke(wifi, conf, true);
            log("setWifiApEnabledForConnectivity -> " + r + " (was on=" + on + " mode=" + mode + ")");
        } catch (Throwable t) {
            error("ensureHotspot", t);
        }
    }

    /** Broadcom's status callbacks: an iPhone connects (start SPP), SPP up/down, SPP data */
    static final class BtListener extends HfDeviceStatusListener {
        @Override
        public void onBTCMDeviceConnectionEventChanged(BluetoothDevice d, int event, boolean[] profiles) {
            if (event != EVENT_BTCM_CONNECTED) return;
            try {
                if (isApple(d)) {
                    log("iPhone BT connected: " + d.getAddress());
                    scheduleSppConnect(d.getAddress());
                }
            } catch (Throwable t) {
                error("connEvent", t);
            }
        }

        @Override
        public void onBTCMSppStatusChanged(BluetoothDevice d, int status) {
            if (d == null) return;
            try {
                if (!isApple(d)) return;
                String addr = d.getAddress();
                log("SPP status " + status + " for " + addr);
                if (status == SPP_CONNECTED) onSppUp(addr);
                else if (status == SPP_DISCONNECTED) onSppDown(addr);
            } catch (Throwable t) {
                error("sppStatus", t);
            }
        }

        @Override
        public void onBTCMSppInPutStreamChanged(BluetoothDevice d, byte[] buffer, int length) {
            onSppData(d, buffer, length);
        }
    }

    /** the iAP2 server's side: USB and CarPlay-channel sessions, and transport data to send */
    static final class TxListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {
            log("iAP2 connected comp=" + component);
            // what DiPO keeps as the current device (setCurrentIapDeviceName): a Bluetooth address is the notice's key
            if ((component == COMPONENT_USB || component == COMPONENT_BT_CARPLAY) && serial != null && serial.contains(":")) sLastBtId = serial;
            if (component == COMPONENT_USB) {
                synchronized (sPendingIn) {
                    sUsb = true;
                    resetReceive();
                }
                invalidateBtAttach();
            }
            else if (component == COMPONENT_CARPLAY_CLIENT) {
                final long generation;
                synchronized (sPendingIn) { generation = rxGeneration; }
                if (!onCpClientConnected(generation)) {
                    log("Ignoring CarPlay-channel connected callback outside active wireless handover");
                    return;
                }
                sReattach.connected();
            }
        }

        @Override
        public void disconnected(int component) {
            log("iAP2 disconnected comp=" + component);
            if (component == COMPONENT_USB) {
                sUsb = false;
                onUsbGone();
            } else if (component == COMPONENT_BT_CARPLAY) {
                invalidateBtAttach();
            } else if (component == COMPONENT_CARPLAY_CLIENT) {
                sCpConnected = false;
                if (sCpClient && !sUsb) Dipo.rgLinkDown("iAP2 disconnected comp=5");
                if (sCpClient && !sUsb && sReattach.active() && !sReattach.busy()) sReattach.lost("iAP2 disconnected comp=5");
            }
        }

        @Override
        public void notify(int what, int a1, int a2, Iap2NotiMsg msg) {
            if ((what != NOTI_BT_TRANSPORT_DATA && what != NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) || msg == null) return;
            try {
                Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
                if (g == null) return;
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemParamType() != OBJ_BLOB) continue;
                    if (what == NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) writeCp(it.getItemByteArray());
                    else writeSpp(it.getItemByteArray());
                }
            } catch (Throwable t) {
                error("notify", t);
            }
        }
    }
}
