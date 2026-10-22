package examnet;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixed list of ExamNet nodes. Every node runs on this machine and listens on 127.0.0.1 only,
 * so each one gets its own ports. Each exam centre also gets a simulated clock error, to show why
 * clock synchronisation is needed (a real centre's clock would be wrong by a similar amount).
 */
public final class Cluster {

    public static final class Node {
        public final int id;            // Bully election uses this number: the highest live id wins
        public final String name;
        public final int peerPort;      // TCP channel between nodes (heartbeat, election, time, replication)
        public final int rmiPort;       // RMI registry for students
        public final int httpPort;      // web service and dashboard
        public final long clockErrorMs; // how far this node's clock starts from real time
        public final double driftPpm;   // how fast it drifts afterwards (parts per million)

        Node(int id, String name, int peerPort, int rmiPort, int httpPort, long clockErrorMs, double driftPpm) {
            this.id = id;
            this.name = name;
            this.peerPort = peerPort;
            this.rmiPort = rmiPort;
            this.httpPort = httpPort;
            this.clockErrorMs = clockErrorMs;
            this.driftPpm = driftPpm;
        }
    }

    private static final List<Node> NODES = new ArrayList<Node>();
    static {
        NODES.add(new Node(1, "Pune",        7001, 1101, 8081,  4000,  80));
        NODES.add(new Node(2, "Mumbai",      7002, 1102, 8082, -7000, -60));
        NODES.add(new Node(3, "Nagpur",      7003, 1103, 8083,  2000,  40));
        NODES.add(new Node(4, "Head Office", 7004, 1104, 8084,     0,   0));
    }

    private Cluster() { }

    public static List<Node> all() {
        return NODES;
    }

    public static Node byId(int id) {
        for (Node n : NODES) {
            if (n.id == id) return n;
        }
        throw new IllegalArgumentException("No node with id " + id);
    }
}
