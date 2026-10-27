import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, test, vi } from "vitest";
import { BulkBar } from "../components/BulkBar";
import { SecretField } from "../components/SecretField";
import { Pagination } from "../components/Pagination";
import { ToastProvider } from "../components/ui";

async function renderWithToast(ui: React.ReactElement) {
  return render(<ToastProvider>{ui}</ToastProvider>);
}

describe("BulkBar", () => {
  test("masqué si moins de 2 sélectionnés", async () => {
    await renderWithToast(
      <BulkBar
        resource="connectors"
        selected={[{ id: "a", item: { version: 1 } }]}
        fields={[{ key: "enabled", label: "Activé", type: "boolean" }]}
        labelOf={(id) => id}
        onDone={() => {}}
      />,
    );
    expect(screen.queryByText(/Édition groupée/)).toBeNull();
  });

  test("confirmation avec résumé avant envoi atomique", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      headers: new Headers({ "Content-Type": "application/json" }),
      json: async () => ({ updated: [{ id: "a", version: 2 }] }),
      text: async () => "{}",
      clone: () => ({ json: async () => ({}) }),
    }));
    vi.stubGlobal("fetch", fetchMock);
    await renderWithToast(
      <BulkBar
        resource="profiles"
        selected={[
          { id: "p1", item: { version: 1 } },
          { id: "p2", item: { version: 3 } },
        ]}
        fields={[{ key: "enabled", label: "Activé", type: "boolean" }]}
        labelOf={(id) => id}
        onDone={() => {}}
      />,
    );
    await user.click(screen.getByRole("button", { name: /Édition groupée/ }));
    const sel = screen.getByLabelText("Activé") as HTMLSelectElement;
    await user.selectOptions(sel, "true");
    await user.click(screen.getByRole("button", { name: /Relire/ }));
    // Résumé : items + patch JSON
    expect(screen.getByText("Résumé de confirmation")).toBeTruthy();
    expect(screen.getByText("p1").closest("li")?.textContent).toContain("v1");
    expect(screen.getByText("p2").closest("li")?.textContent).toContain("v3");
    expect(document.querySelector(".patch-preview")?.textContent).toContain('"enabled": true');
    await user.click(screen.getByRole("button", { name: /Appliquer/ }));
    expect(fetchMock).toHaveBeenCalledOnce();
    const call = fetchMock.mock.calls[0] as unknown as [string, { body: string }];
    const body = JSON.parse(call[1].body) as { items: Array<{ id: string; version: number }>; patch: object };
    expect(body.items).toEqual([
      { id: "p1", version: 1 },
      { id: "p2", version: 3 },
    ]);
    expect(body.patch).toEqual({ enabled: true });
    expect(call[0]).toBe("/api/admin/config/profiles/bulk");
    vi.unstubAllGlobals();
  });

  test("tout inchangé => erreur inline, pas d'envoi", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await renderWithToast(
      <BulkBar
        resource="users"
        selected={[
          { id: "u1", item: { version: 1 } },
          { id: "u2", item: { version: 1 } },
        ]}
        fields={[{ key: "enabled", label: "Activé", type: "boolean" }]}
        labelOf={(id) => id}
        onDone={() => {}}
      />,
    );
    await user.click(screen.getByRole("button", { name: /Édition groupée/ }));
    await user.click(screen.getByRole("button", { name: /Relire/ }));
    // Pas de patch => message d'erreur dans l'étape résumé
    expect(screen.getByRole("alert").textContent).toContain("inchangé");
    expect(fetchMock).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  test("3 modes : inchangé absent, valeur envoyée, reset nullable envoie null", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      headers: new Headers({ "Content-Type": "application/json" }),
      json: async () => ({ updated: [{ id: "c1", version: 2 }, { id: "c2", version: 2 }] }),
      text: async () => "{}",
      clone: () => ({ json: async () => ({}) }),
    }));
    vi.stubGlobal("fetch", fetchMock);
    await renderWithToast(
      <BulkBar
        resource="connectors"
        selected={[
          { id: "c1", item: { version: 1 } },
          { id: "c2", item: { version: 1 } },
        ]}
        fields={[
          { key: "priority", label: "priority 0-100", type: "number" },
          { key: "keepAliveSec", label: "keepAliveSec", type: "number", resettable: true, resetHint: "NULL" },
          { key: "proxyName", label: "proxyName", type: "text" },
        ]}
        labelOf={(id) => id}
        onDone={() => {}}
      />,
    );
    await user.click(screen.getByRole("button", { name: /Édition groupée/ }));
    // Champ non-nullable : pas d'option Réinitialiser.
    const prioMode = screen.getByLabelText("priority 0-100 — mode") as HTMLSelectElement;
    expect([...prioMode.options].map((o) => o.value)).toEqual(["unchanged", "set"]);
    // Champ nullable : 3 options.
    const kaMode = screen.getByLabelText("keepAliveSec — mode") as HTMLSelectElement;
    expect([...kaMode.options].map((o) => o.value)).toEqual(["unchanged", "set", "reset"]);
    // Définir priority=80, réinitialiser keepAliveSec, laisser proxyName inchangé.
    await user.selectOptions(prioMode, "set");
    await user.type(screen.getByLabelText("priority 0-100 — valeur"), "80");
    await user.selectOptions(kaMode, "reset");
    await user.click(screen.getByRole("button", { name: /Relire/ }));
    const preview = document.querySelector(".patch-preview")?.textContent ?? "";
    expect(preview).toContain('"priority": 80');
    expect(preview).toContain('"keepAliveSec": null');
    expect(preview).not.toContain("proxyName");
    await user.click(screen.getByRole("button", { name: /Appliquer/ }));
    const call = fetchMock.mock.calls[0] as unknown as [string, { body: string }];
    const body = JSON.parse(call[1].body) as { patch: Record<string, unknown> };
    expect(body.patch).toEqual({ priority: 80, keepAliveSec: null });
    expect("proxyName" in body.patch).toBe(false);
    vi.unstubAllGlobals();
  });

  test("multiselect : remplacement explicite, vide = vide l'ensemble", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      headers: new Headers({ "Content-Type": "application/json" }),
      json: async () => ({ updated: [{ id: "u1", version: 2 }] }),
      text: async () => "{}",
      clone: () => ({ json: async () => ({}) }),
    }));
    vi.stubGlobal("fetch", fetchMock);
    await renderWithToast(
      <BulkBar
        resource="users"
        selected={[
          { id: "u1", item: { version: 1 } },
          { id: "u2", item: { version: 1 } },
        ]}
        fields={[
          { key: "profiles", label: "profiles (ensemble)", type: "multiselect", options: ["hdplus", "sky"] },
        ]}
        labelOf={(id) => id}
        onDone={() => {}}
      />,
    );
    await user.click(screen.getByRole("button", { name: /Édition groupée/ }));
    // Non coché = inchangé : pas d'envoi possible.
    await user.click(screen.getByRole("button", { name: /Relire/ }));
    expect(screen.getByRole("alert").textContent).toContain("inchangé");
    await user.click(screen.getByRole("button", { name: "Retour" }));
    // Remplacement avec un seul profil.
    await user.click(screen.getByLabelText(/Remplacer l'ensemble/));
    await user.click(screen.getByLabelText("sky"));
    await user.click(screen.getByRole("button", { name: /Relire/ }));
    expect(document.querySelector(".patch-preview")?.textContent).toContain('"profiles": [\n    "sky"\n  ]');
    await user.click(screen.getByRole("button", { name: /Appliquer/ }));
    const call = fetchMock.mock.calls[0] as unknown as [string, { body: string }];
    const body = JSON.parse(call[1].body) as { patch: Record<string, unknown> };
    expect(body.patch).toEqual({ profiles: ["sky"] });
    expect(call[0]).toBe("/api/admin/config/users/bulk");
    vi.unstubAllGlobals();
  });
});

