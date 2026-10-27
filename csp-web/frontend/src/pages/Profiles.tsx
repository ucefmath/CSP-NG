import { useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, parseInteger } from "../api";
import type { Profile } from "../types";
import { BulkBar, SelectBox } from "../components/BulkBar";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";

const BULK_FIELDS = [
  { key: "enabled", label: "Activé", type: "boolean" as const },
  { key: "debug", label: "Debug", type: "boolean" as const },
  { key: "cacheOnly", label: "Cache seul", type: "boolean" as const },
  { key: "requireProviderMatch", label: "Provider requis", type: "boolean" as const, resettable: true, resetHint: "NULL" },
  { key: "checkEcmLength", label: "Vérifier longueur ECM", type: "boolean" as const, resettable: true, resetHint: "NULL" },
  { key: "maxCwWaitMs", label: "maxCwWaitMs", type: "number" as const },
  { key: "congestionLimitMs", label: "congestionLimitMs (vide = inchangé)", type: "number" as const, resettable: true, resetHint: "NULL (hérité)" },
  { key: "mismatchedCards", label: "mismatchedCards", type: "boolean" as const },
  { key: "serviceConflicts", label: "serviceConflicts", type: "number" as const },
  { key: "blockCaidMismatch", label: "blockCaidMismatch", type: "boolean" as const, resettable: true, resetHint: "NULL" },
  { key: "providerFilter", label: "providerFilter (vide = inchangé)", type: "text" as const, resettable: true, resetHint: "NULL" },
  { key: "servicesFile", label: "servicesFile (vide = inchangé)", type: "text" as const, resettable: true, resetHint: "NULL" },
  { key: "servicesFileFormat", label: "servicesFileFormat (vide = inchangé)", type: "text" as const, resettable: true, resetHint: "NULL" },
];

