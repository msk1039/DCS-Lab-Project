package examnet;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-ahead log of a centre's exam events (Unit 4, recovery). An event is forced to disk before
 * the student gets ACCEPTED, so a crash cannot lose an acknowledged answer. After a restart, or
 * when a new coordinator is elected, the log is replayed.
 */
public final class AnswerLog {

    private final File file;
    private final List<Event> events = new ArrayList<Event>();

    public AnswerLog(File file) throws Exception {
        this.file = file;
        if (file.exists()) { // replay after a restart
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) events.add(Event.decode(line.trim()));
            }
        }
    }

    public synchronized void append(Event e) throws Exception {
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            out.write((e.encode() + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync(); // on disk before we reply
        } finally {
            out.close();
        }
        events.add(e);
    }

    public synchronized List<Event> all() {
        return new ArrayList<Event>(events);
    }
}
