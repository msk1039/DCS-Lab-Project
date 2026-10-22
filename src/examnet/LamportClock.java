package examnet;

/**
 * Lamport's logical clock (Lab 5 Part A). Add 1 before every local event and every send, and on a
 * receive set C = max(C, received) + 1. Then "a happened before b" gives C(a) < C(b), and ties
 * broken by node id give one total order that every node agrees on.
 */
public final class LamportClock {

    private long c;

    /** A local event or a send. Returns the new value to stamp it with. */
    public synchronized long tick() {
        return ++c;
    }

    /** A message stamped ts has arrived. */
    public synchronized long onReceive(long ts) {
        c = Math.max(c, ts) + 1;
        return c;
    }

    public synchronized long get() {
        return c;
    }
}
