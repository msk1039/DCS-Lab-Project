package examnet;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Prints one line per event: real time, this node's synchronised time, Lamport value, text.
 * All nodes run on one machine, so the real time column lets us compare them exactly.
 * Lines starting with #EV are machine-readable records for Verify.
 */
public final class Log {

    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");
    private final SimClock clock;
    private final LamportClock lamport;

    public Log(SimClock clock, LamportClock lamport) {
        this.clock = clock;
        this.lamport = lamport;
    }

    public synchronized void info(String text) {
        long real = System.currentTimeMillis();
        String synced = clock == null ? "            " : fmt.format(new Date(clock.now()));
        String l = lamport == null ? "     " : String.format("%5d", lamport.get());
        System.out.println(fmt.format(new Date(real)) + " | " + synced + " | L=" + l + " | " + text);
        System.out.flush();
    }

    /** A machine-readable record, for example: ev("STOP", "real=..."). */
    public synchronized void ev(String kind, String fields) {
        System.out.println("#EV " + kind + " real=" + System.currentTimeMillis() + " " + fields);
        System.out.flush();
    }

    public synchronized String time(long ms) {
        return fmt.format(new Date(ms));
    }
}
