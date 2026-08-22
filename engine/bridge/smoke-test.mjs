/**
 * End to end check of the headless bridge, without an Android device.
 *
 * Boots the engine exactly like the app does, feeds it a file over HTTP the way
 * the shell serves picked files, and reads the result back through the base64
 * fallback path. Proves the bridge's fetch, pathfinding, conversion and output
 * plumbing in one go.
 *
 * Usage: node src/android/smoke-test.mjs [fromFormat] [toFormat]
 */

import { createServer } from "node:http";
import { createReadStream, existsSync, statSync } from "node:fs";
import { dirname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer";

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, "..", "..");
const distDir = join(projectRoot, "dist-android");

const fromFormat = process.argv[2] || "JSON";
const toFormat = process.argv[3] || "YAML";
const inputBody = Buffer.from(JSON.stringify({ hello: "world", list: [1, 2, 3] }, null, 2));

if (!existsSync(join(distDir, "engine.html"))) {
  console.error(`Engine build not found in ${distDir}. Run the android vite build first.`);
  process.exit(1);
}

const MIME = {
  ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript",
  ".json": "application/json", ".wasm": "application/wasm", ".css": "text/css",
  ".data": "application/octet-stream", ".sf2": "application/octet-stream",
  ".bin": "application/octet-stream", ".ico": "image/x-icon", ".svg": "image/svg+xml"
};

const server = createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, "http://localhost").pathname);

  if (path.startsWith("/io/")) {
    res.writeHead(200, { "Content-Type": "application/octet-stream" }).end(inputBody);
    return;
  }

  const target = normalize(join(distDir, path.replace(/^\/convert\//, "/")));
  if (!target.startsWith(distDir) || !existsSync(target) || !statSync(target).isFile()) {
    res.writeHead(404).end("Not Found");
    return;
  }
  const extension = target.slice(target.lastIndexOf("."));
  res.writeHead(200, {
    "Content-Type": MIME[extension] || "application/octet-stream",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Embedder-Policy": "require-corp"
  });
  createReadStream(target).pipe(res);
});

await new Promise(done => server.listen(0, "127.0.0.1", done));
const port = server.address().port;

const browser = await puppeteer.launch({
  headless: true,
  args: ["--no-sandbox", "--disable-setuid-sandbox"],
  ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {})
});
const page = await browser.newPage();

const chunks = [];
let resolveReady, resolveDone, rejectDone;
const ready = new Promise(r => { resolveReady = r; });
const finished = new Promise((r, j) => { resolveDone = r; rejectDone = j; });

await page.exposeFunction("__nativeEmit", json => {
  const event = JSON.parse(json);
  switch (event.type) {
    case "ready": resolveReady(event); break;
    case "attempt": console.log(`  path: ${event.path.join(" -> ")}`); break;
    case "step": console.log(`  step ${event.step}/${event.steps}: ${event.from} -> ${event.to} (${event.handler})`); break;
    case "dead-end": console.log(`  dead end: ${event.path.join(" -> ")} (${event.reason})`); break;
    case "done": resolveDone(event); break;
    case "failed": rejectDone(new Error(`conversion failed: ${event.message}`)); break;
    case "fatal": rejectDone(new Error(`engine fatal: ${event.message}`)); break;
    case "log": if (event.level === "error") console.log(`  [error] ${event.message}`); break;
  }
});
await page.exposeFunction("__nativeChunk", (_job, base64) => { chunks.push(Buffer.from(base64, "base64")); });
await page.evaluateOnNewDocument(() => {
  window.AndroidEngine = {
    emit: json => window.__nativeEmit(json),
    chunk: (job, base64) => window.__nativeChunk(job, base64)
  };
});

await page.goto(`http://127.0.0.1:${port}/convert/engine.html`, { waitUntil: "load" });
await page.evaluate(() => window.ConvertEngine.boot());

const { formats } = await ready;
const from = formats.find(f => f.format === fromFormat && f.from);
const to = formats.find(f => f.format === toFormat && f.to);
if (!from || !to) {
  console.error(`Could not find ${fromFormat} (from) and ${toFormat} (to) in ${formats.length} formats.`);
  process.exit(1);
}
console.log(`Converting ${from.format} -> ${to.format} using ${formats.length} known formats...`);

await page.evaluate((request) => window.ConvertEngine.convert(request), JSON.stringify({
  job: "smoke",
  inputs: [{ name: `input.${from.extension}`, url: `http://127.0.0.1:${port}/io/test` }],
  fromId: from.id,
  toId: to.id,
  simpleMode: true
}));

const done = await finished;
const output = Buffer.concat(chunks);
console.log(`\nOK: ${done.count} file, ${output.length} bytes via ${done.path.join(" -> ")}`);
console.log("---");
console.log(output.toString("utf8").slice(0, 400));

await browser.close();
server.close();
