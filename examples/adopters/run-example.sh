#!/usr/bin/env bash
# Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Runs one adopter example through both front doors (own host, and inside Apache Knox 3.0.0)
# against the real engine. --keep copies the result logs into examples/adopters/<adopter>/results.
#   ./run-example.sh flink [--keep]          (set AEGIS_KNOX_HOME for the Knox front door)
set -euo pipefail
adopter="${1:?usage: run-example.sh <adopter> [--keep]}"
keep="${2:-}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# Module names are relative to examples/pom.xml, which owns the real-engines profile.
case "$adopter" in
  flink)         module=aegis-validation;               test=FlinkFrontDoorsTest ;;
  spark)         module=aegis-validation;               test=SparkFrontDoorsTest ;;
  zookeeper)     module=aegis-validation;               test=ZooKeeperFrontDoorsTest ;;
  calcite)       module=aegis-validation;               test=CalciteFrontDoorsTest ;;
  kafka)         module=aegis-validation;               test=KafkaFrontDoorsTest ;;
  iceberg)       module=aegis-validation;               test=IcebergFrontDoorsTest ;;
  activemq)      module=aegis-validation;               test=ActiveMqFrontDoorsTest ;;
  couchdb)       module=aegis-validation;               test=CouchdbFrontDoorsTest ;;  # needs Docker
  hive)          module=aegis-validation;               test=HiveFrontDoorsTest ;;     # needs Docker (apache/hive)
  pinot)         module=aegis-validation;               test=PinotFrontDoorsTest ;;    # needs Docker
  ignite)        module=aegis-validation;               test=IgniteFrontDoorsTest ;;
  # needs AEGIS_MCP_PYTHON, AEGIS_NODE, AEGIS_MCP_FILESYSTEM_JS (existing-mcp-server/setup.sh)
  existing-mcp-server) module=aegis-validation;         test=ExistingMcpServerFrontDoorsTest ;;
  burr)          module=aegis-validation;               test=BurrFrontDoorsTest ;;   # needs AEGIS_BURR_PYTHON
  a2a)           module=aegis-validation;               test=A2aAgentFrontDoorsTest ;; # needs AEGIS_A2A_PYTHON
  solr)          module=aegis-validation;               test=SolrFrontDoorsTest ;;   # needs AEGIS_SOLR_HOME
  flume)         module=aegis-validation;               test=FlumeFrontDoorsTest ;;
  bookkeeper)    module=aegis-validation;               test=BookKeeperFrontDoorsTest ;;
  pulsar)        module=aegis-validation;               test=PulsarFrontDoorsTest ;; # needs AEGIS_PULSAR_HOME
  ranger)        module=aegis-validation;               test=RangerFrontDoorsTest ;;   # needs Docker (apache/ranger)
  spark-connect) module=aegis-validation-spark-connect; test=SparkConnectFrontDoorsTest ;;
  spark-iceberg) module=aegis-validation-spark-connect; test=SparkIcebergFrontDoorsTest ;; # docs/TUTORIAL-SPARK-ICEBERG.md
  *) echo "no runnable example for '$adopter'" >&2; exit 2 ;;
esac
if [ -z "${AEGIS_KNOX_HOME:-}" ]; then
  echo "WARNING: AEGIS_KNOX_HOME is not set: the Knox front door will be reported as SKIPPED, not passed." >&2
fi
# Build the root reactor first. Its POM intentionally does not declare the examples-only profile.
mvn -B -q -f "$root/pom.xml" -DskipTests install
# Knox copies typed source jars plus target/plugin.classpath from the root reactor (-Preal-engines on the source module).
case "$adopter" in
  zookeeper) mvn -B -q -f "$root/pom.xml" -Preal-engines -pl aegis-source-zookeeper -am -DskipTests package ;;
  a2a)       mvn -B -q -f "$root/pom.xml" -Preal-engines -pl aegis-source-a2a -am -DskipTests package ;;
esac
# Build the profile-specific artifacts through the lifecycle that creates plugin.classpath files. Then clean
# only the selected validation module, preserving dependency artifacts needed by the test lifecycle.
# These commands stay under set -e: a preparation failure must not continue into the test.
mvn -B -q -f "$root/examples/pom.xml" -Preal-engines -DskipTests install
mvn -B -q -f "$root/examples/pom.xml" -Preal-engines -pl "$module" clean
set +e
mvn -B -f "$root/examples/pom.xml" -Preal-engines -pl "$module" -am test "-Dtest=$test" -Dsurefire.failIfNoSpecifiedTests=false
status=$?
set -e
if [ "$keep" = "--keep" ]; then
  if [ "$status" -ne 0 ]; then
    echo "refusing to keep $adopter: Maven exited $status" >&2
    exit "$status"
  fi
  from="$root/examples/aegis-validation/target/adopter-results/$adopter"
  report="$root/examples/$module/target/surefire-reports/io.github.vaquarkhan.aegis.validation.$test.txt"
  line="$(grep -o 'Tests run: [0-9]*, Failures: [0-9]*, Errors: [0-9]*, Skipped: [0-9]*' "$report" 2>/dev/null | head -n 1 || true)"
  runs="$(printf '%s' "$line" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p')"
  fails="$(printf '%s' "$line" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p')"
  errors="$(printf '%s' "$line" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p')"
  skipped="$(printf '%s' "$line" | sed -n 's/.*Skipped: \([0-9]*\).*/\1/p')"
  if [ -z "$runs" ] || [ "$fails" != 0 ] || [ "$errors" != 0 ] || [ "$skipped" != 0 ] || [ "$runs" -lt 2 ]; then
    echo "refusing to keep $adopter: tests=${runs:-missing} failures=${fails:-missing} errors=${errors:-missing} skipped=${skipped:-missing}. Retained files were not changed." >&2
    exit 1
  fi
  for rel in own-host-transcript.log knox-transcript.log own-host-audit.jsonl knox-audit.jsonl \
      aegis/own-host.yaml aegis/knox-aegis.yaml aegis/knox-topology.xml HOW-TO-RUN.md; do
    if [ ! -s "$from/$rel" ]; then
      echo "refusing to keep $adopter: missing or empty $rel. Retained files were not changed." >&2
      exit 1
    fi
  done
  to="$here/$adopter/results"
  mkdir -p "$to"
  cp "$from"/*-transcript.log "$from"/*-audit.jsonl "$to"/
  if [ -f "$from/knox-logs/knox-ext-plugins.log" ]; then cp "$from/knox-logs/knox-ext-plugins.log" "$to"/; fi
  cp "$report" "$to/test-report.txt"
  # What the run used, recorded by the harness: Aegis YAML per front door, Knox topology, HOW-TO-RUN.md.
  if [ -d "$from/aegis" ]; then mkdir -p "$here/$adopter/aegis" && cp "$from"/aegis/* "$here/$adopter/aegis/"; fi
  if [ -d "$from/engine" ]; then mkdir -p "$here/$adopter/engine" && cp "$from"/engine/* "$here/$adopter/engine/"; fi
  if [ -f "$from/HOW-TO-RUN.md" ]; then cp "$from/HOW-TO-RUN.md" "$here/$adopter/"; fi
  echo "kept results in $to"
fi
exit $status
