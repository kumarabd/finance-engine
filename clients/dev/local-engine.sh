#!/usr/bin/env bash
# A local finance-engine for the apps' Debug "dev engine" mode: real Postgres (timescaledb + pgvector) in Docker, the real
# engine on 127.0.0.1:18091 acting as the fixed user "dev_user" (DEV_VERIFIED_USER), no Clerk and no router.
#
#   ./local-engine.sh start     # database + engine (first run pulls the Postgres image)
#   ./local-engine.sh seed      # sample categories, tags, merchants and a month of spends
#   ./local-engine.sh reset     # drop everything and start clean
#   ./local-engine.sh stop
#
# iOS:     launch the Debug app with the argument  -dev-engine http://127.0.0.1:18091
# Android: ./gradlew :app:installDebug, then  adb shell am start -n org.nighthawklabs.treasure/.MainActivity --es dev_engine http://10.0.2.2:18091
set -euo pipefail
cd "$(dirname "$0")/../.."
PG=treasure-dev-pg; PORT=18091; DBPORT=15433; PIDFILE=/tmp/treasure-dev-engine.pid; LOG=/tmp/treasure-dev-engine.log
URL="postgres://postgres:pw@localhost:$DBPORT/treasure_dev?sslmode=disable"

stop_engine() { [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null || true; pkill -f "finance-api-dev" 2>/dev/null || true; rm -f "$PIDFILE"; }

start() {
  docker inspect "$PG" >/dev/null 2>&1 || docker run -d --name "$PG" -p "$DBPORT:5432" -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=treasure_dev timescale/timescaledb-ha:pg17 >/dev/null
  docker start "$PG" >/dev/null 2>&1 || true
  # A fresh image restarts Postgres once while initialising, so "accepting connections" is not enough: retry the real work.
  for _ in $(seq 1 60); do
    docker exec "$PG" psql -U postgres -d treasure_dev -qc "CREATE EXTENSION IF NOT EXISTS timescaledb; CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null 2>&1 && break
    sleep 2
  done
  stop_engine
  go build -o /tmp/finance-api-dev ./cmd/finance-api
  DATABASE_URL="$URL" LISTEN_ADDR="127.0.0.1:$PORT" DEV_VERIFIED_USER=dev_user nohup /tmp/finance-api-dev >"$LOG" 2>&1 &
  echo $! >"$PIDFILE"
  for _ in $(seq 1 30); do curl -sf "localhost:$PORT/readyz" >/dev/null && { echo "engine ready on http://127.0.0.1:$PORT"; return; }; sleep 1; done
  echo "engine did not become ready; see $LOG" >&2; exit 1
}

case "${1:-}" in
  start) start ;;
  seed) python3 clients/dev/seed.py "http://127.0.0.1:$PORT" ;;
  reset) stop_engine; docker rm -f "$PG" >/dev/null 2>&1 || true; start; python3 clients/dev/seed.py "http://127.0.0.1:$PORT" ;;
  stop) stop_engine; docker stop "$PG" >/dev/null 2>&1 || true ;;
  *) sed -n 2,13p "$0"; exit 1 ;;
esac
