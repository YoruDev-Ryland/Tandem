import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";

export default defineConfig({
  // Tests get each component's CSS injected, so jsdom can read the rules a component declares (getComputedStyle).
  plugins: [svelte(process.env.VITEST ? { compilerOptions: { css: "injected" } } : {})],
  server: {
    proxy: {
      "/v1": "http://localhost:5100",
      "/healthz": "http://localhost:5100",
    },
  },
  build: { outDir: "dist", emptyOutDir: true },
  resolve: process.env.VITEST ? { conditions: ["browser"] } : undefined,
  test: { environment: "jsdom", include: ["test/**/*.test.ts"] },
});
