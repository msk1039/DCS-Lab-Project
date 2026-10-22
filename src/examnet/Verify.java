package examnet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks a finished demo run from its log files and prints a PASS/FAIL table.
 * Usage: java examnet.Verify <logs dir> [--baseline]
 */
public final class Verify {

    private static final List<String[]> rows = new ArrayList<String[]>();

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        boolean baseline = args.length > 1 && "--baseline".equals(args[1]);

        // (a) Clock sync: every centre closes the exam at the same real instant.
        Map<Integer, Long> stops = new TreeMap<Integer, Long>();
        Map<Integer, String> syncs = new TreeMap<Integer, String>();
        for (int id = 1; id <= 4; id++) {
            for (String l : lines(new File(dir, "node-" + id + ".log"))) {
                if (l.startsWith("#EV STOP ")) stops.put(id, num(l, "real"));
                if (l.startsWith("#EV SYNC ") && !syncs.containsKey(id)) syncs.put(id, "offset " + num(l, "offset") + " ms, delay " + num(l, "delay") + " ms");
            }
        }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long t : stops.values()) { min = Math.min(min, t); max = Math.max(max, t); }
        long spread = stops.isEmpty() ? -1 : max - min;
        for (Map.Entry<Integer, String> s : syncs.entrySet()) System.out.println("First NTP sync, " + Cluster.byId(s.getKey()).name + ": " + s.getValue());
        check("Exam closes together (spread of STOP times <= 50 ms)", spread >= 0 && spread <= 50,
                stops.size() + " nodes, spread " + spread + " ms");
        if (baseline) { print(); return; }

        // (b) Failover: after the kill, every live centre learns the new coordinator within 5 s.
        long kill = 0;
        int killed = 0;
        for (String l : lines(new File(dir, "demo-events.log"))) {
            if (l.startsWith("#EV KILL ")) { kill = num(l, "real"); killed = (int) num(l, "node"); }
        }
        long worst = -1;
        int newCoord = 0;
        boolean allLearned = true;
        for (int id = 1; id <= 4; id++) {
            if (id == killed) continue;
            long first = -1;
            for (String l : lines(new File(dir, "node-" + id + ".log"))) {
                if (l.startsWith("#EV COORD ") && num(l, "real") > kill && num(l, "node") != killed) {
                    first = num(l, "real");
                    newCoord = (int) num(l, "node");
                    break;
                }
            }
            if (first < 0) allLearned = false;
            else worst = Math.max(worst, first - kill);
        }
        check("New coordinator within 5 s of the crash", kill > 0 && allLearned && worst <= 5000,
                (newCoord == 0 ? "none" : Cluster.byId(newCoord).name) + " after " + worst + " ms");

        // (c) No lost answers: the coordinator holds every accepted answer, and the last one wins.
        Map<String, Character> accepted = new HashMap<String, Character>();
        boolean lateRejected = false, lateTried = false;
        File[] studentLogs = dir.listFiles();
        if (studentLogs != null) {
            for (File f : studentLogs) {
                if (!f.getName().startsWith("student-")) continue;
                String roll = f.getName().substring(8, f.getName().length() - 4);
                for (String l : lines(f)) {
                    int i = l.indexOf("ACCEPTED Q");
                    if (i >= 0) {
                        String[] qa = l.substring(i + 10).split(" ")[0].split("=");
                        accepted.put(roll + "#" + qa[0], qa[1].charAt(0));
                    }
                    if (l.contains("(late attempt)")) {
                        lateTried = true;
                        lateRejected = l.contains("REJECTED time is over");
                    }
                }
            }
        }
        Map<String, Character> stored = new HashMap<String, Character>();
        for (String l : lines(new File(dir, "results.txt"))) {
            if (!l.startsWith("ANSWER ")) continue;
            String[] p = l.split(" ");
            stored.put(p[1] + "#" + p[2], p[3].charAt(0));
        }
        check("No lost answers, latest answer kept", !accepted.isEmpty() && accepted.equals(stored),
                accepted.size() + " accepted, " + stored.size() + " in the results");

        // (d) A late answer is refused.
        check("Late answer rejected", lateTried && lateRejected, lateTried ? "REJECTED time is over" : "no late attempt found");

        // (e) Lamport order respects causality.
        Set<String> ids = new HashSet<String>();
        boolean unique = true, causal = true;
        Map<String, Long> login = new HashMap<String, Long>(), finish = new HashMap<String, Long>();
        Map<Integer, Long> startSeen = new HashMap<Integer, Long>();
        List<long[]> answers = new ArrayList<long[]>();
        List<String> answerRolls = new ArrayList<String>();
        int events = 0;
        for (String l : lines(new File(dir, "audit.txt"))) {
            String[] p = l.split(" ");
            if (p.length < 6) continue;
            events++;
            long lam = Long.parseLong(p[0]);
            int node = Integer.parseInt(p[1]);
            if (!ids.add(lam + "@" + node)) unique = false;
            if ("LOGIN".equals(p[2])) login.put(p[3], lam);
            if ("FINISH".equals(p[2])) finish.put(p[3], lam);
            if ("START".equals(p[2]) && !startSeen.containsKey(node)) startSeen.put(node, lam);
            if ("ANSWER".equals(p[2])) { answers.add(new long[] { lam, node }); answerRolls.add(p[3]); }
        }
        for (int i = 0; i < answers.size(); i++) {
            long lam = answers.get(i)[0];
            int node = (int) answers.get(i)[1];
            String roll = answerRolls.get(i);
            if (!login.containsKey(roll) || login.get(roll) >= lam) causal = false;
            if (finish.containsKey(roll) && finish.get(roll) <= lam) causal = false;
            if (!startSeen.containsKey(node) || startSeen.get(node) >= lam) causal = false;
        }
        check("Lamport order respects cause and effect", events > 0 && unique && causal,
                events + " events, START < LOGIN-ANSWER < FINISH holds");

        // (f) Nothing listens outside this machine.
        int listeners = 0;
        boolean loopback = true;
        for (String l : lines(new File(dir, "listeners.txt"))) {
            if (!l.contains("(LISTEN)")) continue;
            listeners++;
            if (!l.contains("127.0.0.1:")) loopback = false;
        }
        check("Every port listens on 127.0.0.1 only", listeners > 0 && loopback, listeners + " listening sockets");
        print();
    }

    private static void check(String name, boolean ok, String detail) {
        rows.add(new String[] { ok ? "PASS" : "FAIL", name, detail });
    }

    private static void print() {
        System.out.println();
        System.out.println("ExamNet checks");
        System.out.println("--------------------------------------------------------------------------------------------");
        for (String[] r : rows) System.out.println(String.format("%-5s %-52s %s", r[0], r[1], r[2]));
    }

    private static long num(String line, String key) {
        for (String part : line.split(" ")) {
            if (part.startsWith(key + "=")) return Long.parseLong(part.substring(key.length() + 1));
        }
        return -1;
    }

    private static List<String> lines(File f) throws Exception {
        if (!f.exists()) return new ArrayList<String>();
        return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    }
}
