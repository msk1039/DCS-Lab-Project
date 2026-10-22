#!/usr/bin/env bash
# Runs the whole ExamNet scenario on this machine (every port on 127.0.0.1) and checks the result.
#   scripts/demo.sh             4 nodes, 10 students, coordinator crash mid-exam, about 2.5 minutes
#   scripts/demo.sh --baseline  clocks NOT synchronised and no crash, to compare closing times
set -u
cd "$(dirname "$0")/.."
. scripts/env.sh

BASE=0; [ "${1:-}" = "--baseline" ] && BASE=1
if [ $BASE = 1 ]; then RUN=logs/baseline; SYNC="--no-sync"; DUR=40; else RUN=logs/main; SYNC=""; DUR=120; fi
rm -rf "$RUN"; mkdir -p "$RUN"
scripts/build.sh || exit 1

PIDS=()
start_node() {
  "$JAVA" $JOPTS examnet.ExamNode "$1" $SYNC --dir "$RUN" > "$RUN/node-$1.log" 2>&1 &
  echo $! > "$RUN/pid-node-$1"; PIDS+=($!)
}
student() { # centre roll seed [--late]
  "$JAVA" $JOPTS examnet.StudentClient "$1" "$2" --auto "$3" ${4:-} > "$RUN/student-$2.log" 2>&1 &
  echo $! > "$RUN/pid-student-$2"; PIDS+=($!)
}
cleanup() {
  for f in "$RUN"/pid-*; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"; done
}
trap cleanup EXIT

echo "== Starting Head Office (id 4), then the centres Pune (1), Mumbai (2), Nagpur (3)"
start_node 4; sleep 2
start_node 1; start_node 2; start_node 3
sleep 7   # Bully election and the first NTP sync

echo "== Scheduling the exam on the coordinator: opens in 8 s, lasts $DUR s"
curl -s "http://127.0.0.1:8084/api/schedule?startIn=8&duration=$DUR"; echo

if [ $BASE = 1 ]; then
  student 1 PUN01 11; student 2 MUM01 21; student 3 NAG01 31
else
  student 1 PUN01 11; student 1 PUN02 12; student 1 PUN03 13
  student 2 MUM01 21; student 2 MUM02 22; student 2 MUM03 23 --late
  student 3 NAG01 31; student 3 NAG02 32; student 3 NAG03 33
  # One student typing answers by hand (the keyboard is simulated with a pipe).
  { sleep 11; for qa in "1 B" "2 C" "3 B" "4 A" "5 C" "4 B" "6 C" "7 C" "8 B" "9 A" "10 B"; do echo "$qa"; sleep 3; done; echo finish; } \
    | "$JAVA" $JOPTS examnet.StudentClient 1 PUN00 > "$RUN/student-PUN00.log" 2>&1 &
  echo $! > "$RUN/pid-student-PUN00"; PIDS+=($!)
fi

sleep 3
lsof -nP -a -p "$(IFS=,; echo "${PIDS[*]}")" -iTCP -sTCP:LISTEN > "$RUN/listeners.txt" 2>/dev/null

if [ $BASE = 0 ]; then
  sleep 52   # about 50 s into the exam
  echo "== Crashing the coordinator (Head Office, id 4) with kill -9"
  echo "#EV KILL real=$(now_ms) node=4" > "$RUN/demo-events.log"
  P4="$(cat "$RUN/pid-node-4")"; kill -9 "$P4"; wait "$P4" 2>/dev/null; rm -f "$RUN/pid-node-4"
  sleep $((8 + DUR - 52 - 3 + 8))
else
  sleep $((8 + DUR + 8 - 3))
fi

COORD=0
for id in 1 2 3 4; do
  curl -s "http://127.0.0.1:808$id/api/status" > "$RUN/status-$id.json" 2>/dev/null
  grep -q '"role": "coordinator"' "$RUN/status-$id.json" 2>/dev/null && COORD=$id
done
echo "== Results from the coordinator (node $COORD)"
curl -s "http://127.0.0.1:808$COORD/api/results.txt" > "$RUN/results.txt"
curl -s "http://127.0.0.1:808$COORD/api/audit.txt" > "$RUN/audit.txt"
curl -s "http://127.0.0.1:808$COORD/api/results" > "$RUN/results.json"
curl -s "http://127.0.0.1:808$COORD/api/audit" > "$RUN/audit.json"
grep '^STUDENT' "$RUN/results.txt"

if [ $BASE = 1 ]; then
  "$JAVA" -cp out examnet.Verify "$RUN" --baseline | tee "$RUN/verify.txt"
else
  "$JAVA" -cp out examnet.Verify "$RUN" | tee "$RUN/verify.txt"
fi
