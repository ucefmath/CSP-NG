import { useState } from "react";
import { apiFetch, apiJson, describeError, formatDate } from "../api";
import type {
  CacheExStats,
  CacheHitsResponse,
  PeerQualityResponse,
  QuarantineResponse,
  QuorumResponse,
} from "../types";
import { Empty, Field, LoadError, Loading, useSnapshot, useToast } from "../components/ui";

// Stats CacheEx par pair. Sémantique modes OSCam asymétriques :
// connecteur sortant mode 2 = nous recevons ; mode 3 = nous émettons.
// Entrant user mode 2 = nous émettons vers le client ; mode 3 = nous recevons.

// Causes de rejet backend (CacheExStatsRegistry.REASONS, toujours pré-renseignées
// côté backend : les zéros affichés sont des compteurs réels, pas des valeurs fabriquées).
const REASON_LABELS: Record<string, string> = {
  loop: "boucle",
  hop: "hop",
  checksum: "checksum",
  filtered: "filtre",
  invalid: "invalide",
  queue: "file",
  flood: "flood",
  quarantine: "quarantaine",
  quorum: "quorum (en attente/divergent)",
};

export function formatReasons(reasons: Record<string, number> | null | undefined): string {
  if (!reasons) return "inconnu";
  const parts = Object.entries(reasons)
    .filter(([, v]) => (v ?? 0) > 0)
    .map(([k, v]) => `${REASON_LABELS[k] ?? k}:${v}`);
  return parts.length ? parts.join(" ") : "—";
}

function floodOf(reasons: Record<string, number> | null | undefined): string {
  if (!reasons) return "inconnu";
  if (!("flood" in reasons)) return "—";
  const v = reasons.flood ?? 0;
  return String(v);
}
function directionHint(mode: number | undefined, direction: string | undefined): string {
  if (direction === "outbound") {
    if (mode === 2) return "nous recevons du serveur";
    if (mode === 3) return "nous émettons vers le serveur";
  } else if (direction === "inbound") {
    if (mode === 2) return "nous émettons vers le client";
    if (mode === 3) return "nous recevons du client";
  }
  return "—";
}

