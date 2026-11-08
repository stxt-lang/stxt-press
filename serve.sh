#!/bin/sh
# Starts the server of the folder server/ with the jar of target/, building the jar first if there is none.
#   ./serve.sh            the jar as it is
#   ./serve.sh --build    the jar built again from the sources (without the tests)
# Ctrl+C stops it.
set -e
cd "$(dirname "$0")"

if [ "$1" = "--build" ] || ! ls target/stxt-press-*.jar >/dev/null 2>&1; then
    mvn -q package -DskipTests
fi

jar=$(ls target/stxt-press-*.jar | head -n 1)
exec java -jar "$jar" serve --config server/server.stxt
