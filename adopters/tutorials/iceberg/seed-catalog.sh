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
# Creates the tutorial fixture DIRECTLY against the catalog, not through Aegis, so the starting state
# is not itself a governed action: three namespaces (sales, marketing, finance) and four tables
# (sales.orders, sales.returns, marketing.campaigns, finance.ledger). Safe to run again.
#   ./seed-catalog.sh          (works in Git Bash on Windows too)
set -euo pipefail
base="${AEGIS_ICEBERG_REST_URL:-http://127.0.0.1:18181}"
schema='{"type":"struct","schema-id":0,"fields":[
  {"id":1,"name":"id","required":true,"type":"long"},
  {"id":2,"name":"item","required":false,"type":"string"},
  {"id":3,"name":"amount","required":false,"type":"double"}]}'
for ns in sales marketing finance; do
  curl -s -o /dev/null -X POST -H 'Content-Type: application/json' \
    "$base/v1/namespaces" --data "{\"namespace\":[\"$ns\"],\"properties\":{}}"
  echo "namespace $ns"
done
create() {
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    "$base/v1/namespaces/$1/tables" --data "{\"name\":\"$2\",\"schema\":$schema}")
  echo "table $1.$2 -> HTTP $code"
}
create sales orders
create sales returns
create marketing campaigns
create finance ledger
echo
echo "namespaces now: $(curl -s "$base/v1/namespaces")"