describe("SecretField", () => {
  test("mode référence par défaut + bascule valeur directe avec révélation volontaire", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    await renderWithToast(
      <SecretField label="passwordRef" value="env:FOO" onChange={onChange} purpose="connector-password" owner="conn-a" />,
    );
    expect((screen.getByDisplayValue("env:FOO") as HTMLInputElement).value).toBe("env:FOO");
    await user.click(screen.getByRole("button", { name: "Valeur directe" }));
    const input = screen.getByLabelText(/valeur directe/i) as HTMLInputElement;
    expect(input.type).toBe("password");
    await user.click(screen.getByRole("button", { name: "Révéler" }));
    expect((screen.getByLabelText(/valeur directe/i) as HTMLInputElement).type).toBe("text");
  });

  test("deskey invalide => erreur inline sans appel réseau", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await renderWithToast(
      <SecretField label="desKeyRef" value="" onChange={() => {}} purpose="listener-des-key" owner="li-1" kind="deskey" />,
    );
    await user.click(screen.getByRole("button", { name: "Valeur directe" }));
    await user.type(screen.getByLabelText(/valeur directe/i), "trop-court");
    await user.click(screen.getByRole("button", { name: "Matérialiser" }));
    expect(screen.getByRole("alert").textContent).toContain("28 caractères hex");
    expect(fetchMock).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  test("matérialisation OK => onChange(secretRef) + pas de valeur en clair transmise ensuite", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 201,
      headers: new Headers({ "Content-Type": "application/json" }),
      json: async () => ({ secretRef: "file:/s/csp-conn-a-abcd.pwd", purpose: "connector-password", owner: "conn-a" }),
      text: async () => "{}",
      clone: () => ({ json: async () => ({}) }),
    }));
    vi.stubGlobal("fetch", fetchMock);
    await renderWithToast(
      <SecretField label="passwordRef" value="" onChange={onChange} purpose="connector-password" owner="Conn A" />,
    );
    await user.click(screen.getByRole("button", { name: "Valeur directe" }));
    await user.type(screen.getByLabelText(/valeur directe/i), "s3cret-très-long");
    await user.click(screen.getByRole("button", { name: "Matérialiser" }));
    expect(onChange).toHaveBeenCalledWith("file:/s/csp-conn-a-abcd.pwd");
    const call = fetchMock.mock.calls[0] as unknown as [string, { body: string; headers: Headers }];
    expect(call[0]).toBe("/api/admin/secrets/materialize");
    expect(call[1].headers.get("X-CSP-Requested-With")).toBe("CSP-WebUI");
    vi.unstubAllGlobals();
  });
});

