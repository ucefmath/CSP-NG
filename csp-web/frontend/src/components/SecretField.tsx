import { useId, useState } from "react";
import { apiJson, describeError, isValidDesKeyHex } from "../api";
import type { MaterializeResponse, SecretPurpose } from "../types";
import { Field, useToast } from "./ui";

// Champ secret : valeur directe (materialisee serveur en file:) OU reference
// existante env:/file: conservee. Aucune valeur lue/echoquee par GET.
export function SecretField({
  label,
  value,
  onChange,
  purpose,
  owner,
  kind = "password",
  required,
  help,
}: {
  label: string;
  value: string;
  onChange: (ref: string) => void;
  purpose: SecretPurpose;
  owner: string;
  kind?: "password" | "deskey";
  required?: boolean;
  help?: string;
}) {
  const { push } = useToast();
  const [mode, setMode] = useState<"ref" | "direct">(value && !isRef(value) ? "direct" : "ref");
  const [direct, setDirect] = useState("");
  const [revealed, setRevealed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const baseId = useId();
  const refId = `${baseId}-ref`;
  const directId = `${baseId}-direct`;

  function isRef(v: string) {
    return v.startsWith("env:") || v.startsWith("file:");
  }

  async function materialize() {
    setError("");
    const v = direct;
    if (!v) {
      setError("Saisissez une valeur à matérialiser");
      return;
    }
    if (kind === "deskey" && !isValidDesKeyHex(v)) {
      setError("Clé DES attendue : 28 caractères hex (14 octets, espaces/« : » tolérés)");
      return;
    }
    const slug = owner
      .toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/[^a-z0-9._-]+/g, "-")
      .replace(/^[._-]+/, "")
      .replace(/[._-]+$/, "")
      .slice(0, 64);
    if (!slug) {
      setError("Nom propriétaire invalide pour la matérialisation");
      return;
    }
    setBusy(true);
    try {
      const { body } = await apiJson<MaterializeResponse>("/api/admin/secrets/materialize", {
        method: "POST",
        body: { purpose, owner: slug, value: v },
      });
      onChange(body.secretRef);
      setDirect("");
      setRevealed(false);
      setMode("ref");
      push(`Secret matérialisé (${body.secretRef}) — pensez à Enregistrer`);
    } catch (e) {
      const m = describeError(e);
      setError(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="secret-field">
      <div className="seg" role="group" aria-label={`${label} : mode de saisie`}>
        <button
          type="button"
          className={mode === "ref" ? "seg-btn active" : "seg-btn"}
          aria-pressed={mode === "ref"}
          onClick={() => setMode("ref")}
        >
          Référence
        </button>
        <button
          type="button"
          className={mode === "direct" ? "seg-btn active" : "seg-btn"}
          aria-pressed={mode === "direct"}
          onClick={() => setMode("direct")}
        >
          Valeur directe
        </button>
      </div>
      {mode === "ref" ? (
        <Field label={label} htmlFor={refId} error={undefined}>
          <input
            id={refId}
            name={label}
            type="text"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            placeholder="env:NOM_VAR ou file:/chemin/absolu"
            required={required}
            autoComplete="off"
            spellCheck={false}
          />
          {help && <p className="muted small">{help}</p>}
        </Field>
      ) : (
        <Field label={`${label} — valeur directe (jamais stockée en clair, matérialisée serveur)`} htmlFor={directId} error={error || undefined}>
          <div className="secret-row">
            <input
              id={directId}
              type={revealed ? "text" : "password"}
              value={direct}
              onChange={(e) => setDirect(e.target.value)}
              autoComplete="new-password"
              spellCheck={false}
            />
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setRevealed((v) => !v)} aria-pressed={revealed}>
              {revealed ? "Masquer" : "Révéler"}
            </button>
            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={materialize}>
              {busy ? "…" : "Matérialiser"}
            </button>
          </div>
          <p className="muted small">
            Crée un nouveau fichier opaque 0600 côté serveur et remplace le champ par sa référence file:. Chaque
            appel crée un fichier distinct.
          </p>
        </Field>
      )}
    </div>
  );
}

// Affichage d'une ref existante sans jamais exposer de valeur.
export function SecretRefView({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">—</span>;
  return (
    <code className="mono small" title="Référence opaque — la valeur n'est jamais lue">
      {value}
    </code>
  );
}
