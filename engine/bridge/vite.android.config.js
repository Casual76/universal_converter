/**
 * Build config for the Android shell.
 *
 * Not part of upstream p2r3/convert: it extends the upstream config instead of
 * replacing it, so every WASM asset upstream adds is picked up automatically.
 * Only the entry point and output directory differ - the web UI is dropped and
 * `engine.html` (headless bridge) is built in its place.
 */

import { defineConfig, mergeConfig } from "vite";
import baseConfig from "./vite.config.js";

export default mergeConfig(baseConfig, defineConfig({
  build: {
    outDir: "dist-android",
    emptyOutDir: true,
    target: "es2022",
    // The engine ships enormous generated chunks; the warning is just noise.
    chunkSizeWarningLimit: 8192,
    rollupOptions: {
      input: "engine.html"
    }
  }
}));