describe("Pagination", () => {
  test("affiche les numéros de page et le total d'éléments", async () => {
    const onPageChange = vi.fn();
    const onPageSizeChange = vi.fn();
    render(
      <Pagination
        page={2}
        totalPages={7}
        totalElements={163}
        pageSize={25}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
        label="connecteurs"
      />,
    );

    expect(screen.getByText(/163 connecteurs/)).toBeTruthy();
    expect(screen.getAllByText("2").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("7")).toBeTruthy();
  });

  test("interactions sur les boutons précédent, suivant, premier et dernier", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    const { rerender } = render(
      <Pagination
        page={2}
        totalPages={5}
        totalElements={100}
        pageSize={20}
        onPageChange={onPageChange}
        label="connecteurs"
      />,
    );

    await user.click(screen.getByRole("button", { name: "Page précédente" }));
    expect(onPageChange).toHaveBeenCalledWith(1);

    await user.click(screen.getByRole("button", { name: "Page suivante" }));
    expect(onPageChange).toHaveBeenCalledWith(3);

    await user.click(screen.getByRole("button", { name: "Première page" }));
    expect(onPageChange).toHaveBeenCalledWith(1);

    await user.click(screen.getByRole("button", { name: "Dernière page" }));
    expect(onPageChange).toHaveBeenCalledWith(5);

    // Sur page 1, précédent et premier doivent être désactivés
    rerender(
      <Pagination
        page={1}
        totalPages={5}
        totalElements={100}
        pageSize={20}
        onPageChange={onPageChange}
        label="connecteurs"
      />,
    );
    expect((screen.getByRole("button", { name: "Page précédente" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Première page" }) as HTMLButtonElement).disabled).toBe(true);
  });

  test("changement de taille de page", async () => {
    const user = userEvent.setup();
    const onPageSizeChange = vi.fn();
    render(
      <Pagination
        page={1}
        totalPages={4}
        totalElements={100}
        pageSize={25}
        onPageChange={() => {}}
        onPageSizeChange={onPageSizeChange}
        label="connecteurs"
      />,
    );

    const select = screen.getByLabelText("Nombre d'éléments par page") as HTMLSelectElement;
    await user.selectOptions(select, "50");
    expect(onPageSizeChange).toHaveBeenCalledWith(50);
  });
});
