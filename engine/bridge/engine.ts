/**
 * Headless entry point for the Android shell.
 *
 * This file is NOT part of upstream p2r3/convert. It is added on top of a
 * pristine checkout so the engine itself can be updated with a plain
 * `git pull` inside the submodule without ever hitting a merge conflict.
 *
 * It exposes the conversion engine as a plain function API driven from Kotlin:
 * no DOM, no UI, no user interaction. The pathfinding and conversion logic
 * mirrors `src/main.ts` so behaviour stays identical to the web app.
 */

import type { FileData, FileFormat, FormatHandler, ConvertPathNode } from "../FormatHandler.ts";
import handlers from "../handlers";
import { TraversionGraph } from "../TraversionGraph.ts";

/** Native bridge injected by the Android shell via addJavascriptInterface. */
interface NativeBridge {
  /** Delivers a JSON encoded engine event to the Kotlin side. */
  emit: (json: string) => void;
  /** Base64 fallback used when the WebMessage ArrayBuffer path is unavailable. */
  chunk: (job: string, base64: string) => void;
}

declare global {
  interface Window {
    AndroidEngine?: NativeBridge;
    ConvertEngine: typeof ConvertEngine;
  }
}

/** Flat list of every (format, handler) pair the engine can offer. */
const allOptions: Array<{ format: FileFormat; handler: FormatHandler }> = [];

/** Port used to stream binary output back to Kotlin without base64. */
let nativePort: MessagePort | null = null;

/** Jobs the native side asked to abort, checked between conversion steps. */
const cancelled = new Set<string>();

/** Job the pathfinder is currently working for, used to attribute its events. */
let searchingJob = "";
/** Candidate paths the pathfinder has looked at during the current job. */
let explored = 0;
let lastSearchEmit = 0;

const emit = (event: Record<string, unknown>) => {
  window.AndroidEngine?.emit(JSON.stringify(event));
};

/** Mirrors console output into the native log so failures stay diagnosable. */
const pipeConsole = () => {
  for (const level of ["log", "warn", "error"] as const) {
    const original = console[level].bind(console);
    console[level] = (...args: unknown[]) => {
      original(...args);
      emit({
        type: "log",
        level,
        message: args.map(a => {
          if (typeof a === "string") return a;
          if (a instanceof Error) return `${a.name}: ${a.message}`;
          try { return JSON.stringify(a); } catch { return String(a); }
        }).join(" ")
      });
    };
  }
  window.addEventListener("error", e => {
    emit({ type: "log", level: "error", message: `${e.message} @ ${e.filename}:${e.lineno}` });
  });
  window.addEventListener("unhandledrejection", e => {
    emit({ type: "log", level: "error", message: `unhandled rejection: ${e.reason}` });
  });
};

/** Receives the MessagePort the shell hands over right after page load. */
window.addEventListener("message", event => {
  if (event.data !== "__convert_native_port__") return;
  nativePort = event.ports[0] ?? null;
});

const pathNames = (path: ConvertPathNode[]) => path.map(node => node.format.format);

/* -------------------------------------------------------------------------- */
/* Boot                                                                        */
/* -------------------------------------------------------------------------- */

/**
 * Loads the precomputed format cache and builds the traversion graph.
 * No handler is initialised here, which is what keeps startup instant and
 * avoids pulling every WASM engine onto the device.
 */
