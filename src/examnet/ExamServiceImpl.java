package examnet;

import java.util.HashSet;
import java.util.Set;

/**
 * A centre's RMI service. Calls from different students may run at the same time, so every method
 * is synchronized. The exam window is checked against the centre's synchronised clock, and every
 * accepted event is written to the write-ahead log before the student gets the reply.
 */
public final class ExamServiceImpl implements ExamService {

    private final ExamNode node;
    private final Set<String> loggedIn = new HashSet<String>();
    private final Set<String> done = new HashSet<String>();

    public ExamServiceImpl(ExamNode node) {
        this.node = node;
    }

    public synchronized String login(String roll) {
        if (node.endAt != 0 && node.clock.now() >= node.endAt) return "REJECTED the exam is over";
        if (loggedIn.add(roll)) {
            Event e = node.record("LOGIN", roll, 0, '-');
            node.log.info("LOGIN " + roll + " (event " + e.id() + ")");
        }
        return "Welcome " + roll + " to the " + node.me.name + " centre. Paper " + node.paper.id + ".";
    }

    public synchronized long timeLeftMs() {
        if (node.endAt == 0) return -1;
        long now = node.clock.now();
        if (now < node.startAt) return -1;
        return Math.max(0, node.endAt - now);
    }

    public synchronized String[] paper() {
        if (node.endAt == 0 || node.clock.now() < node.startAt) return new String[0];
        String[] qs = new String[node.paper.size()];
        for (int q = 1; q <= qs.length; q++) qs[q - 1] = node.paper.forStudent(q);
        return qs;
    }

    public synchronized String submit(String roll, int q, char option) {
        long now = node.clock.now();
        option = Character.toUpperCase(option);
        if (!loggedIn.contains(roll)) return "REJECTED please log in first";
        if (node.endAt == 0 || now < node.startAt) return "REJECTED the exam has not started";
        if (now >= node.endAt) {
            node.log.info("LATE answer from " + roll + " for Q" + q + " rejected (" + (now - node.endAt) + " ms after close)");
            node.log.ev("LATE", "roll=" + roll + " q=" + q);
            return "REJECTED time is over";
        }
        if (done.contains(roll)) return "REJECTED you have already finished";
        if (q < 1 || q > node.paper.size() || option < 'A' || option > 'D') return "REJECTED invalid answer";
        Event e = node.record("ANSWER", roll, q, option);
        node.log.info("ANSWER " + roll + " Q" + q + "=" + option + " (event " + e.id() + ")");
        return "ACCEPTED Q" + q + "=" + option + " at L=" + e.lamport;
    }

    public synchronized String finish(String roll) {
        if (!loggedIn.contains(roll)) return "REJECTED please log in first";
        if (done.add(roll)) {
            Event e = node.record("FINISH", roll, 0, '-');
            node.log.info("FINISH " + roll + " (event " + e.id() + ")");
        }
        return "Submitted. Thank you, " + roll + ".";
    }
}
