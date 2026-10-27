import { useState } from "react";
import { apiJson, describeError } from "../api";
import type { BulkItem } from "../types";
import { Dialog, useToast } from "./ui";

export type BulkMode = "unchanged" | "set" | "reset" | "replace";

export interface BulkFieldDef {
  key: string;
  label: string;
  type: "boolean" | "number" | "text" | "multiselect";
  help?: string;
  /** Reset explicite (null) autorisé : colonne NULLABLE côté backend uniquement. */
  resettable?: boolean;
  /** Précision affichée, ex "réinitialise à NULL" ou "défaut : legacy". */
  resetHint?: string;
  /** Options du multiselect (remplacement d'ensemble). */
  options?: string[];
}

// Barre de bulk edit : options communes à plusieurs éléments.
// Sémantique backend (ConfigRepository.appendBulkSet / UserRepository,
// contrat v2 §2) : absent = inchangé ; présent + valeur = modification ;
// présent + null = RÉINITIALISATION, uniquement si la colonne est NULLABLE
// (sinon 400). Ensembles users : présent = REMPLACEMENT complet.
// Confirmation avec résumé avant envoi atomique.
export function BulkBar<T extends { version: number }>({
  resource,
  selected,
  fields,
  labelOf,
  onDone,
}: {
  resource: "connectors" | "profiles" | "listeners" | "users";
  selected: Array<{ id: string; item: T }>;
  fields: BulkFieldDef[];
  labelOf: (id: string) => string;
  onDone: () => void;
}) {
  const { push } = useToast();
  const [open, setOpen] = useState(false);
  const [modes, setModes] = useState<Record<string, BulkMode>>({});
  const [values, setValues] = useState<Record<string, string>>({});
  const [sets, setSets] = useState<Record<string, string[]>>({});
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  if (selected.length < 2) return null;

  const modeOf = (f: BulkFieldDef): BulkMode => modes[f.key] ?? "unchanged";

  function parsePatch(): { ok: true; patch: Record<string, unknown> } | { ok: false; error: string } {
    const patch: Record<string, unknown> = {};
    for (const f of fields) {
      const mode = modeOf(f);
      // Les booléens portent leur état dans values[] (pas de mode dédié).
      if (f.type !== "boolean" && mode === "unchanged") continue;
      if (f.type === "boolean") {
        const raw = (values[f.key] ?? "").trim();
        if (raw === "__unchanged__" || raw === "") continue;
        if (raw === "__null__") {
          if (!f.resettable) return { ok: false, error: `${f.label} : réinitialisation non autorisée` };
          patch[f.key] = null;
          continue;
        }
        if (raw !== "true" && raw !== "false") return { ok: false, error: `${f.label} : true/false ou inchangé` };
        patch[f.key] = raw === "true";
      } else if (f.type === "multiselect") {
        if (mode !== "replace") continue;
        const opts = f.options ?? [];
        patch[f.key] = (sets[f.key] ?? []).filter((v) => opts.includes(v));
      } else if (mode === "reset") {
        if (!f.resettable) return { ok: false, error: `${f.label} : réinitialisation non autorisée` };
        patch[f.key] = null;
      } else {
        const raw = (values[f.key] ?? "").trim();
        if (raw === "") return { ok: false, error: `${f.label} : valeur requise (ou repassez en « inchangé »)` };
        if (f.type === "number") {
          const n = Number(raw);
          if (!Number.isInteger(n)) return { ok: false, error: `${f.label} : entier requis` };
          patch[f.key] = n;
        } else {
          patch[f.key] = raw;
        }
      }
    }
    if (Object.keys(patch).length === 0) return { ok: false, error: "Aucune modification (tout est « inchangé »)" };
    if (Object.keys(patch).length > 24) return { ok: false, error: "24 champs maximum" };
    return { ok: true, patch };
  }

  async function submit() {
    const parsed = parsePatch();
    if (!parsed.ok) {
      setError(parsed.error);
      return;
    }
    setError("");
    setBusy(true);
    try {
      const items: BulkItem[] = selected.map((s) => ({ id: s.id, version: s.item.version }));
      const { body } = await apiJson<{ updated: BulkItem[] }>(`/api/admin/config/${resource}/bulk`, {
        method: "PUT",
        body: { items, patch: parsed.patch },
      });
      push(`Bulk ${resource} : ${body.updated.length} élément(s) mis à jour`);
      setOpen(false);
      setModes({});
      setValues({});
      setSets({});
      setConfirming(false);
      onDone();
    } catch (e) {
      const m = describeError(e);
      setError(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  const parsed = parsePatch();
  const patchPreview = parsed.ok ? parsed.patch : null;

  const setMode = (key: string, mode: BulkMode) => setModes((m) => ({ ...m, [key]: mode }));

  function toggleSetValue(key: string, opt: string, checked: boolean) {
    setSets((s) => {
      const cur = new Set(s[key] ?? []);
      if (checked) cur.add(opt);
      else cur.delete(opt);
      return { ...s, [key]: [...cur] };
    });
  }

  return (
    <>
      <div className="bulkbar" role="region" aria-label="Édition groupée">
        <span className="small">
          <strong>{selected.length}</strong> sélectionné(s)
        </span>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setOpen(true)}>
          Édition groupée…
        </button>
      </div>
      {open && (
        <Dialog title={`Édition groupée — ${resource} (${selected.length})`} onClose={() => setOpen(false)} wide>
          {!confirming ? (
            <>
              <p className="muted small">
                Options communes. « Inchangé » = champ absent du patch, ligne non touchée.
                « Réinitialiser » = remise à la valeur par défaut/NULL — <strong>uniquement
                proposée quand le backend l'autorise</strong> (sinon 400). Ensembles :
                « Remplacer » écrase tout l'ensemble (vide = vide l'ensemble).
                Application atomique : tout échoue si une ligne est en conflit de version.
              </p>
              <div className="form-grid cols-2">
                {fields.map((f) => {
                  const mode = modeOf(f);
                  if (f.type === "boolean") {
                    return (
                      <div className="field" key={f.key}>
                        <label htmlFor={`bulk-${f.key}`}>{f.label}</label>
                        <select
                          id={`bulk-${f.key}`}
                          value={values[f.key] ?? "__unchanged__"}
                          onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                        >
                          <option value="__unchanged__">— Inchangé —</option>
                          <option value="true">Activé (true)</option>
                          <option value="false">Désactivé (false)</option>
                          {f.resettable && (
                            <option value="__null__">
                              Réinitialiser{f.resetHint ? ` (${f.resetHint})` : ""}
                            </option>
                          )}
                        </select>
                        {f.help && <p className="muted small">{f.help}</p>}
                      </div>
                    );
                  }
                  if (f.type === "multiselect") {
                    const replacing = mode === "replace";
                    const opts = f.options ?? [];
                    return (
                      <fieldset className="field" key={f.key}>
                        <legend className="small">{f.label}</legend>
                        <label className="check-row">
                          <input
                            type="checkbox"
                            checked={replacing}
                            onChange={(e) => setMode(f.key, e.target.checked ? "replace" : "unchanged")}
                          />
                          <span>Remplacer l'ensemble (vide = vide l'ensemble)</span>
                        </label>
                        {!opts.length ? (
                          <p className="chip-empty">Aucune option disponible.</p>
                        ) : (
                          <div className="chip-group">
                            {opts.map((o) => (
                              <label className="chip" key={o} htmlFor={`bulk-${f.key}-${o}`}>
                                <input
                                  type="checkbox"
                                  id={`bulk-${f.key}-${o}`}
                                  checked={(sets[f.key] ?? []).includes(o)}
                                  disabled={!replacing}
                                  onChange={(e) => toggleSetValue(f.key, o, e.target.checked)}
                                />
                                {o}
                              </label>
                            ))}
                          </div>
                        )}
                        {f.help && <p className="muted small">{f.help}</p>}
                      </fieldset>
                    );
                  }
                  return (
                    <div className="field" key={f.key}>
                      <label htmlFor={`bulk-${f.key}-mode`}>{f.label} — mode</label>
                      <select
                        id={`bulk-${f.key}-mode`}
                        value={mode}
                        onChange={(e) => setMode(f.key, e.target.value as BulkMode)}
                      >
                        <option value="unchanged">— Inchangé —</option>
                        <option value="set">Définir une valeur</option>
                        {f.resettable && (
                          <option value="reset">
                            Réinitialiser{f.resetHint ? ` (${f.resetHint})` : ""}
                          </option>
                        )}
                      </select>
                      {mode === "set" && (
                        <>
                          <label className="sr-only" htmlFor={`bulk-${f.key}`}>
                            {f.label} — valeur
                          </label>
                          <input
                            id={`bulk-${f.key}`}
                            type="text"
                            value={values[f.key] ?? ""}
                            placeholder={f.type === "number" ? "Entier" : "Valeur"}
                            onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                          />
                        </>
                      )}
                      {mode === "reset" && (
                        <p className="muted small" role="note">
                          Sera réinitialisé{f.resetHint ? ` (${f.resetHint})` : ""}.
                        </p>
                      )}
                      {f.help && <p className="muted small">{f.help}</p>}
                    </div>
                  );
                })}
              </div>
              {error && (
                <p className="field-error" role="alert">
                  {error}
                </p>
              )}
              <div className="dialog-actions">
                <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
                  Annuler
                </button>
                <button type="button" className="btn btn-primary" onClick={() => { setError(""); setConfirming(true); }}>
                  Relire…
                </button>
              </div>
            </>
          ) : (
            <>
              <h3>Résumé de confirmation</h3>
              <p className="muted small">{selected.length} élément(s) :</p>
              <ul className="small">
                {selected.map((s) => (
                  <li key={s.id}>
                    <code className="mono">{labelOf(s.id)}</code> (v{s.item.version})
                  </li>
                ))}
              </ul>
              <h3>Patch</h3>
              {patchPreview ? (
                <>
                  <pre className="mono small patch-preview">{JSON.stringify(patchPreview, null, 2)}</pre>
                  <p className="muted small">
                    Légende : valeur = modification ; <code className="mono">null</code> = réinitialisation
                    ; tableau = remplacement complet de l'ensemble.
                  </p>
                </>
              ) : (
                <p className="field-error" role="alert">
                  {(parsed as { ok: false; error: string }).error}
                </p>
              )}
              {error && (
                <p className="field-error" role="alert">
                  {error}
                </p>
              )}
              <div className="dialog-actions">
                <button type="button" className="btn btn-ghost" onClick={() => setConfirming(false)}>
                  Retour
                </button>
                <button type="button" className="btn btn-primary" disabled={busy || !patchPreview} onClick={submit}>
                  {busy ? "Envoi…" : "Appliquer (atomique)"}
                </button>
              </div>
            </>
          )}
        </Dialog>
      )}
    </>
  );
}

// Case a cocher de selection avec label accessible.
export function SelectBox({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label: string;
}) {
  return (
    <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} aria-label={label} />
  );
}
