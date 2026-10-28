import { useState } from "react";
import { apiJson, describeError } from "../api";
import { useT } from "../i18n";
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
  const t = useT();
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
          if (!f.resettable) return { ok: false, error: t("components.resetNotAllowed", { field: f.label }) };
          patch[f.key] = null;
          continue;
        }
        if (raw !== "true" && raw !== "false") return { ok: false, error: t("components.booleanExpected", { field: f.label }) };
        patch[f.key] = raw === "true";
      } else if (f.type === "multiselect") {
        if (mode !== "replace") continue;
        const opts = f.options ?? [];
        patch[f.key] = (sets[f.key] ?? []).filter((v) => opts.includes(v));
      } else if (mode === "reset") {
        if (!f.resettable) return { ok: false, error: t("components.resetNotAllowed", { field: f.label }) };
        patch[f.key] = null;
      } else {
        const raw = (values[f.key] ?? "").trim();
        if (raw === "") return { ok: false, error: t("components.valueRequired", { field: f.label }) };
        if (f.type === "number") {
          const n = Number(raw);
          if (!Number.isInteger(n)) return { ok: false, error: t("components.integerRequired", { field: f.label }) };
          patch[f.key] = n;
        } else {
          patch[f.key] = raw;
        }
      }
    }
    if (Object.keys(patch).length === 0) return { ok: false, error: t("components.noChanges") };
    if (Object.keys(patch).length > 24) return { ok: false, error: t("components.maxFields") };
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
      push(t("components.updatedToast", { resource, count: body.updated.length }));
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
      <div className="bulkbar" role="region" aria-label={t("components.bulkRegion")}>
        <span className="small">
          <strong>{selected.length}</strong> {t("components.selectedCount", { count: selected.length }).replace(String(selected.length), "").trim()}
        </span>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setOpen(true)}>
          {t("components.bulkEditBtn")}
        </button>
      </div>
      {open && (
        <Dialog title={t("components.dialogTitle", { resource, count: selected.length })} onClose={() => setOpen(false)} wide>
          {!confirming ? (
            <>
              <p className="muted small">
                {t("components.introText")}
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
                          <option value="__unchanged__">{t("components.unchangedOpt")}</option>
                          <option value="true">{t("components.enabledTrue")}</option>
                          <option value="false">{t("components.disabledFalse")}</option>
                          {f.resettable && (
                            <option value="__null__">
                              {t("components.resetOpt", { hint: f.resetHint ? ` (${f.resetHint})` : "" })}
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
                          <span>{t("components.replaceSet")}</span>
                        </label>
                        {!opts.length ? (
                          <p className="chip-empty">{t("components.noOption")}</p>
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
                      <label htmlFor={`bulk-${f.key}-mode`}>{t("components.modeLabel", { field: f.label })}</label>
                      <select
                        id={`bulk-${f.key}-mode`}
                        value={mode}
                        onChange={(e) => setMode(f.key, e.target.value as BulkMode)}
                      >
                        <option value="unchanged">{t("components.unchangedOpt")}</option>
                        <option value="set">{t("components.setValueOpt")}</option>
                        {f.resettable && (
                          <option value="reset">
                            {t("components.resetOpt", { hint: f.resetHint ? ` (${f.resetHint})` : "" })}
                          </option>
                        )}
                      </select>
                      {mode === "set" && (
                        <>
                          <label className="sr-only" htmlFor={`bulk-${f.key}`}>
                            {t("components.valueLabel", { field: f.label })}
                          </label>
                          <input
                            id={`bulk-${f.key}`}
                            type="text"
                            value={values[f.key] ?? ""}
                            placeholder={f.type === "number" ? t("components.intPlaceholder") : t("components.valPlaceholder")}
                            onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                          />
                        </>
                      )}
                      {mode === "reset" && (
                        <p className="muted small" role="note">
                          {t("components.willBeReset", { hint: f.resetHint ? ` (${f.resetHint})` : "" })}
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
                  {t("common.cancel")}
                </button>
                <button type="button" className="btn btn-primary" onClick={() => { setError(""); setConfirming(true); }}>
                  {t("components.reviewBtn")}
                </button>
              </div>
            </>
          ) : (
            <>
              <h3>{t("components.confirmSummary")}</h3>
              <p className="muted small">{t("components.elementsCount", { count: selected.length })}</p>
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
                    {t("components.legend")}
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
                  {t("components.backBtn")}
                </button>
                <button type="button" className="btn btn-primary" disabled={busy || !patchPreview} onClick={submit}>
                  {busy ? t("components.sendingBtn") : t("components.applyBtn")}
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
