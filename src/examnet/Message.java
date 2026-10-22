package examnet;

/**
 * One message on the peer channel, sent as a single text line: TYPE|from|lamport|payload.
 * The payload may itself contain '|', so only the first three are used as separators.
 */
public final class Message {

    public final String type;
    public final int from;
    public final long lamport;
    public final String payload;

    public Message(String type, int from, long lamport, String payload) {
        this.type = type;
        this.from = from;
        this.lamport = lamport;
        this.payload = payload == null ? "" : payload;
    }

    public String encode() {
        return type + "|" + from + "|" + lamport + "|" + payload.replace('\n', ' ');
    }

    public static Message decode(String line) {
        String[] p = line.split("\\|", 4);
        return new Message(p[0], Integer.parseInt(p[1]), Long.parseLong(p[2]), p.length > 3 ? p[3] : "");
    }
}
