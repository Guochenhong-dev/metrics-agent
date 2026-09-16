#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
APP_JAR=app/metrics-agent.jar
if [ -f target/metrics-agent.jar ]; then APP_JAR=target/metrics-agent.jar; fi
exec java -Dfile.encoding=UTF-8 -jar "$APP_JAR"
