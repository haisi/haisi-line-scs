#!/usr/bin/env bash
# Boots this module directly via `java -cp` (not `spring-boot:run`/`spring-boot:start`, which fork
# a Maven subprocess we'd then have no single reliable PID to kill), screenshots the Angular app's
# Lines overview page against it, then shuts it back down. Invoked by exec-maven-plugin, bound to
# prepare-package -- see the maven-dependency-plugin and exec-maven-plugin comments in pom.xml for
# how the pieces around this fit together.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
port=8080
classpath="$script_dir/target/classes:$(cat "$script_dir/target/runtime-classpath.txt")"

# "local" activates shared/web/localdev/ (demo login + seed data) so there's something real for
# the screenshot to show.
java -cp "$classpath" -Dspring.profiles.active=local li.selman.optimisticlocking.OptimisticLockingApplication &
app_pid=$!

cleanup() {
  kill "$app_pid" 2>/dev/null || true
  wait "$app_pid" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 60); do
  if curl -sf "http://localhost:$port/" > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

node "$script_dir/../optimistic-locking-ui/scripts/screenshot-overview.mjs" \
  --out="$script_dir/target/generated-docs/images/overview.png" \
  --base-url="http://localhost:$port"
