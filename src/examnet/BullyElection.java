package examnet;

/**
 * The Bully algorithm (Lab 4). A node that finds the coordinator dead sends ELECTION to every node
 * with a higher id. If none answers OK, it wins and sends COORDINATOR to everyone. If one answers,
 * that node takes over the election, and this node waits for its COORDINATOR message.
 */
public final class BullyElection {

    private final ExamNode node;
    private boolean running;
    private int announcements; // counts COORDINATOR messages, to know if one came while waiting

    public BullyElection(ExamNode node) {
        this.node = node;
    }

    /** Start an election in the background, unless one is already running. */
    public void start(final String reason) {
        synchronized (this) {
            if (running) return;
            running = true;
        }
        new Thread(new Runnable() {
            public void run() { elect(reason); }
        }, "election").start();
    }

    private void elect(String reason) {
        node.log.info("ELECTION started (" + reason + ")");
        int seenBefore;
        synchronized (this) { seenBefore = announcements; }
        boolean higherAlive = false;
        for (Cluster.Node n : Cluster.all()) {
            if (n.id <= node.me.id) continue;
            Message r = node.peer.request(n.id, "ELECTION", "");
            if (r != null && "OK".equals(r.type)) {
                higherAlive = true;
                node.log.info("  OK from " + n.name + " (id " + n.id + "), it takes over");
            } else {
                node.log.info("  no answer from " + n.name + " (id " + n.id + ")");
            }
        }
        if (!higherAlive) {
            node.becomeCoordinator();
            for (Cluster.Node n : Cluster.all()) {
                if (n.id != node.me.id) node.peer.request(n.id, "COORDINATOR", String.valueOf(node.me.id));
            }
            synchronized (this) { running = false; }
            return;
        }
        // A higher node is alive: wait up to 4 s for its COORDINATOR message, else start again.
        long deadline = System.currentTimeMillis() + 4000;
        synchronized (this) {
            while (announcements == seenBefore && System.currentTimeMillis() < deadline) {
                try { wait(200); } catch (InterruptedException ignored) { }
            }
            running = false;
            if (announcements != seenBefore) return;
        }
        start("no COORDINATOR message arrived");
    }

    /** Called when a COORDINATOR message arrives. */
    public synchronized void announced() {
        announcements++;
        notifyAll();
    }
}
