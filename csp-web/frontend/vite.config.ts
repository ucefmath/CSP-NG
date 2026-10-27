import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Bundle admin servi sous /admin/ par Quarkus (META-INF/resources/admin).
// Noms de fichiers deterministes (pas de hash) pour un build Maven reproductible.
export default defineConfig({
  base: "/admin/",
  plugins: [react()],
  build: {
    outDir: "../target/frontend-dist",
    emptyOutDir: true,
    assetsInlineLimit: 0,
    modulePreload: false,
    rollupOptions: {
      output: {
        entryFileNames: "assets/app.js",
        chunkFileNames: "assets/[name].js",
        assetFileNames: (info) => {
          if (info.name && info.name.endsWith(".css")) return "assets/app.css";
          return "assets/[name][extname]";
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    exclude: ["e2e/**", "node_modules/**"],
  },
});