export function ProfilesPage() {
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [filter, setFilter] = useState("");
  const [editing, setEditing] = useState<Profile | null | "new">(null);
  const [deleting, setDeleting] = useState<Profile | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const list = useMemo(() => {
    const all = snap.data?.profiles ?? [];
    if (!filter.trim()) return all;
    const q = filter.toLowerCase();
    return all.filter((p) => p.name.toLowerCase().includes(q));
  }, [snap.data, filter]);

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;

  const toggle = (name: string, v: boolean) =>
    setSelected((s) => {
      const n = new Set(s);
      if (v) n.add(name);
      else n.delete(name);
      return n;
    });

  const selectedItems = list.filter((p) => selected.has(p.name)).map((p) => ({ id: p.name, item: p }));

  async function doDelete(p: Profile) {
    try {
      const res = await apiFetch(`/api/admin/config/profiles/${encodeURIComponent(p.name)}?version=${p.version}`, {
        method: "DELETE",
      });
      if (!res.ok) {
        let b: unknown = null;
        try {
          b = await res.json();
        } catch {
          b = null;
        }
        throw { status: res.status, ...((b ?? {}) as object) };
      }
      push("Profil supprimé");
      snap.reload();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <div className="toolbar">
        <h1>Profils</h1>
        <input
          type="search"
          className="search-input"
          placeholder="Rechercher…"
          aria-label="Rechercher un profil"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          Créer un profil
        </button>
      </div>
      <BulkBar
        resource="profiles"
        selected={selectedItems}
        fields={BULK_FIELDS}
        labelOf={(id) => id}
        onDone={() => {
          setSelected(new Set());
          snap.reload();
        }}
      />
      {!list.length ? (
        <Empty msg={snap.data?.profiles?.length ? "Aucun résultat" : "Aucun profil"} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>
                  <span className="sr-only">Sélection</span>
                </th>
                <th>Nom</th>
                <th>État</th>
                <th>CAID</th>
                <th>Network</th>
                <th>maxCwWait</th>
                <th>Version</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {list.map((p) => (
                <tr key={p.name}>
                  <td>
                    <SelectBox checked={selected.has(p.name)} onChange={(v) => toggle(p.name, v)} label={`Sélectionner ${p.name}`} />
                  </td>
                  <td className="mono">{p.name}</td>
                  <td>
                    <span className={p.enabled ? "badge badge-ok" : "badge badge-muted"}>
                      {p.enabled ? "activé" : "désactivé"}
                    </span>
                  </td>
                  <td className="mono">{p.caId ?? "—"}</td>
                  <td className="mono">{p.networkId ?? "—"}</td>
                  <td className="mono">{p.maxCwWaitMs ?? "—"}</td>
                  <td className="mono">v{p.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(p)}>
                      Éditer
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(p)}>
                      Supprimer
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <ProfileDialog
          existing={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            snap.reload();
          }}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title="Supprimer le profil"
          desc={`« ${deleting.name} » (v${deleting.version}) — irréversible.`}
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
    </div>
  );
}

function ProfileDialog({ existing, onClose, onSaved }: { existing: Profile | null; onClose: () => void; onSaved: () => void }) {
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    name: existing?.name ?? "",
    caId: String(existing?.caId ?? 0),
    networkId: String(existing?.networkId ?? 0),
    maxCwWaitMs: String(existing?.maxCwWaitMs ?? 5000),
    congestionLimitMs: existing?.congestionLimitMs != null ? String(existing.congestionLimitMs) : "",
    serviceConflicts: String(existing?.serviceConflicts ?? 0),
    servicesFile: existing?.servicesFile ?? "",
    providerFilter: existing?.providerFilter ?? "",
    servicesFileFormat: existing?.servicesFileFormat ?? "",
    enabled: existing?.enabled ?? true,
    debug: existing?.debug ?? false,
    cacheOnly: existing?.cacheOnly ?? false,
    mismatchedCards: existing?.mismatchedCards ?? false,
    requireProviderMatch: existing?.requireProviderMatch ?? false,
    checkEcmLength: existing?.checkEcmLength ?? false,
    blockCaidMismatch: existing?.blockCaidMismatch ?? false,
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const name = f.name.trim();
    if (!name) return setErr("Nom requis");
    const caId = parseInteger(f.caId, NaN);
    const networkId = parseInteger(f.networkId, NaN);
    const maxCwWaitMs = parseInteger(f.maxCwWaitMs, NaN);
    const serviceConflicts = parseInteger(f.serviceConflicts, NaN);
    if (Number.isNaN(caId)) return setErr("caId : nombre requis");
    if (Number.isNaN(networkId)) return setErr("networkId : nombre requis");
    if (!(maxCwWaitMs > 100)) return setErr("maxCwWaitMs > 100");
    if (Number.isNaN(serviceConflicts)) return setErr("serviceConflicts : nombre requis");
    const congStr = f.congestionLimitMs.trim();
    const congestionLimitMs = congStr === "" ? null : parseInteger(congStr, NaN);
    if (congStr !== "" && Number.isNaN(congestionLimitMs)) return setErr("congestionLimitMs : nombre requis");
    setBusy(true);
    try {
      const payload = {
        enabled: f.enabled,
        debug: f.debug,
        cacheOnly: f.cacheOnly,
        caId,
        networkId,
        requireProviderMatch: f.requireProviderMatch,
        checkEcmLength: f.checkEcmLength,
        maxCwWaitMs,
        congestionLimitMs,
        mismatchedCards: f.mismatchedCards,
        serviceConflicts,
        servicesFile: f.servicesFile.trim() || null,
        providerFilter: f.providerFilter.trim() || null,
        servicesFileFormat: f.servicesFileFormat.trim() || null,
        blockCaidMismatch: f.blockCaidMismatch,
        version: existing?.version ?? 1,
      };
      const { body } = await apiJson<{ version: number }>(`/api/admin/config/profiles/${encodeURIComponent(name)}`, {
        method: "PUT",
        body: payload,
      });
      push(`Profil ${isEdit ? "mis à jour" : "créé"} (v${body.version})`);
      onSaved();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? "Éditer le profil" : "Créer un profil"} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <section aria-label="Essentiel">
          <h3>Essentiel</h3>
          <div className="form-grid cols-2">
            <Field label="Nom" htmlFor="pf-name">
              <input id="pf-name" value={f.name} onChange={set("name")} disabled={isEdit} required />
            </Field>
            <Field label="caId" htmlFor="pf-caId">
              <input id="pf-caId" type="number" value={f.caId} onChange={set("caId")} required />
            </Field>
            <Field label="networkId" htmlFor="pf-net">
              <input id="pf-net" type="number" value={f.networkId} onChange={set("networkId")} required />
            </Field>
            <Field label="maxCwWaitMs" htmlFor="pf-wait">
              <input id="pf-wait" type="number" value={f.maxCwWaitMs} onChange={set("maxCwWaitMs")} required />
            </Field>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.enabled)} onChange={set("enabled")} /><span>Activé</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.cacheOnly)} onChange={set("cacheOnly")} /><span>Cache seul</span></label>
          </div>
        </section>
        <section aria-label="Avancé" className="section section-advanced">
          <h3>Avancé</h3>
          <div className="form-grid cols-2">
            <Field label="congestionLimitMs (vide = hérité)" htmlFor="pf-cong">
              <input id="pf-cong" type="number" value={String(f.congestionLimitMs)} onChange={set("congestionLimitMs")} />
            </Field>
            <Field label="serviceConflicts" htmlFor="pf-sc">
              <input id="pf-sc" type="number" value={f.serviceConflicts} onChange={set("serviceConflicts")} required />
            </Field>
            <Field label="servicesFile" htmlFor="pf-sf">
              <input id="pf-sf" value={f.servicesFile} onChange={set("servicesFile")} />
            </Field>
            <Field label="providerFilter" htmlFor="pf-pf">
              <input id="pf-pf" value={f.providerFilter} onChange={set("providerFilter")} />
            </Field>
            <Field label="servicesFileFormat" htmlFor="pf-sff">
              <input id="pf-sff" value={f.servicesFileFormat} onChange={set("servicesFileFormat")} />
            </Field>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.debug)} onChange={set("debug")} /><span>Debug</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.mismatchedCards)} onChange={set("mismatchedCards")} /><span>mismatchedCards</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.requireProviderMatch)} onChange={set("requireProviderMatch")} /><span>requireProviderMatch</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.checkEcmLength)} onChange={set("checkEcmLength")} /><span>checkEcmLength</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.blockCaidMismatch)} onChange={set("blockCaidMismatch")} /><span>blockCaidMismatch</span></label>
          </div>
          {isEdit && <p className="muted small">Version : v{existing.version} (envoyée automatiquement)</p>}
        </section>
        {err && (
          <p className="field-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Annuler
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? "…" : isEdit ? "Enregistrer" : "Créer"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
