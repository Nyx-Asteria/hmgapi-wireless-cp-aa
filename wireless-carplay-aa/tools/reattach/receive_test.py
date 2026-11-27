"""Run the actual Bridge receive methods against a controlled Binder double."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parents[2] / "src/hmgapi/mods/wireless/dipo/Bridge.java"
body = source.read_text()
def method(signature):
    start = body.index(signature)
    opening = body.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (body[end] == "{") - (body[end] == "}")
        end += 1
    return body[start:end]

java = """
import java.util.*;
import java.util.concurrent.*;
public class ReceiveTest {
    interface IIap2Service { void Iap2SvcTransportReadDone(byte[] b, int n); }
    static volatile boolean sCpConnected, sCpClient, sUsb;
    static volatile IIap2Service sIap2;
    static volatile Object sCp;
    static final int MAX_PENDING = 64;
    static final ArrayList<byte[]> sPendingIn = new ArrayList<>();
    static long diagnosticRxOverflow;
    static long diagnosticRxAttempts, diagnosticRxFailures, diagnosticRxTotalMs, diagnosticRxMaxMs;
    static long now() { return System.nanoTime()/1000000L; }
    static boolean rxDraining;
    static long rxGeneration;
    static void startCpClient(String why, long generation) {
        synchronized (sPendingIn) {
            if (generation != rxGeneration || sUsb || sCpClient) return;
            sCpClient=true; sCpConnected=false;
        }
    }
    static int errors;
    static void error(String why, Throwable t) { errors++; }
""" + "\n".join(method(s) for s in (
    "static void onCpIapIn(", "static boolean onCpClientConnected(", "private static void acceptReceive(", "private static void flushPending(", "private static void resetReceive(", "private static void replaceReceiveService("
)) + """
    static void check(boolean v,String msg) { if(!v) throw new AssertionError(msg); }
    static void send(int n) { onCpIapIn(null,new byte[]{(byte)n},1); }
    static void await(CountDownLatch l) {
        try { check(l.await(3,TimeUnit.SECONDS),"latch timeout"); }
        catch(InterruptedException e){ throw new AssertionError(e); }
    }
    public static void main(String[] args) throws Exception {
        List<Integer> seen=Collections.synchronizedList(new ArrayList<Integer>());
        sIap2=(b,n)->seen.add((int)b[0]);
        byte[] reusable={1,99};
        onCpIapIn(null,reusable,1); reusable[0]=55;
        send(2); check(seen.isEmpty(),"buffer until connected");
        onCpClientConnected(rxGeneration); check(seen.equals(Arrays.asList(1,2)),"copy and FIFO");
        seen.clear();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        sIap2=(b,n)->{seen.add((int)b[0]);if(b[0]==3){entered.countDown();await(release);}};
        Thread first=new Thread(()->send(3));first.setDaemon(true);first.start();await(entered);
        Thread producer=new Thread(()->{send(4);send(5);});producer.setDaemon(true);producer.start();
        producer.join(3000);
        check(!producer.isAlive(),"Binder must not hold queue lock");
        check(seen.equals(Arrays.asList(3)),"only one drain owner");
        release.countDown();first.join(3000);
        check(!first.isAlive()&&seen.equals(Arrays.asList(3,4,5)),"concurrent FIFO");
        seen.clear();sCpConnected=false;
        for(int i=0;i<65;i++)send(i);
        check(sPendingIn.size()==64&&diagnosticRxOverflow==1,"bounded queue");
        onCpClientConnected(rxGeneration);check(seen.size()==64&&seen.get(63)==63,"drain retained packets");
        seen.clear();sIap2=(b,n)->{if(b[0]==7)throw new IllegalStateException("fixture");seen.add((int)b[0]);};
        send(7);send(8);check(errors==1&&seen.equals(Arrays.asList(8)),"failure releases drain ownership");
        sUsb=true;send(9);check(seen.equals(Arrays.asList(8)),"USB excludes wireless receive");
        sUsb=false;seen.clear();
        CountDownLatch oldEntered=new CountDownLatch(1), oldRelease=new CountDownLatch(1);
        CountDownLatch newEntered=new CountDownLatch(1), newRelease=new CountDownLatch(1);
        sIap2=(b,n)->{seen.add((int)b[0]);oldEntered.countDown();await(oldRelease);};
        Thread oldDrain=new Thread(()->send(10));oldDrain.setDaemon(true);oldDrain.start();await(oldEntered);
        send(11);resetReceive();
        sIap2=(b,n)->{seen.add((int)b[0]);if(b[0]==12){newEntered.countDown();await(newRelease);}};
        send(12);
        Thread newDrain=new Thread(()->onCpClientConnected(rxGeneration));newDrain.setDaemon(true);newDrain.start();await(newEntered);
        send(13);oldRelease.countDown();oldDrain.join(3000);
        check(!oldDrain.isAlive()&&seen.equals(Arrays.asList(10,12)),"old drain must not consume new queue");
        send(14);check(seen.equals(Arrays.asList(10,12)),"old drain must not release new ownership");
        newRelease.countDown();newDrain.join(3000);
        check(!newDrain.isAlive()&&seen.equals(Arrays.asList(10,12,13,14)),"new generation FIFO and old queue discarded");
        long stale = rxGeneration;
        resetReceive(); sCpClient=false;
        acceptReceive(new Object(),new byte[]{15},stale);
        check(!sCpClient && sPendingIn.isEmpty(),"stale producer cannot resurrect client");
        check(!onCpClientConnected(stale) && !sCpConnected,"stale connection rejected");
        send(16); check(sPendingIn.size()==1,"current producer accepted");
        check(!onCpClientConnected(stale) && !sCpConnected,"old connection cannot drain new queue");
        check(onCpClientConnected(rxGeneration) && seen.get(seen.size()-1)==16,"current connection drains");
        sUsb=true;resetReceive();
        check(!onCpClientConnected(rxGeneration),"USB rejects connection");
        check(diagnosticRxFailures==1 && diagnosticRxAttempts>0,"aggregate receive metrics");
        sUsb=false; sCpClient=true;
        seen.clear();
        CountDownLatch serviceEntered=new CountDownLatch(1), serviceRelease=new CountDownLatch(1);
        IIap2Service oldService=(b,n)->{seen.add((int)b[0]);serviceEntered.countDown();await(serviceRelease);};
        replaceReceiveService(oldService);
        onCpClientConnected(rxGeneration);
        Thread blocked=new Thread(()->send(17));blocked.setDaemon(true);blocked.start();await(serviceEntered);
        send(18);
        long prior=rxGeneration;
        IIap2Service replacement=(b,n)->seen.add((int)b[0]);
        replaceReceiveService(replacement);
        check(rxGeneration!=prior && !sCpConnected && sPendingIn.isEmpty(),"replacement invalidates old link");
        send(19);check(seen.equals(Arrays.asList(17)),"replacement waits for connection");
        check(onCpClientConnected(rxGeneration),"replacement connects");
        serviceRelease.countDown();blocked.join(3000);
        check(!blocked.isAlive() && seen.equals(Arrays.asList(17,19)),"old service cannot drain replacement");
        long stable=rxGeneration;
        replaceReceiveService(replacement);
        check(stable==rxGeneration && sCpConnected,"same service does not reset");
        System.out.println("Bridge receive FIFO, copy, overflow, concurrent enqueue, failure and USB checks passed");
    }
}
"""
with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "ReceiveTest.java"
    path.write_text(java)
    subprocess.run(["javac", "--release", "8", str(path)], check=True, timeout=20)
    subprocess.run(["java", "-cp", tmp, "ReceiveTest"], check=True, timeout=20)
