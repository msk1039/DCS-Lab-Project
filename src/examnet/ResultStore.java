package examnet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The coordinator's copy of every centre's events (replication). Events can arrive twice (a centre
 * re-sends its whole log to a new coordinator), so they are stored once by id. For each student and
 * question the answer that comes last in Lamport order wins.
 */
public final class ResultStore {

    private final Set<String> seen = new HashSet<String>();
    private final List<Event> audit = new ArrayList<Event>();
    private final Map<String, Event> answers = new TreeMap<String, Event>(); // "roll#q" -> latest answer
    private final Map<String, Integer> centreOf = new TreeMap<String, Integer>();
    private final Set<String> finished = new HashSet<String>();

    /** Returns false if the event was already stored. */
    public synchronized boolean add(Event e) {
        if (!seen.add(e.id())) return false;
        audit.add(e);
        if (!"-".equals(e.roll)) centreOf.put(e.roll, e.node); // "-" marks a centre event, not a student
        if ("ANSWER".equals(e.kind)) {
            String key = e.roll + "#" + e.q;
            Event old = answers.get(key);
            if (old == null || e.after(old)) answers.put(key, e);
        } else if ("FINISH".equals(e.kind)) {
            finished.add(e.roll);
        }
        return true;
    }

    public synchronized int size() {
        return audit.size();
    }

    /** All events in the one total order every node agrees on. */
    public synchronized List<Event> orderedAudit() {
        List<Event> copy = new ArrayList<Event>(audit);
        Collections.sort(copy);
        return copy;
    }

    /** One row per student: roll, centre, score, answered, finished. Sorted by score, then roll. */
    public synchronized List<String[]> ranklist(Paper paper) {
        List<String[]> rows = new ArrayList<String[]>();
        for (Map.Entry<String, Integer> s : centreOf.entrySet()) {
            int score = 0, answered = 0;
            for (int q = 1; q <= paper.size(); q++) {
                Event a = answers.get(s.getKey() + "#" + q);
                if (a == null) continue;
                answered++;
                if (a.opt == paper.key(q)) score++;
            }
            rows.add(new String[] { s.getKey(), Cluster.byId(s.getValue()).name, String.valueOf(score),
                    String.valueOf(answered), finished.contains(s.getKey()) ? "yes" : "no" });
        }
        Collections.sort(rows, (a, b) -> {
            int c = Integer.parseInt(b[2]) - Integer.parseInt(a[2]);
            return c != 0 ? c : a[0].compareTo(b[0]);
        });
        return rows;
    }

    public synchronized List<Event> latestAnswers() {
        return new ArrayList<Event>(answers.values());
    }
}
