package examnet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The results web service and dashboard (Lab 6). Lab 6 built a SOAP service with JAX-WS. Java 11
 * and later no longer ship JAX-WS, so ExamNet offers a REST web service on the JDK's own HttpServer:
 *   GET /api/status              this node: role, clocks, exam window
 *   GET /api/results(.txt)       ranklist from the coordinator's replicated answers
 *   GET /api/audit(.txt)         every event in Lamport order
 *   GET /api/schedule?startIn=8&duration=120   coordinator only: start an exam (seconds)
 *   GET /                        HTML dashboard, refreshes every 2 s
 * It listens on 127.0.0.1 only.
 */
public final class WebService {

    private final ExamNode node;

    public WebService(ExamNode node) {
        this.node = node;
    }

    public void start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), node.me.httpPort), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange x) { route(x); }
        });
        server.start();
    }

    private void route(HttpExchange x) {
        try {
            String path = x.getRequestURI().getPath();
            String query = x.getRequestURI().getQuery();
            boolean coord = node.coordinator == node.me.id;
            if ("/api/status".equals(path)) {
                send(x, 200, "application/json", status());
            } else if ("/api/schedule".equals(path)) {
                Map<String, String> q = params(query);
                long startIn = Long.parseLong(q.containsKey("startIn") ? q.get("startIn") : "10") * 1000;
                long duration = Long.parseLong(q.containsKey("duration") ? q.get("duration") : "120") * 1000;
                String r = node.schedule(startIn, duration);
                send(x, coord ? 200 : 409, "application/json", "{\"result\": " + js(r) + "}");
            } else if (path.startsWith("/api/results") || path.startsWith("/api/audit")) {
                if (!coord) {
                    send(x, 409, "application/json", "{\"error\": \"not the coordinator\", \"coordinator\": " + node.coordinator + "}");
                } else if ("/api/results".equals(path)) {
                    send(x, 200, "application/json", resultsJson());
                } else if ("/api/results.txt".equals(path)) {
                    send(x, 200, "text/plain", resultsText());
                } else if ("/api/audit".equals(path)) {
                    send(x, 200, "application/json", auditJson());
                } else {
                    send(x, 200, "text/plain", auditText());
                }
            } else if ("/".equals(path)) {
                send(x, 200, "text/html", dashboard());
            } else {
                send(x, 404, "text/plain", "not found");
            }
        } catch (Exception e) {
            try { send(x, 500, "text/plain", e.toString()); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ API

    private String status() {
        long now = node.clock.now();
        return "{\"node\": " + node.me.id + ", \"name\": " + js(node.me.name)
                + ", \"role\": " + js(node.coordinator == node.me.id ? "coordinator" : "centre")
                + ", \"coordinator\": " + node.coordinator
                + ", \"lamport\": " + node.lamport.get()
                + ", \"syncedTime\": " + js(node.log.time(now))
                + ", \"clockCorrectionMs\": " + node.clock.totalCorrection()
                + ", \"lastNtpOffsetMs\": " + node.lastOffset + ", \"lastNtpDelayMs\": " + node.lastDelay
                + ", \"examOpens\": " + js(node.endAt == 0 ? "" : node.log.time(node.startAt))
                + ", \"examCloses\": " + js(node.endAt == 0 ? "" : node.log.time(node.endAt))
                + ", \"messagesSent\": " + js(node.peer.sentCounts().toString()) + "}";
    }

    private String resultsJson() {
        StringBuilder b = new StringBuilder("{\"paper\": " + js(node.paper.id) + ", \"students\": [");
        List<String[]> rows = node.results.ranklist(node.paper);
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            b.append(i == 0 ? "" : ", ").append("{\"rank\": ").append(i + 1).append(", \"roll\": ").append(js(r[0]))
                    .append(", \"centre\": ").append(js(r[1])).append(", \"score\": ").append(r[2])
                    .append(", \"answered\": ").append(r[3]).append(", \"finished\": ").append(js(r[4])).append("}");
        }
        return b.append("]}").toString();
    }

    private String resultsText() {
        StringBuilder b = new StringBuilder();
        for (String[] r : node.results.ranklist(node.paper)) {
            b.append("STUDENT ").append(r[0]).append(' ').append(r[1].replace(' ', '_')).append(' ').append(r[2])
                    .append(' ').append(r[3]).append(' ').append(r[4]).append('\n');
        }
        for (Event a : node.results.latestAnswers()) {
            b.append("ANSWER ").append(a.roll).append(' ').append(a.q).append(' ').append(a.opt).append(' ')
                    .append(a.lamport).append(' ').append(a.node).append('\n');
        }
        return b.toString();
    }

    private String auditJson() {
        StringBuilder b = new StringBuilder("[");
        List<Event> events = node.results.orderedAudit();
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            b.append(i == 0 ? "" : ", ").append("{\"lamport\": ").append(e.lamport).append(", \"node\": ").append(e.node)
                    .append(", \"kind\": ").append(js(e.kind)).append(", \"roll\": ").append(js(e.roll))
                    .append(", \"q\": ").append(e.q).append(", \"option\": ").append(js(String.valueOf(e.opt))).append("}");
        }
        return b.append("]").toString();
    }

    private String auditText() {
        StringBuilder b = new StringBuilder();
        for (Event e : node.results.orderedAudit()) {
            b.append(e.lamport).append(' ').append(e.node).append(' ').append(e.kind).append(' ').append(e.roll)
                    .append(' ').append(e.q).append(' ').append(e.opt).append('\n');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ dashboard

    private String dashboard() {
        boolean coord = node.coordinator == node.me.id;
        long now = node.clock.now();
        String status;
        if (node.endAt == 0) status = "Waiting for START";
        else if (now < node.startAt) status = "Opens at " + node.log.time(node.startAt);
        else if (now < node.endAt) status = "OPEN, " + ((node.endAt - now) / 1000) + " s left";
        else status = "CLOSED at " + node.log.time(node.endAt);
        String coordName = node.coordinator == 0 ? "electing" : Cluster.byId(node.coordinator).name;

        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset='utf-8'><meta http-equiv='refresh' content='2'>")
         .append("<title>ExamNet ").append(node.me.name).append("</title><style>")
         .append("body{margin:0;font:15px/1.4 Arial,Helvetica,sans-serif;background:#f1f3f6;color:#14233b}")
         .append("header{background:#14233b;color:#fff;padding:18px 28px;display:flex;align-items:center;gap:16px}")
         .append("header h1{margin:0;font:bold 24px Georgia,serif}.pill{padding:4px 12px;border-radius:14px;font-weight:bold;font-size:13px}")
         .append(".coord{background:#8cc4ff;color:#14233b}.centre{background:#d8dde4;color:#14233b}.muted{color:#8a94a6}")
         .append("main{padding:22px 28px}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:22px}")
         .append(".card{background:#fff;padding:14px 18px;border-radius:6px}.card b{display:block;font:bold 22px Georgia,serif;margin-top:4px}")
         .append("h2{font:bold 18px Georgia,serif;margin:18px 0 8px}table{border-collapse:collapse;width:100%;background:#fff}")
         .append("th,td{padding:7px 12px;text-align:left;border-bottom:1px solid #e3e7ec}th{background:#14233b;color:#fff;font-size:13px}")
         .append(".open{color:#2e7d32}.closed{color:#b3261e}.grid{display:grid;grid-template-columns:1fr 1fr;gap:22px}</style></head><body>");
        b.append("<header><h1>ExamNet</h1><span>").append(node.me.name).append(" (node ").append(node.me.id).append(")</span>")
         .append("<span class='pill ").append(coord ? "coord'>COORDINATOR" : "centre'>EXAM CENTRE").append("</span>")
         .append("<span class='muted'>coordinator: ").append(coordName).append("</span></header><main><div class='cards'>")
         .append(card("Synchronised clock", node.log.time(now)))
         .append(card("Clock correction", ExamNode.signed(node.clock.totalCorrection()) + " ms"))
         .append(card("Lamport clock", String.valueOf(node.lamport.get())))
         .append(card("Exam", "<span class='" + (status.startsWith("CLOSED") ? "closed" : "open") + "'>" + status + "</span>"))
         .append("</div><div class='grid'><div><h2>Students at this centre</h2>").append(localStudents()).append("</div>");
        if (coord) {
            b.append("<div><h2>Ranklist, all centres</h2>").append(ranklistHtml()).append("</div></div>")
             .append("<h2>Audit log in Lamport order (last 10)</h2>").append(auditHtml());
        } else {
            b.append("<div><h2>Results</h2><p class='muted'>Results are held by the coordinator (")
             .append(coordName).append(").</p></div></div>");
        }
        return b.append("</main></body></html>").toString();
    }

    private String card(String label, String value) {
        return "<div class='card'><span class='muted'>" + label + "</span><b>" + value + "</b></div>";
    }

    private String localStudents() {
        Map<String, TreeSet<Integer>> answered = new TreeMap<String, TreeSet<Integer>>();
        TreeSet<String> finished = new TreeSet<String>();
        for (Event e : node.wal.all()) {
            if ("-".equals(e.roll)) continue;
            if (!answered.containsKey(e.roll)) answered.put(e.roll, new TreeSet<Integer>());
            if ("ANSWER".equals(e.kind)) answered.get(e.roll).add(e.q);
            if ("FINISH".equals(e.kind)) finished.add(e.roll);
        }
        if (answered.isEmpty()) return "<p class='muted'>No students yet.</p>";
        StringBuilder b = new StringBuilder("<table><tr><th>Roll</th><th>Answered</th><th>Finished</th></tr>");
        for (Map.Entry<String, TreeSet<Integer>> s : answered.entrySet()) {
            b.append("<tr><td>").append(s.getKey()).append("</td><td>").append(s.getValue().size()).append(" / ")
             .append(node.paper.size()).append("</td><td>").append(finished.contains(s.getKey()) ? "yes" : "no").append("</td></tr>");
        }
        return b.append("</table>").toString();
    }

    private String ranklistHtml() {
        List<String[]> rows = node.results.ranklist(node.paper);
        if (rows.isEmpty()) return "<p class='muted'>No answers yet.</p>";
        StringBuilder b = new StringBuilder("<table><tr><th>#</th><th>Roll</th><th>Centre</th><th>Score</th></tr>");
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            b.append("<tr><td>").append(i + 1).append("</td><td>").append(r[0]).append("</td><td>").append(r[1])
             .append("</td><td>").append(r[2]).append(" / ").append(node.paper.size()).append("</td></tr>");
        }
        return b.append("</table>").toString();
    }

    private String auditHtml() {
        List<Event> events = node.results.orderedAudit();
        StringBuilder b = new StringBuilder("<table><tr><th>Lamport</th><th>Node</th><th>Event</th><th>Student</th><th>Detail</th></tr>");
        for (int i = Math.max(0, events.size() - 10); i < events.size(); i++) {
            Event e = events.get(i);
            b.append("<tr><td>").append(e.lamport).append("</td><td>").append(Cluster.byId(e.node).name).append("</td><td>")
             .append(e.kind).append("</td><td>").append(e.roll).append("</td><td>")
             .append("ANSWER".equals(e.kind) ? "Q" + e.q + " = " + e.opt : "COORD".equals(e.kind) && e.q > 0 ? "new: " + Cluster.byId(e.q).name : "")
             .append("</td></tr>");
        }
        return b.append("</table>").toString();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> params(String query) {
        Map<String, String> m = new TreeMap<String, String>();
        if (query == null) return m;
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2) m.put(p[0], p[1]);
        }
        return m;
    }

    private static String js(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void send(HttpExchange x, int code, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        x.sendResponseHeaders(code, bytes.length);
        OutputStream out = x.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
