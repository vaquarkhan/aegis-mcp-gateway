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
// Drive a governed Aegis gateway from Rust with the standard library only: no crates, no Cargo
// project, no HTTP client, no JSON library, no MCP SDK. Compile and run:
//
//     rustc -O aegis_client.rs -o aegis_client
//     ./aegis_client 127.0.0.1 8099
//
// Environment: ANALYST_TOKEN, OPERATOR_TOKEN
//
// The point of doing it the hard way: MCP 2026-07-28 over Streamable HTTP is a JSON-RPC document in an
// HTTP POST with no handshake and no session, so a governed Aegis gateway is reachable from anything
// that can open a socket. Nothing in this file knows what Aegis is.

use std::env;
use std::io::{Read, Write};
use std::net::TcpStream;

const PROTOCOL: &str = "2026-07-28";

/// One JSON-RPC call over a fresh connection. Returns the response body.
fn rpc(
    host: &str,
    port: u16,
    token: &str,
    method: &str,
    tool: Option<&str>,
    params: &str,
    id: u32,
) -> String {
    let body = format!(
        "{{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"{}\",\"params\":{}}}",
        id, method, params
    );
    let mut req = format!(
        "POST /mcp HTTP/1.1\r\nHost: {host}:{port}\r\nContent-Type: application/json\r\n\
         Accept: application/json\r\nAuthorization: Bearer {token}\r\n\
         MCP-Protocol-Version: {PROTOCOL}\r\nMcp-Method: {method}\r\n"
    );
    if let Some(t) = tool {
        req.push_str(&format!("Mcp-Name: {t}\r\n"));
    }
    req.push_str(&format!(
        "Content-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    ));
    req.push_str(&body);

    let mut stream = TcpStream::connect((host, port)).expect("connect to the gateway");
    stream.write_all(req.as_bytes()).expect("write request");
    let mut resp = String::new();
    stream.read_to_string(&mut resp).expect("read response");
    match resp.find("\r\n\r\n") {
        Some(i) => resp[i + 4..].to_string(),
        None => resp,
    }
}

/// Pulls one string value out of the response. There is no JSON parser in the standard library and
/// this demo adds no crates on purpose, so it searches. A real client would use serde_json.
fn string_field(json: &str, key: &str) -> String {
    let needle = format!("\"{key}\":\"");
    if let Some(i) = json.find(&needle) {
        let rest = &json[i + needle.len()..];
        if let Some(j) = rest.find('"') {
            return rest[..j].to_string();
        }
    }
    String::new()
}

/// Pulls one numeric or boolean value out of the response.
fn raw_field(json: &str, key: &str) -> String {
    let needle = format!("\"{key}\":");
    if let Some(i) = json.find(&needle) {
        let rest = &json[i + needle.len()..];
        let end = rest.find([',', '}']).unwrap_or(rest.len());
        return rest[..end].trim().trim_matches('"').to_string();
    }
    String::new()
}

fn meta() -> String {
    format!("\"_meta\":{{\"io.modelcontextprotocol/protocolVersion\":\"{PROTOCOL}\"}}")
}

fn call_tool(
    host: &str,
    port: u16,
    token: &str,
    name: &str,
    arguments: &str,
    id: u32,
    extra_meta: Option<&str>,
) -> String {
    let m = match extra_meta {
        Some(extra) => format!(
            "\"_meta\":{{\"io.modelcontextprotocol/protocolVersion\":\"{PROTOCOL}\",{extra}}}"
        ),
        None => meta(),
    };
    let params = format!("{{\"name\":\"{name}\",\"arguments\":{arguments},{m}}}");
    rpc(host, port, token, "tools/call", Some(name), &params, id)
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let host = args.get(1).cloned().unwrap_or_else(|| "127.0.0.1".to_string());
    let port: u16 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(8099);
    let analyst = env::var("ANALYST_TOKEN").expect("ANALYST_TOKEN");
    let operator = env::var("OPERATOR_TOKEN").expect("OPERATOR_TOKEN");

    println!("== rust {PROTOCOL}, std library only: no crates, no HTTP client, no JSON parser ==");

    let listed = rpc(&host, port, &analyst, "tools/list", None, &format!("{{{}}}", meta()), 1);
    println!(
        "tools/list as analyst   -> demo_list_things advertised: {}, demo_delete_thing advertised: {}",
        listed.contains("demo_list_things"),
        listed.contains("demo_delete_thing")
    );

    let read = call_tool(&host, port, &analyst, "demo_list_things", "{}", 2, None);
    println!("READ as analyst         -> orders present: {}", read.contains("orders"));

    let denied = call_tool(&host, port, &analyst, "demo_delete_thing", "{\"id\":\"t-1\"}", 3, None);
    println!(
        "DESTRUCTIVE as analyst  -> refused {} at step {}",
        string_field(&denied, "code"),
        raw_field(&denied, "step")
    );

    let needs = call_tool(&host, port, &operator, "demo_delete_thing", "{\"id\":\"t-1\"}", 4, None);
    println!(
        "DESTRUCTIVE as operator -> refused {} at step {}",
        string_field(&needs, "code"),
        raw_field(&needs, "step")
    );

    let dry = call_tool(
        &host,
        port,
        &operator,
        "demo_delete_thing",
        "{\"id\":\"t-1\"}",
        5,
        Some("\"io.github.vaquarkhan.aegis/dryRun\":true"),
    );
    println!(
        "dry run as operator     -> allowed, receipt present: {}",
        dry.contains("dryRunReceipt")
    );

    let bad = call_tool(&host, port, &analyst, "demo_list_things", "{\"unexpected\":1}", 6, None);
    println!(
        "undeclared argument     -> refused {} at step {}",
        string_field(&bad, "code"),
        raw_field(&bad, "step")
    );
    println!("rust client done");
}
