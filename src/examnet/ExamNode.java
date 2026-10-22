package examnet;

import java.io.File;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One ExamNet node. Every node runs the same program: an exam centre (RMI service for students),
 * a peer on the TCP channel, and, if elected, the coordinator (time server, exam controller and
 * result store). Usage: java examnet.ExamNode <id> [--no-sync] [--dir logs] [--paper questions.txt]
 */
public final class ExamNode {

    final Cluster.Node me;
    final SimClock clock;
    final LamportClock lamport = new LamportClock();
    final Log log;
    final Peer peer;
    final Paper paper;
    final AnswerLog wal;
    final ResultStore results = new ResultStore();
    final BullyElection election = new BullyElection(this);
    final boolean syncEnabled;

    volatile int coordinator;         // 0 until the first election ends
    volatile long startAt, endAt;     // exam window in synchronised time, from the START group message
    volatile boolean started, stopped, warned;
    volatile long lastOffset, lastDelay; // last NTP result, for the dashboard
    private long groupSeq;            // highest group message number seen
    private final Object syncSignal = new Object();
    private boolean syncNow;

    // Kept as fields so the exported RMI objects are never garbage collected.
    private Registry registry;
    private ExamServiceImpl service;

    ExamNode(int id, boolean syncEnabled, File dir, String paperFile) throws Exception {
        this.me = Cluster.byId(id);
        this.syncEnabled = syncEnabled;
        this.clock = new SimClock(me.clockErrorMs, me.driftPpm);
        this.log = new Log(clock, lamport);
        this.peer = new Peer(me, lamport);
        this.paper = new Paper(paperFile);
        this.wal = new AnswerLog(new File(dir, "wal-" + id + ".log"));
    }

    public static void main(String[] args) throws Exception {
        int id = Integer.parseInt(args[0]);
        boolean sync = true;
        File dir = new File("logs");
        String paperFile = "questions.txt";
        for (int i = 1; i < args.length; i++) {
            if ("--no-sync".equals(args[i])) sync = false;
            else if ("--dir".equals(args[i])) dir = new File(args[++i]);
            else if ("--paper".equals(args[i])) paperFile = args[++i];
        }
        dir.mkdirs();
        new ExamNode(id, sync, dir, paperFile).run();
    }

    void run() throws Exception {
        log.info("Node " + me.id + " (" + me.name + ") starting. Clock error " + signed(me.clockErrorMs)
                + " ms, drift " + me.driftPpm + " ppm, sync " + (syncEnabled ? "on" : "OFF"));
        peer.listen(new Peer.Handler() {
            public Message handle(Message m) { return onMessage(m); }
        });

        // Lab 3: export the exam service for students, on 127.0.0.1 only.
        LoopbackSockets ssf = new LoopbackSockets();
        registry = LocateRegistry.createRegistry(me.rmiPort, null, ssf);
        service = new ExamServiceImpl(this);
        ExamService stub = (ExamService) UnicastRemoteObject.exportObject(service, 0, null, ssf);
        registry.rebind("ExamService", stub);
        log.info("RMI ExamService bound at rmi://127.0.0.1:" + me.rmiPort + "/ExamService");

        new WebService(this).start();
        log.info("Web service and dashboard at http://127.0.0.1:" + me.httpPort + "/");

        background("heartbeat", new Runnable() { public void run() { heartbeatLoop(); } });
        background("clock-sync", new Runnable() { public void run() { syncLoop(); } });
        background("exam-timer", new Runnable() { public void run() { timerLoop(); } });

        Thread.sleep(800);
        election.start("node started");
        Thread.currentThread().join(); // keep running until the process is stopped
    }

    // ------------------------------------------------------------------ peer messages

