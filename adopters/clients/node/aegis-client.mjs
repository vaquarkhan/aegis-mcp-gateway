// Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Drive a governed Aegis gateway from Node, using only built-in fetch. No npm install, no MCP SDK.
//
//   node aegis-client.mjs http://127.0.0.1:8099/mcp
// Environment: ANALYST_TOKEN, OPERATOR_TOKEN

const PROTOCOL = '2026-07-28';
const META_VERSION = 'io.modelcontextprotocol/protocolVersion';
const AEGIS_DRY_RUN = 'io.github.vaquarkhan.aegis/dryRun';

async function rpc(url, token, method, params = {}, tool = null, id = 1) {
  const body = { jsonrpc: '2.0', id, method, params: { ...params } };
  body.params._meta = { ...(body.params._meta || {}), [META_VERSION]: PROTOCOL };
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    Authorization: `Bearer ${token}`,
    'MCP-Protocol-Version': PROTOCOL,
    'Mcp-Method': method,
  };
  if (tool) headers['Mcp-Name'] = tool;
  const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
  const text = await res.text();
  return text ? JSON.parse(text) : {};
}

const callTool = (url, token, name, args, id, meta) =>
  rpc(url, token, 'tools/call', meta ? { name, arguments: args, _meta: meta } : { name, arguments: args }, name, id);

const toolText = (r) => (r.result?.content ?? []).find((c) => c.type === 'text')?.text ?? '';

// Aegis puts the governance decision in result._meta so a client can show why it was refused.
const decision = (r) => {
  const meta = r.result?._meta ?? {};
  const key = Object.keys(meta).find((k) => k.endsWith('/decision'));
  return key ? meta[key] : {};
};

const url = process.argv[2] ?? 'http://127.0.0.1:8099/mcp';
const analyst = process.env.ANALYST_TOKEN;
const operator = process.env.OPERATOR_TOKEN;

console.log(`== node ${process.version}, built-in fetch only, MCP ${PROTOCOL}, no handshake and no session ==`);

const listed = await rpc(url, analyst, 'tools/list', {}, null, 1);
console.log('tools/list as analyst   ->', (listed.result?.tools ?? []).map((t) => t.name));

const read = await callTool(url, analyst, 'demo_list_things', {}, 2);
console.log('READ as analyst         ->', toolText(read).slice(0, 80));

const denied = await callTool(url, analyst, 'demo_delete_thing', { id: 't-1' }, 3);
console.log(`DESTRUCTIVE as analyst  -> refused ${decision(denied).code} at step ${decision(denied).step}`);

const needs = await callTool(url, operator, 'demo_delete_thing', { id: 't-1' }, 4);
console.log(`DESTRUCTIVE as operator -> refused ${decision(needs).code} at step ${decision(needs).step}`);

const dry = await callTool(url, operator, 'demo_delete_thing', { id: 't-1' }, 5, { [AEGIS_DRY_RUN]: true });
const dd = decision(dry);
console.log(`dry run as operator     -> allowed, receipt=${!!dd.dryRunReceipt} approvalRequest=${!!dd.approvalRequestId}`);

const bad = await callTool(url, analyst, 'demo_list_things', { unexpected: 1 }, 6);
console.log(`undeclared argument     -> refused ${decision(bad).code} at step ${decision(bad).step}`);
console.log('node client done');
