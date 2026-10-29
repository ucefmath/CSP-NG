import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { apiJson, describeError, formatDate } from "../api";
import type { LogEntry, LogsResponse } from "../types";
import { Loading, useToast } from "../components/ui";
import { useT } from "../i18n";
import { compileLogFilter, splitHighlight } from "../logFilter";

interface Envelope<T> {
  instanceId: string;
  timestamp: string;
  schemaVersion: string;
  payload: T;
}

export function LogsPage() {
  const t = useT();
  const { push } = useToast();
  const [sources, setSources] = useState<string[]>(["main"]);
  const [selectedSource, setSelectedSource] = useState("main");
  const [minLevel, setMinLevel] = useState("INFO");
  const [isLive, setIsLive] = useState(true);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const [query, setQuery] = useState(() => searchParams.get("q") ?? "");
  const [regexMode, setRegexMode] = useState(() => searchParams.get("re") === "1");
  const [caseSensitive, setCaseSensitive] = useState(() => searchParams.get("cs") === "1");
  const [invert, setInvert] = useState(() => searchParams.get("inv") === "1");
  const [loggerFilter, setLoggerFilter] = useState(() => searchParams.get("logger") ?? "");

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

  // Filtre persisté dans l'URL (partage / signet), sans entrée d'historique à chaque frappe
  useEffect(() => {
    const next = new URLSearchParams(searchParams);
    const setOrDel = (k: string, v: string) => (v ? next.set(k, v) : next.delete(k));
    setOrDel("q", query);
    setOrDel("re", regexMode ? "1" : "");
    setOrDel("cs", caseSensitive ? "1" : "");
    setOrDel("inv", invert ? "1" : "");
    setOrDel("logger", loggerFilter);
    if (next.toString() !== searchParams.toString()) setSearchParams(next, { replace: true });
  }, [query, regexMode, caseSensitive, invert, loggerFilter, searchParams, setSearchParams]);

  const compiled = useMemo(
    () => compileLogFilter({ query, regex: regexMode, caseSensitive, invert }),
    [query, regexMode, caseSensitive, invert],
  );

  const loggers = useMemo(() => {
    const set = new Set<string>();
    for (const l of logs) if (l.logger) set.add(l.logger);
    if (loggerFilter) set.add(loggerFilter);
    return Array.from(set).sort();
  }, [logs, loggerFilter]);

  const visibleLogs = useMemo(() => {
    let out = loggerFilter ? logs.filter((l) => l.logger === loggerFilter) : logs;
    if (compiled.ok && compiled.active) {
      out = out.filter((l) => compiled.test(`[${(l.level ?? "").toUpperCase()}] [${l.logger ?? ""}] ${l.message ?? ""}`));
    }
    return out;
  }, [logs, loggerFilter, compiled]);
  const filtering = !!loggerFilter || (compiled.ok && compiled.active);
  const highlight = compiled.ok ? compiled.highlight : null;
  const filterTouched = !!query.trim() || regexMode || caseSensitive || invert || !!loggerFilter;

  const resetFilter = () => {
    setQuery("");
    setRegexMode(false);
    setCaseSensitive(false);
    setInvert(false);
    setLoggerFilter("");
  };

  const clearLogs = () => {
    setLogs([]);
    push(t("logs.toastReset"));
  };

  return (
    <div className="card logs-page">
      <div className="toolbar">
        <h1>{t("logs.title")}</h1>

        <div className="logs-controls">
          <label htmlFor="log-source" className="small muted">
            {t("logs.sourceLabel")}
          </label>
          <select
            id="log-source"
            className="select-sm"
            value={selectedSource}
            onChange={(e) => setSelectedSource(e.target.value)}
            aria-label={t("logs.sourceAria")}
          >
            {sources.map((s) => (
              <option key={s} value={s}>
                {s === "main" ? t("logs.sourceMain") : s}
              </option>
            ))}
          </select>

          <label htmlFor="log-level" className="small muted">
            {t("logs.levelLabel")}
          </label>
          <select
            id="log-level"
            className="select-sm"
            value={minLevel}
            onChange={(e) => setMinLevel(e.target.value)}
            aria-label={t("logs.levelAria")}
          >
            <option value="DEBUG">{t("logs.levelDebug")}</option>
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
            {isLive ? t("logs.liveActive") : t("logs.livePaused")}
          </button>

          <button type="button" className="btn btn-ghost btn-sm" onClick={clearLogs} aria-label={t("logs.clear")}>
            {t("logs.clear")}
          </button>

          <span className="small muted logs-counter" role="status" aria-live="polite">
            {filtering
              ? t("logs.filteredCount", { shown: visibleLogs.length, total: logs.length })
              : logs.length > 1 ? t("logs.lineCountPlural", { count: logs.length }) : t("logs.lineCountSingular", { count: logs.length })}
          </span>
        </div>
      </div>

      <div className="logs-controls logs-filter" role="search" aria-label={t("logs.filterAria")}>
        <label htmlFor="log-filter" className="small muted">
          {t("logs.filterLabel")}
        </label>
        <input
          id="log-filter"
          type="search"
          className={`search-input logs-filter-input${compiled.ok ? "" : " input-invalid"}`}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={regexMode ? t("logs.filterPlaceholderRegex") : t("logs.filterPlaceholderText")}
          aria-invalid={!compiled.ok}
          aria-describedby="log-filter-help"
          spellCheck={false}
          autoComplete="off"
        />
        <label className="check-row small">
          <input type="checkbox" checked={regexMode} onChange={(e) => setRegexMode(e.target.checked)} />
          <span>{t("logs.filterRegex")}</span>
        </label>
        <label className="check-row small">
          <input type="checkbox" checked={caseSensitive} onChange={(e) => setCaseSensitive(e.target.checked)} />
          <span>{t("logs.filterCase")}</span>
        </label>
        <label className="check-row small">
          <input type="checkbox" checked={invert} onChange={(e) => setInvert(e.target.checked)} />
          <span>{t("logs.filterInvert")}</span>
        </label>
        <label htmlFor="log-logger" className="small muted">
          {t("logs.loggerLabel")}
        </label>
        <select id="log-logger" className="select-sm" value={loggerFilter} onChange={(e) => setLoggerFilter(e.target.value)}>
          <option value="">{t("logs.loggerAll")}</option>
          {loggers.map((l) => (
            <option key={l} value={l}>{l}</option>
          ))}
        </select>
        {filterTouched && (
          <button type="button" className="btn btn-ghost btn-sm" onClick={resetFilter}>
            {t("logs.filterReset")}
          </button>
        )}
      </div>
      <p id="log-filter-help" className="small muted">
        {compiled.ok ? (regexMode ? t("logs.filterHelpRegex") : t("logs.filterHelpText")) : null}
      </p>
      {!compiled.ok && (
        <div className="field-error small" role="alert">
          {t("logs.filterInvalid", { error: compiled.error })}
        </div>
      )}

      {error && <div className="field-error small" role="alert">{error}</div>}

      <div
        className="terminal-window"
        ref={terminalRef}
        onScroll={handleScroll}
        role="region"
        aria-label={t("logs.consoleAria")}
        tabIndex={0}
      >
        {loading && logs.length === 0 ? (
          <div className="terminal-loading">
            <Loading />
          </div>
        ) : logs.length === 0 ? (
          <div className="terminal-empty">{t("logs.empty")}</div>
        ) : visibleLogs.length === 0 ? (
          <div className="terminal-empty">{t("logs.filterNoMatch")}</div>
        ) : (
          <div className="terminal-content mono">
            {visibleLogs.map((log) => {
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
                  <span className="log-msg">
                    {splitHighlight(log.message ?? "", highlight).map((seg, i) =>
                      seg.hit ? <mark key={i} className="log-hit">{seg.text}</mark> : <span key={i}>{seg.text}</span>,
                    )}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