    Message onMessage(Message m) {
        String from = Cluster.byId(m.from).name;
        if ("HEARTBEAT".equals(m.type)) {
            return peer.reply(coordinator == me.id ? "ALIVE" : "NOTCOORD", String.valueOf(coordinator));
        }
        if ("TIME".equals(m.type)) { // NTP server side: T2 on arrival, T3 just before the reply
            long t2 = clock.now();
            long t3 = clock.now();
            return peer.reply("TIME", t2 + "," + t3);
        }
        if ("ELECTION".equals(m.type)) {
            log.info("ELECTION from " + from + ", answering OK");
            peer.count("OK");
            election.start("ELECTION from " + from);
            return peer.reply("OK", "");
        }
        if ("COORDINATOR".equals(m.type)) {
            int id = Integer.parseInt(m.payload.trim());
            setCoordinator(id);
            election.announced();
            return peer.reply("ACK", "");
        }
        if ("GROUP".equals(m.type)) {
            onGroup(m.payload);
            return peer.reply("ACK", "");
        }
        if ("REPLICATE".equals(m.type)) {
            if (coordinator != me.id) return peer.reply("NOTCOORD", String.valueOf(coordinator));
            results.add(Event.decode(m.payload));
            return peer.reply("ACK", "");
        }
        return peer.reply("ERROR", "unknown type " + m.type);
    }

    // ------------------------------------------------------------------ coordinator and election

    /** Called by the election when this node has won. */
    void becomeCoordinator() {
        coordinator = me.id;
        log.info("I am the COORDINATOR now");
        log.ev("COORD", "node=" + me.id + " by=" + me.id);
        record("COORD", "-", me.id, '-');
        for (Event e : wal.all()) results.add(e); // this centre's own events
    }

    void setCoordinator(int id) {
        boolean changed = id != coordinator;
        coordinator = id;
        if (!changed) return;
        log.info("New coordinator: " + Cluster.byId(id).name + " (id " + id + ")");
        log.ev("COORD", "node=" + id + " by=" + me.id);
        record("COORD", "-", id, '-');
        if (id != me.id) {
            requestSync();
            background("resend", new Runnable() { public void run() { resendLog(); } });
        }
    }

    /** Send this centre's whole write-ahead log to a new coordinator (it may have lost everything). */
    private void resendLog() {
        int c = coordinator;
        int sent = 0;
        for (Event e : wal.all()) {
            Message r = peer.request(c, "REPLICATE", e.encode());
            if (r != null && "ACK".equals(r.type)) sent++;
        }
        log.info("Re-sent " + sent + " logged events to " + Cluster.byId(c).name);
    }

    private void heartbeatLoop() {
        int misses = 0;
        while (true) {
            sleep(1000);
            int c = coordinator;
            if (c == 0 || c == me.id) { misses = 0; continue; }
            Message r = peer.request(c, "HEARTBEAT", "");
            if (r != null) { misses = 0; continue; }
            misses++;
            log.info("Heartbeat to " + Cluster.byId(c).name + " failed (" + misses + "/3)");
            if (misses >= 3) {
                misses = 0;
                log.ev("SUSPECT", "node=" + c);
                election.start(Cluster.byId(c).name + " is not responding");
            }
        }
    }

    // ------------------------------------------------------------------ clock synchronisation

    void requestSync() {
        synchronized (syncSignal) {
            syncNow = true;
            syncSignal.notifyAll();
        }
    }

    private void syncLoop() {
        while (true) {
            synchronized (syncSignal) {
                if (!syncNow) {
                    try { syncSignal.wait(20000); } catch (InterruptedException ignored) { }
                }
                syncNow = false;
            }
            int c = coordinator;
            if (!syncEnabled || c == 0 || c == me.id) continue;
            long before = clock.now() - System.currentTimeMillis();
            long[] best = ClockSync.run(this, c);
            if (best == null) {
                log.info("Clock sync with " + Cluster.byId(c).name + " failed");
                continue;
            }
            lastOffset = best[0];
            lastDelay = best[1];
            log.info("NTP sync with " + Cluster.byId(c).name + ": offset " + signed(best[0]) + " ms, delay "
                    + best[1] + " ms (best of 8), " + (best[0] >= 0 ? "clock set forward" : "clock slewed back"));
            log.ev("SYNC", "to=" + c + " offset=" + best[0] + " delay=" + best[1] + " errorBefore=" + before);
        }
    }

