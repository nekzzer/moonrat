#!/bin/sh
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
find common client server -name '*.java' > sources.txt
javac -d out @sources.txt
rm sources.txt
"$(dirname "$(readlink -f "$(command -v javac)")")/jar" --create --file out/moonrat-agent.jar --main-class moonrat.client.MoonAgent \
    -C out moonrat/Protocol.class -C out 'moonrat/Protocol$Frame.class' -C out moonrat/client
echo "built ok"
echo "agent jar: out/moonrat-agent.jar"
echo "server: java -cp out moonrat.server.MoonRAT 8080 4444 <channel-key> 8443"
echo "agent : java -jar out/moonrat-agent.jar <server-ip> 4444 <channel-key>"
