package examnet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A student's terminal (Lab 3, RMI client). It looks up ExamService at its centre and calls it
 * through the stub. Usage:
 *   java examnet.StudentClient <centreId> <roll>                  answer from the keyboard: "3 B", "finish"
 *   java examnet.StudentClient <centreId> <roll> --auto <seed>    a simulated student (demo)
 *   java examnet.StudentClient <centreId> <roll> --auto <seed> --late   also tries once after the exam closes
 */
public final class StudentClient {

    public static void main(String[] args) throws Exception {
        Cluster.Node centre = Cluster.byId(Integer.parseInt(args[0]));
        String roll = args[1];
        boolean auto = false, late = false;
        long seed = 1;
        for (int i = 2; i < args.length; i++) {
            if ("--auto".equals(args[i])) { auto = true; seed = Long.parseLong(args[++i]); }
            else if ("--late".equals(args[i])) late = true;
        }

        Registry reg = LocateRegistry.getRegistry("127.0.0.1", centre.rmiPort);
        ExamService exam = (ExamService) reg.lookup("ExamService");
        say(exam.login(roll));

        say("Waiting for the exam to open...");
        long left;
        while ((left = exam.timeLeftMs()) < 0) Thread.sleep(200);
        String[] paper = exam.paper();
        say("Exam is OPEN, " + (left / 1000) + " s left. " + paper.length + " questions.");

        if (auto) runAuto(exam, roll, paper, new Random(seed), late);
        else runKeyboard(exam, roll, paper);
    }

    /** A simulated student: answers in random order, sometimes changes an answer, then finishes. */
    private static void runAuto(ExamService exam, String roll, String[] paper, Random rnd, boolean late) throws Exception {
        String key = "BCBBCCCBAB"; // a good student knows most answers
        double skill = 0.5 + rnd.nextDouble() * 0.5;
        List<Integer> order = new ArrayList<Integer>();
        for (int q = 1; q <= paper.length; q++) order.add(q);
        Collections.shuffle(order, rnd);
        for (int q : order) {
            Thread.sleep(2000 + rnd.nextInt(6000)); // reading time
            char opt = rnd.nextDouble() < skill ? key.charAt(q - 1) : (char) ('A' + rnd.nextInt(4));
            say(exam.submit(roll, q, opt));
            if (rnd.nextDouble() < 0.15) { // second thoughts: the later answer must win
                char again = key.charAt(q - 1);
                say(exam.submit(roll, q, again) + " (changed)");
            }
        }
        if (late) {
            say("Waiting for the exam to close, then trying one more answer...");
            while (exam.timeLeftMs() > 0) Thread.sleep(200);
            Thread.sleep(300);
            say(exam.submit(roll, 1, 'A') + " (late attempt)");
        } else {
            say(exam.finish(roll));
        }
    }

    /** A real student at the keyboard. */
    private static void runKeyboard(ExamService exam, String roll, String[] paper) throws Exception {
        for (String q : paper) say(q);
        say("Type <question> <option>, for example 3 B. Type finish to submit.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if ("finish".equalsIgnoreCase(line)) {
                say(exam.finish(roll));
                return;
            }
            String[] p = line.split("\\s+");
            if (p.length != 2) { say("Format: <question> <option>"); continue; }
            say("> " + line + "   " + exam.submit(roll, Integer.parseInt(p[0]), p[1].charAt(0)) + "   (" + exam.timeLeftMs() / 1000 + " s left)");
        }
    }

    private static void say(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
