package examnet;

/**
 * One exam event at a centre: a LOGIN, an ANSWER or a FINISH. It is stamped with the centre's
 * Lamport clock and its synchronised time. (lamport, node) is unique, so it also serves as the
 * event id when events are replicated more than once.
 */
public final class Event implements Comparable<Event> {

    public final String kind;
    public final String roll;
    public final int q;          // question number, 0 for LOGIN and FINISH
    public final char opt;       // chosen option, '-' if none
    public final long lamport;
    public final int node;
    public final long synced;    // synchronised time at the centre

    public Event(String kind, String roll, int q, char opt, long lamport, int node, long synced) {
        this.kind = kind;
        this.roll = roll;
        this.q = q;
        this.opt = opt;
        this.lamport = lamport;
        this.node = node;
        this.synced = synced;
    }

    public String id() {
        return lamport + "@" + node;
    }

    public String encode() {
        return kind + ";" + roll + ";" + q + ";" + opt + ";" + lamport + ";" + node + ";" + synced;
    }

    public static Event decode(String s) {
        String[] p = s.split(";");
        return new Event(p[0], p[1], Integer.parseInt(p[2]), p[3].charAt(0), Long.parseLong(p[4]),
                Integer.parseInt(p[5]), Long.parseLong(p[6]));
    }

    /** Total order: Lamport stamp first, node id breaks ties. */
    public int compareTo(Event o) {
        if (lamport != o.lamport) return lamport < o.lamport ? -1 : 1;
        return Integer.compare(node, o.node);
    }

    /** True if this event comes after o in the total order (used to keep the latest answer). */
    public boolean after(Event o) {
        return compareTo(o) > 0;
    }
}
