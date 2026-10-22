package examnet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * The TCP channel between nodes (Lab 1 Part A). The server accepts connections on 127.0.0.1 and
 * gives each one its own thread, so a slow peer never blocks the others. A request is one line and
 * the reply is one line. Every message carries a Lamport stamp.
 */
public final class Peer {

    /** What a node does with an incoming message. Returns the reply payload. */
    public interface Handler {
        Message handle(Message m);
    }

    private final Cluster.Node me;
    private final LamportClock lamport;
    private final Map<String, Integer> sent = new TreeMap<String, Integer>(); // message counts by type

    public Peer(Cluster.Node me, LamportClock lamport) {
        this.me = me;
        this.lamport = lamport;
    }

    /** Start the server thread. */
    public void listen(final Handler handler) throws Exception {
        final ServerSocket server = new ServerSocket(me.peerPort, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        final Socket s = server.accept();
                        new Thread(new Runnable() { // one thread per connection
                            public void run() { serve(s, handler); }
                        }).start();
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        }, "peer-server");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket s, Handler handler) {
        try {
            s.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            String line = in.readLine();
            if (line != null) {
                Message m = Message.decode(line);
                lamport.onReceive(m.lamport);
                Message reply = handler.handle(m);
                if (reply == null) reply = reply("ACK", "");
                out.println(reply.encode());
            }
        } catch (Exception e) {
            // the sender times out and treats this as no answer
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Build a reply stamped with a fresh Lamport value. */
    public Message reply(String type, String payload) {
        return new Message(type, me.id, lamport.tick(), payload);
    }

    /** Send one request and wait for the reply. Returns null if the node is down or too slow. */
    public Message request(int toId, String type, String payload) {
        Cluster.Node to = Cluster.byId(toId);
        count(type);
        Message m = new Message(type, me.id, lamport.tick(), payload);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), to.peerPort), 500);
            s.setSoTimeout(1500);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println(m.encode());
            String line = in.readLine();
            if (line == null) return null;
            Message r = Message.decode(line);
            lamport.onReceive(r.lamport);
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    public synchronized void count(String type) {
        Integer n = sent.get(type);
        sent.put(type, n == null ? 1 : n + 1);
    }

    public synchronized Map<String, Integer> sentCounts() {
        return new TreeMap<String, Integer>(sent);
    }
}