async function boot () {

  allOptions.length = 0;
  window.supportedFormatCache = new Map();
  window.traversionGraph = new TraversionGraph();

  let cacheHit = true;
  try {
    const cacheJSON = await fetch("cache.json").then(r => r.json());
    window.supportedFormatCache = new Map(cacheJSON);
  } catch {
    cacheHit = false;
    console.warn("Format cache missing, falling back to initialising every handler.");
  }

  for (const handler of handlers) {
    if (!window.supportedFormatCache.has(handler.name)) {
      // Only reachable when the build time cache is missing or stale.
      if (cacheHit) console.warn(`Cache miss for handler "${handler.name}", initialising it.`);
      try {
        await handler.init();
      } catch (_) { continue; }
      if (handler.supportedFormats) {
        window.supportedFormatCache.set(handler.name, handler.supportedFormats);
      }
    }
    const supportedFormats = window.supportedFormatCache.get(handler.name);
    if (!supportedFormats) continue;
    for (const format of supportedFormats) {
      if (!format.mime) continue;
      allOptions.push({ format, handler });
    }
  }

  window.traversionGraph.init(window.supportedFormatCache, handlers);

  // The pathfinder can look at thousands of candidates before it finds one that
  // works. Reporting that (throttled, it fires per iteration) is what keeps the
  // progress bar alive during a search that would otherwise look frozen.
  window.traversionGraph.addPathEventListener((state, path) => {
    if (!searchingJob || state !== "searching") return;
    explored++;
    const now = performance.now();
    if (now - lastSearchEmit < 150) return;
    lastSearchEmit = now;
    emit({ type: "search", job: searchingJob, explored, candidate: pathNames(path) });
  });

  emit({
    type: "ready",
    cached: cacheHit,
    handlers: window.supportedFormatCache.size,
    formats: allOptions.map((option, index) => ({
      id: index,
      name: option.format.name,
      format: option.format.format,
      extension: option.format.extension,
      mime: option.format.mime,
      categories: Array.isArray(option.format.category)
        ? option.format.category
        : (option.format.category ? [option.format.category] : []),
      from: option.format.from,
      to: option.format.to,
      lossless: option.format.lossless ?? false,
      handler: option.handler.name
    }))
  });

}

/* -------------------------------------------------------------------------- */
/* Conversion                                                                  */
/* -------------------------------------------------------------------------- */

/** Path segments that failed during the current job, skipped on later attempts. */
let deadEndAttempts: ConvertPathNode[][] = [];
/** How many complete paths we have tried for the current job. */
let attempts = 0;

/**
 * Runs a single candidate conversion path end to end.
 * Returns null when any step fails, after recording the dead end.
 * Faithful port of `attemptConvertPath` in src/main.ts.
 */
async function attemptConvertPath (job: string, files: FileData[], path: ConvertPathNode[]) {

  for (const deadEnd of deadEndAttempts) {
    let isDeadEnd = true;
    for (let i = 0; i < deadEnd.length; i++) {
      if (path[i] === deadEnd[i]) continue;
      isDeadEnd = false;
      break;
    }
    if (isDeadEnd) return null;
  }

  attempts++;
  emit({ type: "attempt", job, attempt: attempts, path: pathNames(path) });

  for (let i = 0; i < path.length - 1; i++) {
    if (cancelled.has(job)) throw new Error("cancelled");

    const handler = path[i + 1].handler;
    emit({
      type: "step",
      job,
      step: i + 1,
      steps: path.length - 1,
      handler: handler.name,
      from: path[i].format.format,
      to: path[i + 1].format.format
    });

    try {
      let supportedFormats = window.supportedFormatCache.get(handler.name);
      if (!handler.ready) {
        emit({ type: "engine-init", job, handler: handler.name });
        await handler.init();
        if (!handler.ready) throw `Handler "${handler.name}" not ready after init.`;
        if (handler.supportedFormats) {
          window.supportedFormatCache.set(handler.name, handler.supportedFormats);
          supportedFormats = handler.supportedFormats;
        }
        // Tells the shell the engine is loaded and the actual work is starting,
        // so the UI can stop showing a download and go back to progress.
        emit({ type: "engine-ready", job, handler: handler.name });
      }
      if (!supportedFormats) throw `Handler "${handler.name}" doesn't support any formats.`;
      const inputFormat = supportedFormats.find(c =>
        c.from
        && c.mime === path[i].format.mime
        && c.format === path[i].format.format
      ) || (handler.supportAnyInput ? path[i].format : undefined);
      if (!inputFormat) throw `Handler "${handler.name}" doesn't support the "${path[i].format.format}" format.`;

      files = await handler.doConvert(files, inputFormat, path[i + 1].format);
      if (files.some(c => !c.bytes.length)) throw "Output is empty.";
    } catch (e) {
      if (cancelled.has(job)) throw new Error("cancelled");
      console.error(handler.name, `${path[i].format.format} to ${path[i + 1].format.format}`, e);
      const deadEndPath = path.slice(0, i + 2);
      deadEndAttempts.push(deadEndPath);
      window.traversionGraph.addDeadEndPath(deadEndPath);
      emit({ type: "dead-end", job, path: pathNames(deadEndPath), reason: String(e) });
      return null;
    }
  }

  return { files, path };

}