    // ------------------------------------------------------------------ exam control (group multicast)

    /** Coordinator only: schedule the exam and multicast START to every centre. */
    synchronized String schedule(long startInMs, long durationMs) {
        if (coordinator != me.id) return "not the coordinator";
        long s = clock.now() + startInMs;
        long e = s + durationMs;
        groupCast("START", paper.id + "," + s + "," + e);
        return "paper " + paper.id + " starts " + log.time(s) + ", ends " + log.time(e);
    }

    /**
     * Reliable application-level multicast: the message goes to every member over TCP and is retried
     * until each one acknowledges (3 tries). Sequence numbers let members drop duplicates.
     */
    synchronized void groupCast(String kind, String args) {
        long seq = ++groupSeq;
        String payload = seq + ";" + kind + ";" + args;
        onGroup(payload); // deliver to ourselves too
        List<String> acked = new ArrayList<String>();
        for (Cluster.Node n : Cluster.all()) {
            if (n.id == me.id) continue;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Message r = peer.request(n.id, "GROUP", payload);
                if (r != null) { acked.add(n.name); break; }
            }
        }
        log.info("Multicast #" + seq + " " + kind + " acknowledged by " + (acked.isEmpty() ? "nobody" : String.join(", ", acked)));
    }

    synchronized void onGroup(String payload) {
        String[] p = payload.split(";", 3);
        long seq = Long.parseLong(p[0]);
        String kind = p[1];
        if (seq <= groupSeq && !(coordinator == me.id && seq == groupSeq)) return; // duplicate
        groupSeq = Math.max(groupSeq, seq);
        if ("START".equals(kind)) {
            String[] a = p[2].split(",");
            startAt = Long.parseLong(a[1]);
            endAt = Long.parseLong(a[2]);
            log.info("START received: paper " + a[0] + ", opens " + log.time(startAt) + ", closes " + log.time(endAt));
            record("START", "-", 0, '-');
        } else if ("WARN".equals(kind)) {
            warned = true;
            log.info("Announcement: " + p[2] + " seconds left");
        } else if ("STOP".equals(kind)) {
            log.info("STOP confirmed by the coordinator");
        }
    }

    private void timerLoop() {
        while (true) {
            sleep(5);
            if (endAt == 0) continue;
            long now = clock.now();
            if (!started && now >= startAt) {
                started = true;
                log.info("Exam OPEN at this centre");
                log.ev("OPEN", "node=" + me.id + " synced=" + now);
            }
            if (coordinator == me.id && !warned && !stopped && endAt - now <= 60000) {
                warned = true;
                groupCast("WARN", "60");
            }
            if (!stopped && now >= endAt) {
                stopped = true;
                log.info("Exam CLOSED at this centre (synced " + log.time(now) + ")");
                log.ev("STOP", "node=" + me.id + " synced=" + now);
                record("STOP", "-", 0, '-');
                if (coordinator == me.id) groupCast("STOP", "");
            }
        }
    }

    // ------------------------------------------------------------------ events and replication

    /** Log an event in the write-ahead log, then replicate it to the coordinator. */
    Event record(String kind, String roll, int q, char opt) {
        Event e = new Event(kind, roll, q, opt, lamport.tick(), me.id, clock.now());
        try {
            wal.append(e);
        } catch (Exception ex) {
            log.info("WAL write failed: " + ex);
        }
        replicate(e);
        return e;
    }

    void replicate(Event e) {
        int c = coordinator;
        if (c == me.id) {
            results.add(e);
        } else if (c != 0) {
            Message r = peer.request(c, "REPLICATE", e.encode());
            if (r == null || !"ACK".equals(r.type)) log.info("  replication to coordinator pending (kept in WAL)");
        }
    }

    // ------------------------------------------------------------------ helpers

    static String signed(long v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    private static void background(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
