package examnet;

/**
 * A node's physical clock (Lab 5 Part B). It starts with an error and drifts, like a real quartz
 * clock. ClockSync corrects it: a slow clock jumps forward at once, a fast clock is slewed (it runs
 * at half speed until the error is gone), because time must never run backwards.
 */
public final class SimClock {

    private final double rate;    // 1 + drift
    private long lastReal;        // real time at the last reading
    private long lastSim;         // this clock's time at the last reading
    private long slewLeft;        // milliseconds still to remove by slewing (always <= 0)
    private long totalCorrection; // sum of all corrections, shown on the dashboard

    public SimClock(long errorMs, double driftPpm) {
        this.rate = 1.0 + driftPpm / 1_000_000.0;
        this.lastReal = System.currentTimeMillis();
        this.lastSim = lastReal + errorMs;
    }

    /** Current time of this clock in milliseconds. */
    public synchronized long now() {
        long real = System.currentTimeMillis();
        long dt = real - lastReal;
        long advance = Math.round(dt * rate);
        if (slewLeft < 0 && dt > 0) {
            long take = Math.min(-slewLeft, dt / 2); // run at half speed while slewing
            advance -= take;
            slewLeft += take;
        }
        lastReal = real;
        lastSim += advance;
        return lastSim;
    }

    /**
     * Apply an offset found by NTP: forward at once, backward by slewing. The offset was measured
     * against the clock as it reads now, so any slew still pending from an older sync is dropped.
     */
    public synchronized void correct(long offsetMs) {
        now();
        totalCorrection -= slewLeft; // that part was never applied
        slewLeft = 0;
        totalCorrection += offsetMs;
        if (offsetMs >= 0) {
            lastSim += offsetMs;
        } else {
            slewLeft = offsetMs;
        }
    }

    public synchronized boolean slewing() {
        now();
        return slewLeft < 0;
    }

    public synchronized long totalCorrection() {
        return totalCorrection;
    }
}
