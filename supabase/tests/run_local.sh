#!/usr/bin/env bash
# Every migration on a throwaway local Postgres 16 (stub auth schema), then the SQL tests. Usage: run_local.sh [port]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
port="${1:-54329}"
dir="$(mktemp -d /tmp/forma-pg.XXXXXX)"
chown postgres "$dir" 2>/dev/null || true
as_pg() { if [ "$(id -u)" = 0 ]; then su postgres -s /bin/bash -c "$*"; else bash -c "$*"; fi; }
bin=/usr/lib/postgresql/16/bin
as_pg "$bin/initdb -D $dir/data -U postgres -A trust >/dev/null && $bin/pg_ctl -D $dir/data -o '-p $port -k $dir -c listen_addresses=' -l $dir/log -w start >/dev/null"
trap 'as_pg "$bin/pg_ctl -D $dir/data -m immediate stop >/dev/null" || true; rm -rf "$dir"' EXIT
psql() { command psql -X -q -v ON_ERROR_STOP=1 -h "$dir" -p "$port" -U postgres "$@"; }
psql -f "$here/stub_auth.sql"
for f in "$here"/../migrations/*.sql; do psql -f "$f"; done
for t in "$here"/test_*.sql; do echo "== $(basename "$t")"; psql -f "$t"; done
echo "all SQL tests passed"
