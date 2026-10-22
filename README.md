# ExamNet: A Fault-Tolerant Distributed Online Examination System

Distributed Computing Systems lab mini project.

A university runs one online exam at several centres. ExamNet keeps it fair and running when things go wrong:

- **Same closing time everywhere.** Centre clocks are wrong by seconds. NTP-style sync brings them within milliseconds.
- **No single point of failure.** If the coordinator crashes, the Bully algorithm elects a new one and the exam goes on.
- **No lost answers.** Every answer is on disk (write-ahead log) before the student sees ACCEPTED, and is replicated.
- **A trusted order of events.** Lamport clocks give one order of all events that every node agrees on.

## Lab concepts used

| Lab | Concept | Where in ExamNet |
|---|---|---|
| 1A | Multithreaded TCP sockets | `Peer`: node-to-node channel, one thread per connection |
| 1B | Multicast (group communication) | `ExamNode.groupCast`: START, WARN, STOP to every centre, reliable (acked, retried) |
| 3 | Java RMI | `ExamService`, `ExamServiceImpl`, `StudentClient` |
| 4 | Bully election | `BullyElection`, heartbeats in `ExamNode` |
| 5A | Lamport logical clock | `LamportClock`, `Event` order, audit log |
| 5B | NTP clock synchronisation | `ClockSync`, `SimClock` (jump forward, slew back) |
| 6 | Web service | `WebService`: REST API and dashboard (JAX-WS is not in Java 11+) |
| Unit 4 | Replication and recovery | `AnswerLog` (write-ahead log), `ResultStore` (replica at the coordinator) |

## Nodes and ports (all on 127.0.0.1)

| Id | Node | Peer TCP | RMI registry | HTTP | Simulated clock error |
|---|---|---|---|---|---|
| 1 | Pune centre | 7001 | 1101 | 8081 | +4 s, +80 ppm |
| 2 | Mumbai centre | 7002 | 1102 | 8082 | -7 s, -60 ppm |
| 3 | Nagpur centre | 7003 | 1103 | 8083 | +2 s, +40 ppm |
| 4 | Head Office (first coordinator) | 7004 | 1104 | 8084 | none |

Peer messages are one text line, `TYPE|from|lamport|payload`: `HEARTBEAT`, `TIME`, `ELECTION`, `OK`,
`COORDINATOR`, `GROUP` (START, WARN, STOP), `REPLICATE`.

## Run it

Needs JDK 8 or later, from `JAVA_HOME` or on the `PATH`.

```bash
scripts/build.sh              # compile into out/ (Java 8 compatible)
scripts/demo.sh               # full scenario, about 2.5 minutes, prints a PASS/FAIL table
scripts/demo.sh --baseline    # same exam with clock sync OFF, to compare closing times
scripts/stop.sh               # stop anything left running
```

`demo.sh` starts the four nodes, schedules a 2-minute exam, runs 9 simulated students and 1 typed student, kills
the coordinator 47 s into the exam, then collects results from the new coordinator and runs `examnet.Verify`.
Logs go to `logs/main/` and `logs/baseline/`.

By hand, in separate terminals:

```bash
J="java -Djava.rmi.server.hostname=127.0.0.1 -cp out"
$J examnet.ExamNode 4          # Head Office, then 1, 2 and 3 in their own terminals
curl "http://127.0.0.1:8084/api/schedule?startIn=10&duration=120"
$J examnet.StudentClient 1 PUN05          # type "3 B", then "finish"
kill <pid of node 4>                      # watch the election in the other terminals
```

Dashboard of any node: `http://127.0.0.1:808<id>/`. API: `/api/status`, `/api/results`, `/api/audit`.

## Files

```
src/examnet/   ExamNode (main node), Peer, Message, LamportClock, SimClock, ClockSync, BullyElection,
               ExamService, ExamServiceImpl, LoopbackSockets, Event, AnswerLog, ResultStore, Paper,
               WebService, StudentClient, Verify, Cluster, Log
questions.txt  the paper: 10 DCS questions
scripts/       env.sh, build.sh, demo.sh, stop.sh
```
