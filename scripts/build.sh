#!/usr/bin/env bash
# Compiles ExamNet into out/. --release 8 keeps the code runnable on Java 8 lab machines.
set -eu
cd "$(dirname "$0")/.."
. scripts/env.sh
rm -rf out && mkdir -p out
REL="--release 8"
"$JAVAC" -version 2>&1 | grep -q '1\.8' && REL="-source 8 -target 8"
"$JAVAC" $REL -Xlint:-options -encoding UTF-8 -d out src/examnet/*.java
echo "Built $(ls out/examnet/*.class | wc -l | tr -d ' ') classes with $("$JAVAC" -version 2>&1)"
