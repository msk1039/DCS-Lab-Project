package examnet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The question paper. Every centre holds a sealed copy (questions.txt), and it can be read only
 * after the coordinator multicasts START. Line format: id|question|A|B|C|D|answer.
 */
public final class Paper {

    public final String id;
    private final List<String[]> questions = new ArrayList<String[]>();

    public Paper(String file) throws Exception {
        String paperId = "DCS-2026";
        for (String line : Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#paper ")) {
                paperId = line.substring(7).trim();
                continue;
            }
            if (line.startsWith("#")) continue;
            questions.add(line.split("\\|"));
        }
        this.id = paperId;
    }

    public int size() {
        return questions.size();
    }

    /** Question q (1-based) for a student, without the answer. */
    public String forStudent(int q) {
        String[] p = questions.get(q - 1);
        return "Q" + q + ". " + p[1] + "   A) " + p[2] + "   B) " + p[3] + "   C) " + p[4] + "   D) " + p[5];
    }

    public char key(int q) {
        return questions.get(q - 1)[6].trim().charAt(0);
    }
}
