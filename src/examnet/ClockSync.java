package examnet;

import java.util.Random;

/**
 * NTP-style synchronisation with the coordinator (Lab 5 Part B). The centre sends at T1, the
 * coordinator receives at T2 and replies at T3, and the reply arrives at T4:
 *   delay  = (T4 - T1) - (T3 - T2)
 *   offset = ((T2 - T1) + (T3 - T4)) / 2
 * Eight exchanges are made and the one with the smallest delay is used, as NTP does. Every node runs
 * on one machine, so a wide-area delay of 5 to 40 ms each way is simulated.
 */
public final class ClockSync {

    private static final Random RANDOM = new Random();

    /** Returns {offset, delay} of the best exchange, or null if the coordinator did not answer. */
    public static long[] run(ExamNode node, int coordinator) {
        long[] best = null;
        for (int i = 0; i < 8; i++) {
            long t1 = node.clock.now();
            sleep(5 + RANDOM.nextInt(36)); // request travels
            Message r = node.peer.request(coordinator, "TIME", String.valueOf(t1));
            if (r == null || !"TIME".equals(r.type)) continue;
            sleep(5 + RANDOM.nextInt(36)); // reply travels
            long t4 = node.clock.now();
            String[] p = r.payload.split(",");
            long t2 = Long.parseLong(p[0]), t3 = Long.parseLong(p[1]);
            long delay = (t4 - t1) - (t3 - t2);
            long offset = ((t2 - t1) + (t3 - t4)) / 2;
            if (best == null || delay < best[1]) best = new long[] { offset, delay };
        }
        if (best != null) node.clock.correct(best[0]);
        return best;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