/** Walks candidate paths cheapest first until one converts successfully. */
async function tryConvertByTraversing (
  job: string,
  files: FileData[],
  from: ConvertPathNode,
  to: ConvertPathNode,
  simpleMode: boolean
) {
  deadEndAttempts = [];
  attempts = 0;
  explored = 0;
  searchingJob = job;
  window.traversionGraph.clearDeadEndPaths();
  try {
    for await (const path of window.traversionGraph.searchPath(from, to, simpleMode)) {
      if (cancelled.has(job)) throw new Error("cancelled");
      // Use the exact output format when the target handler supports it
      if (path.at(-1)?.handler === to.handler) path[path.length - 1] = to;
      const attempt = await attemptConvertPath(job, files, path);
      if (attempt) return attempt;
    }
  } finally {
    searchingJob = "";
  }
  return null;
}

/* -------------------------------------------------------------------------- */
/* Binary transfer                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Hands one output file to the native side. The header travels as a string and
 * the payload as a transferable ArrayBuffer on the same port, so ordering is
 * guaranteed and no base64 copy is ever made. Falls back to chunked base64 on
 * devices whose WebView cannot carry ArrayBuffer messages.
 */
function sendOutput (job: string, index: number, file: FileData) {

  if (nativePort) {
    nativePort.postMessage(JSON.stringify({ job, index, name: file.name, size: file.bytes.length }));
    // Copy into a standalone buffer: handlers may hand back a view into a
    // larger pooled buffer, and transferring that would neuter their memory.
    const copy = new Uint8Array(file.bytes);
    nativePort.postMessage(copy.buffer, [copy.buffer]);
    return;
  }

  emit({ type: "output-begin", job, index, name: file.name, size: file.bytes.length });
  const chunkSize = 0xC000;
  for (let offset = 0; offset < file.bytes.length; offset += chunkSize) {
    const slice = file.bytes.subarray(offset, offset + chunkSize);
    let binary = "";
    for (let i = 0; i < slice.length; i++) binary += String.fromCharCode(slice[i]);
    window.AndroidEngine?.chunk(job, btoa(binary));
  }
  emit({ type: "output-end", job, index });

}

/* -------------------------------------------------------------------------- */
/* Public API                                                                  */
/* -------------------------------------------------------------------------- */

interface ConvertRequest {
  job: string;
  /** Input files, served by the shell over the app assets domain. */
  inputs: Array<{ name: string; url: string }>;
  /** Index into the format list emitted by the ready event. */
  fromId: number;
  toId: number;
  simpleMode: boolean;
}

const ConvertEngine = {

  boot () {
    boot().catch(e => emit({ type: "fatal", message: String(e) }));
  },

  cancel (job: string) {
    cancelled.add(job);
  },

  convert (request: string) {
    const req: ConvertRequest = JSON.parse(request);
    (async () => {

      const input = allOptions[req.fromId];
      const output = allOptions[req.toId];
      if (!input || !output) throw new Error("Unknown format selection.");

      const files: FileData[] = [];
      for (const entry of req.inputs) {
        const response = await fetch(entry.url);
        if (!response.ok) throw new Error(`Could not read "${entry.name}".`);
        files.push({ name: entry.name, bytes: new Uint8Array(await response.arrayBuffer()) });
      }

      // Same format in and out: hand the bytes straight back.
      if (input.format.mime === output.format.mime && input.format.format === output.format.format) {
        files.forEach((file, index) => sendOutput(req.job, index, file));
        emit({ type: "done", job: req.job, count: files.length, path: [input.format.format] });
        return;
      }

      emit({ type: "searching", job: req.job });
      const result = await tryConvertByTraversing(req.job, files, input, output, req.simpleMode);
      if (!result) {
        emit({ type: "failed", job: req.job, message: "no-path" });
        return;
      }

      result.files.forEach((file, index) => sendOutput(req.job, index, file));
      emit({ type: "done", job: req.job, count: result.files.length, path: pathNames(result.path) });

    })().catch(e => {
      const error = e as Error;
      const message = String(error && error.message ? error.message : e);
      emit({ type: "failed", job: req.job, message: message === "cancelled" ? "cancelled" : message });
    }).finally(() => {
      cancelled.delete(req.job);
    });
  }

};

pipeConsole();
window.ConvertEngine = ConvertEngine;
emit({ type: "loaded" });
