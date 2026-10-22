#!/usr/bin/env bash
# Stops every ExamNet process started by demo.sh (uses the saved process ids only).
cd "$(dirname "$0")/.."
for f in logs/*/pid-*; do
  [ -f "$f" ] || continue
  kill "$(cat "$f")" 2>/dev/null && echo "stopped $(basename "$f")"
  rm -f "$f"
done
true