export function CacheExPage() {
  const { push } = useToast();
  const [resetting, setResetting] = useState(false);
  const st = useSnapshot(async () => {
    const { body } = await apiJson<CacheExStats>("/api/admin/cacheex/stats");
    return body;
  });

  async function reset() {
    setResetting(true);
    try {
      const res = await apiFetch("/api/admin/cacheex/stats/reset", { method: "POST" });
      if (!res.ok) throw new Error(res.statusText);
      push("Compteurs CacheEx réinitialisés");
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setResetting(false);
    }
  }

  if (st.loading) return <Loading label="Chargement des stats CacheEx…" />;
  if (st.error) {
    const s = (st.error as { status?: number }).status;
    if (s === 404)
      return (
        <div className="card">
          <h1>Stats CacheEx</h1>
          <p className="muted">Endpoint indisponible sur ce backend (404) — fonctionnalité livrée par l'agent backend.</p>
          <button type="button" className="btn btn-sm" onClick={st.reload}>
            Réessayer
          </button>
        </div>
      );
    return <LoadError error={st.error} onRetry={st.reload} />;
  }
  const d = st.data;
  const pairs = d?.pairs ?? [];

  return (
    <>
    <div className="card">
      <div className="toolbar">
        <h1>Stats CacheEx par pair</h1>
        <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
          Actualiser
        </button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={resetting} onClick={reset}>
          {resetting ? "…" : "Réinitialiser compteurs"}
        </button>
      </div>
      <p className="muted small">
        Généré : {formatDate(d?.generatedAtMs)}. Modes asymétriques : mode 2 = réception des push, mode 3 = émission
        des push. Une paire sans trafic n'apparaît pas (jamais de zéros fabriqués).
      </p>
      {!pairs.length ? (
        <Empty msg="Aucune paire avec trafic — pas de stats à afficher (ce n'est pas zéro)." />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Pair</th>
                <th>Direction</th>
                <th>Proto</th>
                <th>Mode</th>
                <th>Sens</th>
                <th>État</th>
                <th>Poussés</th>
                <th>Reçus</th>
                <th>Jetés</th>
                <th>Flood</th>
                <th>Causes</th>
                <th>Dernier push</th>
                <th>Dernière réception</th>
              </tr>
            </thead>
            <tbody>
              {pairs.map((p) => (
                <tr key={`${p.direction}/${p.peerId}`}>
                  <td className="mono">{p.peerId}</td>
                  <td>{p.direction === "outbound" ? "sortant" : "entrant"}</td>
                  <td className="mono">{p.protocol ?? "—"}</td>
                  <td className="mono">{p.mode ?? "—"}</td>
                  <td className="small">{directionHint(p.mode, p.direction)}</td>
                  <td>
                    <span className={p.online ? "badge badge-ok" : "badge badge-muted"}>
                      {p.state ?? (p.online ? "connecté" : "—")}
                    </span>
                  </td>
                  <td className="mono">{p.pushedCw ?? "—"}</td>
                  <td className="mono">{p.receivedCw ?? "—"}</td>
                  <td className="mono">{p.droppedCw ?? "—"}</td>
                  <td className="mono small" title="Rejets flood (anti-rafale)">
                    {floodOf(p.dropReasons)}
                  </td>
                  <td className="mono small">{formatReasons(p.dropReasons)}</td>
                  <td className="small">{formatDate(p.lastPushAtMs)}</td>
                  <td className="small">{formatDate(p.lastReceiveAtMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">
        File d'émission maxSendQueue pleine → cause « file » (queue). Rejets « flood » :
        anti-rafale pair (colonne dédiée). « quarantaine » : pair exclu temporairement
        du routage (mode enforce). Filtre actif : gate effective du pair.
      </p>
    </div>
    <PeerQualitySection />
    <QuarantineSection />
    <QuorumSection />
    </>
  );
}

export function ratioLabel(bps: number | undefined): string {
  if (bps === undefined || bps === null) return "—";
  return `${(bps / 100).toFixed(2)} %`;
}

function PeerQualitySection() {
  const quality = useSnapshot(async () => {
    const { body } = await apiJson<PeerQualityResponse>("/api/admin/peer-quality/quality");
    return body;
  });
  const hits = useSnapshot(async () => {
    const { body } = await apiJson<CacheHitsResponse>("/api/admin/peer-quality/hits");
    return body;
  });
  const peers = quality.data?.peers ?? [];
  const origins = hits.data?.origins ?? [];
  return (
    <div className="card">
      <div className="toolbar">
        <h2>Qualité des pairs (fenêtre temporelle)</h2>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => { quality.reload(); hits.reload(); }}>
          Actualiser
        </button>
      </div>
      <p className="muted small">
        Population éligible : CW acceptées + invalides (contenu imputable). Drops de
        routage/protocole (boucle, hop, file, flood, filtre) exclus. Hits = réponses
        réellement servies par origine connue (« unknown » explicite, jamais confondu
        avec le volume reçu/poussé).
      </p>
      {quality.loading ? (
        <Loading label="Chargement qualité…" />
      ) : quality.error ? (
        <LoadError error={quality.error} onRetry={quality.reload} />
      ) : !peers.length ? (
        <Empty msg="Aucune donnée éligible dans la fenêtre — pas de qualité à afficher." />
      ) : (
        <div className="table-wrap">
          <table aria-label="Qualité des pairs">
            <thead>
              <tr>
                <th>Nœud</th>
                <th>Pair</th>
                <th>Acceptées</th>
                <th>Invalides</th>
                <th>Total éligible</th>
                <th>Ratio invalide</th>
                <th>Quarantiné</th>
              </tr>
            </thead>
            <tbody>
              {peers.map((p) => (
                <tr key={`${p.nodeId ?? "central"}/${p.peerId}`}>
                  <td className="mono small">{p.nodeId ?? "central"}</td>
                  <td className="mono">{p.peerId}</td>
                  <td className="mono">{p.accepted ?? "—"}</td>
                  <td className="mono">{p.invalid ?? "—"}</td>
                  <td className="mono">{p.total ?? "—"}</td>
                  <td className="mono">{ratioLabel(p.invalidRatioBps)}</td>
                  <td>{p.quarantined ? <span className="badge badge-warn">oui</span> : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h3>Hits servis par origine</h3>
      {hits.loading ? (
        <Loading label="Chargement hits…" />
      ) : hits.error ? (
        <LoadError error={hits.error} onRetry={hits.reload} />
      ) : !origins.length ? (
        <Empty msg="Aucun hit servi — pas d'attribution à afficher." />
      ) : (
        <div className="table-wrap">
          <table aria-label="Hits servis par origine">
            <thead>
              <tr>
                <th>Nœud</th>
                <th>Origine</th>
                <th>Hits</th>
                <th>Dernier hit</th>
              </tr>
            </thead>
            <tbody>
              {origins.map((o) => (
                <tr key={`${o.nodeId ?? "central"}/${o.origin}`}>
                  <td className="mono small">{o.nodeId ?? "central"}</td>
                  <td className="mono">{o.origin}</td>
                  <td className="mono">{o.hits ?? "—"}</td>
                  <td className="small">{formatDate(o.lastHitAtMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function QuarantineSection() {
  const { push } = useToast();
  const [saving, setSaving] = useState(false);
  const [releasing, setReleasing] = useState<string | null>(null);
  const [releaseId, setReleaseId] = useState("");
  const st = useSnapshot(async () => {
    const { body } = await apiJson<QuarantineResponse>("/api/admin/peer-quality/quarantine");
    return body;
  });
  const cfg = st.data?.config;
  const [form, setForm] = useState({ enabled: false, mode: "observe", minSamples: "50", invalidRatioBps: "5000", durationMs: "600000" });
  const [touched, setTouched] = useState(false);
  const shown = touched
    ? form
    : {
        enabled: cfg?.enabled ?? false,
        mode: cfg?.mode ?? "observe",
        minSamples: String(cfg?.minSamples ?? 50),
        invalidRatioBps: String(cfg?.invalidRatioBps ?? 5000),
        durationMs: String(cfg?.durationMs ?? 600000),
      };

  async function save() {
    setSaving(true);
    try {
      const { res } = await apiJson("/api/admin/peer-quality/quarantine", {
        method: "PUT",
        body: {
          enabled: shown.enabled,
          mode: shown.mode,
          minSamples: Number(shown.minSamples),
          invalidRatioBps: Number(shown.invalidRatioBps),
          windowMs: cfg?.windowMs ?? 300000,
          durationMs: Number(shown.durationMs),
          maxPeers: cfg?.maxPeers ?? 256,
          version: cfg?.version ?? 1,
        },
      });
      if (!res.ok) throw new Error(res.statusText);
      push("Quarantaine mise à jour (relayée aux workers)");
      setTouched(false);
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setSaving(false);
    }
  }

  async function release(peerId: string, nodeId?: string) {
    const id = peerId.trim();
    if (!id) return;
    if (nodeId && nodeId !== "central") {
      push("La levée manuelle s'applique uniquement au nœud central ; les workers expirent automatiquement.", "err");
      return;
    }
    setReleasing(id);
    try {
      const { res, body } = await apiJson<{ released?: boolean; note?: string }>("/api/admin/peer-quality/quarantine/release", {
        method: "POST",
        body: { peerId: id, nodeId: "central" },
      });
      if (!res.ok) throw new Error(res.statusText);
      if (body?.released) {
        push(`Quarantaine centrale levée : ${id}`);
      } else {
        push(body?.note ?? `Pair non trouvé dans la quarantaine centrale : ${id}`, "err");
      }
      setReleaseId("");
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setReleasing(null);
    }
  }

  const entries = st.data?.entries ?? [];
  return (
    <div className="card">
      <div className="toolbar">
        <h2>Quarantaine temporaire (opt-in)</h2>
        <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
          Actualiser
        </button>
      </div>
      <p className="muted small">
        Désactivée par défaut. Mode observe (défaut) : comptabilise sans exclure. Enforce :
        exclusion temporaire du routage uniquement — jamais un bannissement (source UDP
        usurpable). État mémoire borné (expiration + levée admin centrale). Levée manuelle
        applicable uniquement au nœud central ; la quarantaine des workers expire
        automatiquement en mémoire.
      </p>
      {st.loading ? (
        <Loading label="Chargement quarantaine…" />
      ) : st.error ? (
        <LoadError error={st.error} onRetry={st.reload} />
      ) : (
        <>
          <div className="form-grid">
            <Field label="Activée" htmlFor="q-enabled">
              <input
                id="q-enabled"
                type="checkbox"
                checked={shown.enabled}
                onChange={(e) => { setForm({ ...shown, enabled: e.target.checked }); setTouched(true); }}
              />
            </Field>
            <Field label="Mode" htmlFor="q-mode">
              <select
                id="q-mode"
                value={shown.mode}
                onChange={(e) => { setForm({ ...shown, mode: e.target.value }); setTouched(true); }}
              >
                <option value="observe">observe (sans exclusion)</option>
                <option value="enforce">enforce (exclusion routage)</option>
              </select>
            </Field>
            <Field label="Échantillons min" htmlFor="q-min">
              <input
                id="q-min"
                type="number"
                min={2}
                max={10000}
                value={shown.minSamples}
                onChange={(e) => { setForm({ ...shown, minSamples: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label="Ratio invalide (bps /10000)" htmlFor="q-ratio">
              <input
                id="q-ratio"
                type="number"
                min={0}
                max={10000}
                value={shown.invalidRatioBps}
                onChange={(e) => { setForm({ ...shown, invalidRatioBps: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label="Durée (ms)" htmlFor="q-dur">
              <input
                id="q-dur"
                type="number"
                min={10000}
                max={86400000}
                step={1000}
                value={shown.durationMs}
                onChange={(e) => { setForm({ ...shown, durationMs: e.target.value }); setTouched(true); }}
              />
            </Field>
          </div>
          <button type="button" className="btn btn-sm" disabled={saving} onClick={save}>
            {saving ? "…" : "Enregistrer (version " + (cfg?.version ?? "?") + ")"}
          </button>
          <h3>Entrées actives ({entries.length}) — observées : {st.data?.observed ?? "—"}, exclues : {st.data?.enforced ?? "—"}</h3>
          {!entries.length ? (
            <Empty msg="Aucune quarantaine active." />
          ) : (
            <div className="table-wrap">
              <table aria-label="Quarantaines actives">
                <thead>
                  <tr>
                    <th>Nœud</th>
                    <th>Pair</th>
                    <th>Raison</th>
                    <th>Expire</th>
                    <th>Mode</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((e) => {
                    const isWorker = e.nodeId && e.nodeId !== "central";
                    return (
                      <tr key={`${e.nodeId ?? "central"}/${e.peerId}`}>
                        <td className="mono small">{e.nodeId ?? "central"}</td>
                        <td className="mono">{e.peerId}</td>
                        <td className="mono small">{e.reason ?? "—"}</td>
                        <td className="small">{formatDate(e.expiresAtMs)}</td>
                        <td>{e.observedOnly ? "observée" : "exclue"}</td>
                    <td>
                      {isWorker ? (
                        <span
                          className="badge badge-muted small"
                          title="La quarantaine des workers expire automatiquement en mémoire"
                          aria-label="Expiration worker automatique en mémoire"
                        >
                          Auto-expire (worker)
                        </span>
                      ) : (
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          disabled={releasing === e.peerId}
                          onClick={() => release(e.peerId, e.nodeId)}
                        >
                          {releasing === e.peerId ? "…" : "Lever"}
                        </button>
                      )}
                    </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          <div className="form-grid">
            <Field label="Lever un pair (id exact)" htmlFor="q-release">
              <input
                id="q-release"
                type="text"
                value={releaseId}
                placeholder="connA"
                onChange={(e) => setReleaseId(e.target.value)}
              />
            </Field>
          </div>
          <button type="button" className="btn btn-ghost btn-sm" disabled={!releaseId.trim() || releasing !== null} onClick={() => release(releaseId, "central")}>
            Lever (centrale)
          </button>
        </>
      )}
    </div>
  );
}

function QuorumSection() {
  const { push } = useToast();
  const [saving, setSaving] = useState(false);
  const st = useSnapshot(async () => {
    const { body } = await apiJson<QuorumResponse>("/api/admin/peer-quality/quorum");
    return body;
  });
  const cfg = st.data?.config;
  const [form, setForm] = useState({ mode: "off", minOrigins: "2", profiles: "", caids: "" });
  const [touched, setTouched] = useState(false);
  const shown = touched
    ? form
    : {
        mode: cfg?.mode ?? "off",
        minOrigins: String(cfg?.minOrigins ?? 2),
        profiles: (cfg?.profiles ?? []).join(","),
        caids: (cfg?.caids ?? []).join(","),
      };

  async function save() {
    setSaving(true);
    try {
      const { res } = await apiJson("/api/admin/peer-quality/quorum", {
        method: "PUT",
        body: {
          mode: shown.mode,
          minOrigins: Number(shown.minOrigins),
          windowMs: cfg?.windowMs ?? 30000,
          maxKeys: cfg?.maxKeys ?? 1024,
          profiles: shown.profiles.split(",").map((s) => s.trim()).filter(Boolean),
          caids: shown.caids.split(",").map((s) => Number(s.trim())).filter((n) => Number.isFinite(n)),
          version: cfg?.version ?? 1,
        },
      });
      if (!res.ok) throw new Error(res.statusText);
      push("Quorum mis à jour (relayé aux workers)");
      setTouched(false);
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setSaving(false);
    }
  }

  const stats = st.data?.stats;
  const keys = st.data?.keys ?? [];
  return (
    <div className="card">
      <div className="toolbar">
        <h2>Quorum CacheEx ciblé</h2>
        <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
          Actualiser
        </button>
      </div>
      <p className="muted small">
        Off par défaut. Comparaison exacte de CW déjà valides pour une même identité ECM
        (profil/CAID/provider/SID/parité exacte/hash — sans corps ECM, limite des push).
        Minimum 2 origines de transport vérifiées distinctes (pas de preuve cryptographique).
        Enforce : pas d'injection sans quorum, jamais de repli silencieux.
      </p>
      {st.loading ? (
        <Loading label="Chargement quorum…" />
      ) : st.error ? (
        <LoadError error={st.error} onRetry={st.reload} />
      ) : (
        <>
          <div className="form-grid">
            <Field label="Mode" htmlFor="qq-mode">
              <select
                id="qq-mode"
                value={shown.mode}
                onChange={(e) => { setForm({ ...shown, mode: e.target.value }); setTouched(true); }}
              >
                <option value="off">off (désactivé)</option>
                <option value="observe">observe (injecte + comptabilise)</option>
                <option value="enforce">enforce (quorum exigé)</option>
              </select>
            </Field>
            <Field label="Origines min" htmlFor="qq-min">
              <input
                id="qq-min"
                type="number"
                min={2}
                max={8}
                value={shown.minOrigins}
                onChange={(e) => { setForm({ ...shown, minOrigins: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label="Profils (CSV, vide = tous)" htmlFor="qq-prof">
              <input
                id="qq-prof"
                type="text"
                value={shown.profiles}
                onChange={(e) => { setForm({ ...shown, profiles: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label="CAIDs (CSV, vide = tous)" htmlFor="qq-caids">
              <input
                id="qq-caids"
                type="text"
                value={shown.caids}
                onChange={(e) => { setForm({ ...shown, caids: e.target.value }); setTouched(true); }}
              />
            </Field>
          </div>
          <button type="button" className="btn btn-sm" disabled={saving} onClick={save}>
            {saving ? "…" : "Enregistrer (version " + (cfg?.version ?? "?") + ")"}
          </button>
          <h3>Compteurs</h3>
          <p className="mono small">
            clés : {stats?.keys ?? "—"} · observées : {stats?.observed ?? "—"} · quorum : {stats?.quorum ?? "—"} ·
            en attente : {stats?.pending ?? "—"} · divergences : {stats?.mismatch ?? "—"} · expirées : {stats?.expired ?? "—"}
          </p>
          <h3>Clés suivies ({keys.length})</h3>
          {!keys.length ? (
            <Empty msg="Aucune clé suivie dans la fenêtre." />
          ) : (
            <div className="table-wrap">
              <table aria-label="Clés quorum suivies">
                <thead>
                  <tr>
                    <th>Profil</th>
                    <th>CAID</th>
                    <th>SID</th>
                    <th>Parité</th>
                    <th>Origines</th>
                    <th>Quorum</th>
                  </tr>
                </thead>
                <tbody>
                  {keys.map((k, i) => (
                    <tr key={`${k.profile ?? ""}/${k.caid}/${k.sid}/${k.parity}/${k.dataHash}/${i}`}>
                      <td className="mono">{k.profile ?? "—"}</td>
                      <td className="mono">{k.caid ?? "—"}</td>
                      <td className="mono">{k.sid ?? "—"}</td>
                      <td className="mono">{k.parity ?? "—"}</td>
                      <td className="mono small">{(k.origins ?? []).join(" ") || "—"}</td>
                      <td>{k.quorumReached ? <span className="badge badge-ok">atteint</span> : "en attente"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
