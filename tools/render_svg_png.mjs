#!/usr/bin/env node
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

/** Render a self-contained SVG to PNG with the Puppeteer installation beside node.exe. */
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";

const [inputArg, outputArg, scaleArg = "2"] = process.argv.slice(2);
if (!inputArg || !outputArg) {
  console.error("usage: node tools/render_svg_png.mjs INPUT.svg OUTPUT.png [scale]");
  process.exit(2);
}
const input = path.resolve(inputArg);
const output = path.resolve(outputArg);
const scale = Number(scaleArg);
if (!Number.isFinite(scale) || scale < 1 || scale > 4) {
  throw new Error("scale must be between 1 and 4");
}
const source = fs.readFileSync(input, "utf8");
const tag = source.match(/<svg\b[^>]*\bwidth="(\d+)"[^>]*\bheight="(\d+)"/i);
if (!tag) {
  throw new Error("SVG must declare integer width and height");
}
const width = Number(tag[1]);
const height = Number(tag[2]);
const require = createRequire(import.meta.url);
const nodeHome = path.dirname(process.execPath);
const candidates = [
  path.join(nodeHome, "node_modules", "puppeteer"),
  path.join(nodeHome, "node_modules", "@mermaid-js", "mermaid-cli", "node_modules", "puppeteer"),
];
const puppeteerPath = candidates.find(candidate => fs.existsSync(candidate));
if (!puppeteerPath) {
  throw new Error("Puppeteer was not found beside node.exe or under Mermaid CLI");
}
const puppeteer = require(puppeteerPath);
const browser = await puppeteer.launch({headless: true, args: ["--no-sandbox", "--disable-gpu"]});
try {
  const page = await browser.newPage();
  await page.setViewport({width, height, deviceScaleFactor: scale});
  await page.goto(pathToFileURL(input).href, {waitUntil: "load"});
  const element = await page.$("svg");
  if (!element) {
    throw new Error("rendered document has no svg element");
  }
  fs.mkdirSync(path.dirname(output), {recursive: true});
  await element.screenshot({path: output, type: "png", omitBackground: false});
  const stat = fs.statSync(output);
  if (stat.size < 1024) {
    throw new Error(`rendered PNG is unexpectedly small: ${stat.size} bytes`);
  }
  console.log(`${path.basename(input)} -> ${path.basename(output)} (${width * scale}x${height * scale}, ${stat.size} bytes)`);
} finally {
  await browser.close();
}
