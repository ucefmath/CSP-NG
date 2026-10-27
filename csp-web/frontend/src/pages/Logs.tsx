import { useCallback, useEffect, useRef, useState } from "react";
import { apiJson, describeError, formatDate } from "../api";
import type { LogEntry, LogsResponse } from "../types";
import { Loading, useToast } from "../components/ui";

interface Envelope<T> {
  instanceId: string;
  timestamp: string;
  schemaVersion: string;
  payload: T;
}

export function LogsPage() {
  const { push } = useToast();
  const [sources, setSources] = useState<string[]>(["main"]);
  const [selectedSource, setSelectedSource] = useState("main");
  const [minLevel, setMinLevel] = useState("INFO");
  const [isLive, setIsLive] = useState(true);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const afterSeqRef = useRef<number>(0);
  const generationRef = useRef<number>(0);
  const terminalRef = useRef<HTMLDivElement>(null);
  const autoScrollRef = useRef(true);

  // Charger les sources disponibles
  useEffect(() => {
    let unmounted = false;
    apiJson<Envelope<string[]>>("/api/admin/logs/sources")
      .then(({ body }) => {
        if (!unmounted && body?.payload) {
          setSources(body.payload);
        }
      })
      .catch(() => {
        // Source error fallback to default
      });
    return () => {
      unmounted = true;
    };
  }, []);

  // Détecter si l'utilisateur scroll vers le haut pour suspendre l'auto-scroll
  const handleScroll = () => {
    const el = terminalRef.current;
    if (!el) return;
    const isAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    autoScrollRef.current = isAtBottom;
  };

  // Scroll en bas si auto-scroll actif
  useEffect(() => {
    if (autoScrollRef.current && terminalRef.current) {
      terminalRef.current.scrollTop = terminalRef.current.scrollHeight;
    }
  }, [logs]);

  // Réinitialiser les logs quand la source ou le niveau change
  useEffect(() => {
    generationRef.current += 1;
    setLogs([]);
    afterSeqRef.current = 0;
    autoScrollRef.current = true;
    setLoading(true);
  }, [selectedSource, minLevel]);

  // Polling incrémental
  const fetchLogsBatch = useCallback(async () => {
    const fetchGen = generationRef.current;
    const fetchSource = selectedSource;
    try {
      const currentAfter = afterSeqRef.current;
      const url = `/api/admin/logs?source=${encodeURIComponent(fetchSource)}&after=${currentAfter}&limit=200&level=${encodeURIComponent(minLevel)}`;
      const { body } = await apiJson<Envelope<LogsResponse>>(url);
      const payload = body?.payload;
      if (payload) {
        if (fetchGen !== generationRef.current || payload.source !== fetchSource) {
          return; // ignorer réponse tardive d'une autre génération/source
        }
        afterSeqRef.current = payload.nextAfter;
        if (payload.items && payload.items.length > 0) {
          setLogs((prev) => {
            const next = currentAfter === 0 ? payload.items : [...prev, ...payload.items];
            return next.slice(-5000); // Garde-fou 5000 lignes
          });
        }
        setError(null);
      }
    } catch (e) {
      if (fetchGen === generationRef.current) {
        setError(describeError(e));
      }
    } finally {
      if (fetchGen === generationRef.current) {
        setLoading(false);
      }
    }
  }, [selectedSource, minLevel]);

  // Polling effect
  useEffect(() => {
    fetchLogsBatch();
    if (!isLive) return;

    const timer = setInterval(() => {
      fetchLogsBatch();
    }, 2000);

    return () => clearInterval(timer);
  }, [fetchLogsBatch, isLive]);

  const clearLogs = () => {
    setLogs([]);
    push("Affichage des logs réinitialisé");
  };

  return (
    <div className="card logs-page">
      <div className="toolbar">
        <h1>Journaux d'événements (Logs)</h1>

        <div className="logs-controls">
          <label htmlFor="log-source" className="small muted">
            Source :
          </label>
          <select
            id="log-source"
            className="select-sm"
            value={selectedSource}
            onChange={(e) => setSelectedSource(e.target.value)}
            aria-label="Source des logs"
          >
            {sources.map((s) => (
              <option key={s} value={s}>
                {s === "main" ? "Serveur principal (main)" : s}
              </option>
            ))}
          </select>

          <label htmlFor="log-level" className="small muted">
            Niveau min :
          </label>
          <select
            id="log-level"
            className="select-sm"
            value={minLevel}
            onChange={(e) => setMinLevel(e.target.value)}
            aria-label="Niveau minimum des logs"
          >
            <option value="DEBUG">DEBUG (tous)</option>
            <option value="INFO">INFO</option>
            <option value="WARN">WARN</option>
            <option value="ERROR">ERROR</option>
          </select>

          <button
            type="button"
            className={`btn btn-sm ${isLive ? "btn-primary" : "btn-ghost"}`}
            onClick={() => setIsLive((v) => !v)}
            aria-pressed={isLive}
          >
            {isLive ? "● En direct (Live)" : "⏸ Figé (Pause)"}
          </button>

          <button type="button" className="btn btn-ghost btn-sm" onClick={clearLogs} aria-label="Vider l'affichage">
            Vider l'affichage
          </button>

          <span className="small muted logs-counter">
            {logs.length} ligne{logs.length > 1 ? "s" : ""}
          </span>
        </div>
      </div>

      {error && <div className="field-error small" role="alert">{error}</div>}

      <div
        className="terminal-window"
        ref={terminalRef}
        onScroll={handleScroll}
        role="region"
        aria-label="Console des logs"
        tabIndex={0}
      >
        {loading && logs.length === 0 ? (
          <div className="terminal-loading">
            <Loading />
          </div>
        ) : logs.length === 0 ? (
          <div className="terminal-empty">Aucun log disponible pour cette source et ce niveau.</div>
        ) : (
          <div className="terminal-content mono">
            {logs.map((log) => {
              const lvl = log.level?.toUpperCase() || "INFO";
              let lvlClass = "log-info";
              if (lvl === "ERROR" || lvl === "SEVERE") lvlClass = "log-error";
              else if (lvl === "WARN" || lvl === "WARNING") lvlClass = "log-warn";
              else if (lvl === "DEBUG" || lvl === "FINE" || lvl === "TRACE") lvlClass = "log-debug";

              return (
                <div key={`${log.seq}-${log.timestamp}`} className={`log-line ${lvlClass}`}>
                  <span className="log-time">{formatDate(log.timestamp)}</span>
                  <span className={`log-badge ${lvlClass}`}>[{lvl.padEnd(5)}]</span>
                  <span className="log-logger">[{log.logger}]</span>
                  <span className="log-msg">{log.message}</span>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
