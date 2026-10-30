// Serveur statique E2E : sert le bundle packagé target/frontend-dist sous /admin/
// avec fallback SPA limité aux routes HTML connues (miroir AdminSpaFallbackResource)
// et CSP stricte pour prouver que la page fonctionne sans unsafe-inline.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = process.env.E2E_DIST || path.resolve(__dirname, "../../target/frontend-dist");
const PORT = Number(process.env.E2E_PORT || 4179);

const SPA_ROUTES = new Set([
  "overview", "general", "cache", "cache-etat", "cacheex", "cluster",
  "profiles", "listeners", "catalog", "connectors", "proxys", "relais",
  "dcw", "cw-cycle", "users", "logs", "login",
]);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
};

const CSP =
  "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; " +
  "connect-src 'self'; object-src 'none'; base-uri 'none'; " +
  "frame-ancestors 'none'; form-action 'self'";

function send(res, status, body, type) {
  res.writeHead(status, {
    "Content-Type": type,
    "Content-Security-Policy": CSP,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(body);
}

function serveFile(res, file) {
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    send(res, 404, "Not Found", "text/plain; charset=utf-8");
    return;
  }
  send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] || "application/octet-stream");
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || "/", "http://localhost");
  const p = url.pathname;
  if (p === "/admin" || p === "/admin/") {
    serveFile(res, path.join(DIST, "index.html"));
    return;
  }
  if (p.startsWith("/admin/")) {
    const rest = p.slice("/admin/".length);
    if (!rest.includes("/")) {
      if (SPA_ROUTES.has(rest)) {
        serveFile(res, path.join(DIST, "index.html"));
        return;
      }
      send(res, 404, "Not Found", "text/plain; charset=utf-8");
      return;
    }
    serveFile(res, path.join(DIST, rest));
    return;
  }
  send(res, 404, "Not Found", "text/plain; charset=utf-8");
});

if (!fs.existsSync(path.join(DIST, "index.html"))) {
  console.error(`E2E: bundle absent (${DIST}) — lancez d'abord 'npm run build'.`);
  process.exit(1);
}

server.listen(PORT, "127.0.0.1", () => {
  console.log(`E2E static server: http://127.0.0.1:${PORT}/admin/`);
});
