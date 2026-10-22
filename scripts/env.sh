# Finds the JDK (8 or later). Sourced by the other scripts. Set JAVA_HOME to pick a specific JDK.
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"; JAVAC="$JAVA_HOME/bin/javac"
else
  JAVA=java; JAVAC=javac
fi
# Every server socket binds to 127.0.0.1; RMI stubs must point there too.
JOPTS="-Djava.awt.headless=true -Djava.rmi.server.hostname=127.0.0.1 -cp out"
now_ms() { perl -MTime::HiRes -e 'printf "%d\n", Time::HiRes::time()*1000'; }
