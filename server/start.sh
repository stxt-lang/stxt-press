#!/bin/sh
# Starts the server of this folder: stxt-press.jar with server.stxt, both next to this script, wherever the folder is.
# Java 17 or later: that of JAVA_HOME if it is set, or the java of the PATH. Ctrl+C stops it.
set -e
cd "$(dirname "$0")"

if [ ! -f stxt-press.jar ]; then
    echo "start.sh: no stxt-press.jar in $(pwd); in the project of stxt-press, mvn package puts it here" >&2
    exit 1
fi

java=java
if [ -n "$JAVA_HOME" ]; then
    java="$JAVA_HOME/bin/java"
fi
exec "$java" -jar stxt-press.jar start --config server.stxt
