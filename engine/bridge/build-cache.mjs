/**
 * Generates the precomputed format cache consumed by the Android shell.
 *
 * Every handler advertises its supported formats only after `init()`, and for
 * the heavy ones that means loading tens of megabytes of WASM. Doing that on a
 * phone at every launch is exactly the cost this app is trying to avoid, so we
 * pay it once here, at build time, and ship the result as `cache.json`.
 *
 * Upstream has the same idea in `buildCache.js`, but that script is written
 * against the Bun runtime and drives the web UI. This one runs on plain Node
 * against the headless engine bridge.
 *
 * Usage: node src/android/build-cache.mjs <outputPath> [--pretty]
 */

import { createServer } from "node:http";
import { createReadStream, existsSync, statSync } from "node:fs";
import { writeFile, mkdir } from "node:fs/promises";
import { dirname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer";

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, "..", "..");
const distDir = join(projectRoot, "dist-android");
const outputPath = resolve(process.argv[2] || join(projectRoot, "cache.json"));
const pretty = process.argv.includes("--pretty");

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
  const path = decodeURIComponent(new URL(req.url, "http://localhost").pathname)
    .replace(/^\/convert\//, "/");
  const target = normalize(join(distDir, path));
  if (!target.startsWith(distDir) || !existsSync(target) || !statSync(target).isFile()) {
    res.writeHead(404).end("Not Found");
    return;
  }
  const extension = target.slice(target.lastIndexOf("."));
  res.writeHead(200, {
    "Content-Type": MIME[extension] || "application/octet-stream",
    // Some engines probe for cross origin isolation before enabling threads.
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Embedder-Policy": "require-corp"
  });
  createReadStream(target).pipe(res);
});

await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
const port = server.address().port;

const launchOptions = {
  headless: true,
  args: ["--no-sandbox", "--disable-setuid-sandbox", "--js-flags=--max-old-space-size=4096"]
};
// Allow reusing an already installed browser instead of puppeteer's download.
if (process.env.CHROME_PATH) launchOptions.executablePath = process.env.CHROME_PATH;

const browser = await puppeteer.launch(launchOptions);
const page = await browser.newPage();

/** Resolved by the "ready" event the engine emits once every handler answered. */
let resolveReady;
const ready = new Promise((resolve, reject) => {
  resolveReady = resolve;
  setTimeout(() => reject(new Error("Timed out waiting for the engine to report ready.")), 15 * 60_000);
});

await page.exposeFunction("__nativeEmit", json => {
  const event = JSON.parse(json);
  if (event.type === "ready") resolveReady(event);
  else if (event.type === "fatal") console.error("engine fatal:", event.message);
  else if (event.type === "log" && event.level !== "log") console.log(`  [${event.level}] ${event.message}`);
});
await page.evaluateOnNewDocument(() => {
  window.AndroidEngine = { emit: json => window.__nativeEmit(json), chunk: () => {} };
});

page.on("pageerror", error => console.log(`  [pageerror] ${error.message}`));

console.log(`Booting the engine (this initialises every handler, expect a few minutes)...`);
await page.goto(`http://127.0.0.1:${port}/convert/engine.html`, { waitUntil: "load" });
await page.evaluate(() => window.ConvertEngine.boot());

const event = await ready;
const cache = await page.evaluate(() => JSON.stringify(Array.from(window.supportedFormatCache)));

await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, pretty ? JSON.stringify(JSON.parse(cache), null, 2) : cache);

console.log(`Cached ${event.handlers} handlers and ${event.formats.length} formats to ${outputPath}`);

await browser.close();
server.close();
