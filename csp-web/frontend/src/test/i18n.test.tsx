import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  DEFAULT_LANG,
  STORAGE_KEY,
  getLang,
  readStoredLang,
  setLang,
  translate,
  useT,
} from "../i18n";
import { NAMESPACES } from "../i18n/messages";

function Probe() {
  const t = useT();
  return <span data-testid="label">{t("common.save")}</span>;
}

beforeEach(() => {
  localStorage.clear();
  document.documentElement.lang = "";
  setLang(DEFAULT_LANG);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("langue stockée", () => {
  test("1. sans valeur dans localStorage, readStoredLang() retombe sur la langue par défaut", () => {
    localStorage.removeItem(STORAGE_KEY);

    expect(readStoredLang()).toBe("en");
    expect(readStoredLang()).toBe(DEFAULT_LANG);
  });

  test("2. readStoredLang() lit 'fr' et ignore une valeur inconnue", () => {
    localStorage.setItem(STORAGE_KEY, "fr");
    expect(readStoredLang()).toBe("fr");

    localStorage.setItem(STORAGE_KEY, "de");
    expect(readStoredLang()).toBe("en");
  });
});

describe("setLang", () => {
  test("3. persiste la langue, met à jour getLang() et <html lang>", () => {
    setLang("fr");
    expect(getLang()).toBe("fr");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("fr");
    expect(document.documentElement.lang).toBe("fr");

    setLang("en");
    expect(getLang()).toBe("en");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("en");
    expect(document.documentElement.lang).toBe("en");
  });
});

describe("translate", () => {
  test("4. traduit common.save dans les deux langues", () => {
    setLang("en");
    expect(translate("common.save")).toBe("Save");

    setLang("fr");
    expect(translate("common.save")).toBe("Enregistrer");
  });

  test("5. interpole les variables de common.version", () => {
    setLang("en");
    expect(translate("common.version", { v: 3 })).toBe("Version 3");

    setLang("fr");
    expect(translate("common.version", { v: 3 })).toBe("Version 3");
  });

  test("6. traduit les namespaces api et components en EN et FR", () => {
    setLang("en");
    expect(translate("api.defaultError")).toBe("Error");
    expect(translate("api.conflict409")).toBe("Version conflict — please reload (409)");
    expect(translate("api.sidOutOfRange", { token: "12345" })).toBe("SID out of range 0000-FFFF: 12345");
    expect(translate("components.firstPage")).toBe("First page");
    expect(translate("components.enterValue")).toBe("Enter a value to materialize");
    expect(translate("components.bulkEditBtn")).toBe("Bulk edit…");
    expect(translate("components.resetOpt", { hint: " (NULL)" })).toBe("Reset (NULL)");

    setLang("fr");
    expect(translate("api.defaultError")).toBe("Erreur");
    expect(translate("api.conflict409")).toBe("Conflit de version — rechargez (409)");
    expect(translate("api.sidOutOfRange", { token: "12345" })).toBe("SID hors plage 0000-FFFF : 12345");
    expect(translate("components.firstPage")).toBe("Première page");
    expect(translate("components.enterValue")).toBe("Saisissez une valeur à matérialiser");
    expect(translate("components.bulkEditBtn")).toBe("Édition groupée…");
    expect(translate("components.resetOpt", { hint: " (NULL)" })).toBe("Réinitialiser (NULL)");
  });

  test("6b. traduit les namespaces login, overview, general, cache, cluster en EN et FR", () => {
    setLang("en");
    expect(translate("login.username")).toBe("Username");
    expect(translate("login.password")).toBe("Password");
    expect(translate("login.submit")).toBe("Sign in");
    expect(translate("overview.title")).toBe("Overview");
    expect(translate("general.title")).toBe("General");
    expect(translate("cache.title")).toBe("Cache");
    expect(translate("cluster.title")).toBe("UDP Cluster");

    setLang("fr");
    expect(translate("login.username")).toBe("Identifiant");
    expect(translate("login.password")).toBe("Mot de passe");
    expect(translate("login.submit")).toBe("Se connecter");
    expect(translate("overview.title")).toBe("Vue d'ensemble");
    expect(translate("general.title")).toBe("Général");
    expect(translate("cache.title")).toBe("Cache");
    expect(translate("cluster.title")).toBe("Cluster UDP");
  });

  test("6c. traduit les namespaces logs, cacheEtat, catalog, cwCycle, proxys en EN et FR", () => {
    setLang("en");
    expect(translate("logs.title")).toBe("Event Logs");
    expect(translate("cacheEtat.title")).toBe("Cache State");
    expect(translate("catalog.title")).toBe("Card Catalog");
    expect(translate("cwCycle.title")).toBe("CW Cycle");
    expect(translate("cwCycle.caidsLabel")).toBe("CAID hex (csv, empty = all, ≤ 32)");
    expect(translate("proxys.title")).toBe("SOCKS5 Proxies");

    setLang("fr");
    expect(translate("logs.title")).toBe("Journaux d'événements (Logs)");
    expect(translate("cacheEtat.title")).toBe("État des caches");
    expect(translate("catalog.title")).toBe("Catalogue de cartes");
    expect(translate("cwCycle.title")).toBe("Cycle CW");
    expect(translate("cwCycle.caidsLabel")).toBe("CAID hex (csv, vide = tous, ≤ 32)");
    expect(translate("proxys.title")).toBe("Proxys SOCKS5");
  });

  test("6d. traduit les namespaces profiles et listeners en EN et FR", () => {
    setLang("en");
    expect(translate("profiles.title")).toBe("Profiles");
    expect(translate("profiles.createBtn")).toBe("Create a profile");
    expect(translate("profiles.toastDeleted")).toBe("Profile deleted");
    expect(translate("profiles.bulkEnabled")).toBe("Enabled");
    expect(translate("listeners.title")).toBe("Listeners");
    expect(translate("listeners.newListenerBtn")).toBe("New listener");
    expect(translate("listeners.toastDeleted")).toBe("Listener deleted");
    expect(translate("listeners.extTitle")).toBe("Extended Newcamd");

    setLang("fr");
    expect(translate("profiles.title")).toBe("Profils");
    expect(translate("profiles.createBtn")).toBe("Créer un profil");
    expect(translate("profiles.toastDeleted")).toBe("Profil supprimé");
    expect(translate("profiles.bulkEnabled")).toBe("Activé");
    expect(translate("listeners.title")).toBe("Listeners");
    expect(translate("listeners.newListenerBtn")).toBe("Nouveau listener");
    expect(translate("listeners.toastDeleted")).toBe("Listener supprimé");
    expect(translate("listeners.extTitle")).toBe("Newcamd étendu");
  });

  test("6e. traduit le namespace connectors en EN et FR", () => {
    setLang("en");
    expect(translate("connectors.title")).toBe("Connectors");
    expect(translate("connectors.createBtn")).toBe("Create");
    expect(translate("connectors.toastDeleted")).toBe("Connector deleted");
    expect(translate("connectors.bulkEnabled")).toBe("Enabled");
    expect(translate("connectors.dialogImportTitle")).toBe("Import a CCcam C-line");

    setLang("fr");
    expect(translate("connectors.title")).toBe("Connecteurs");
    expect(translate("connectors.createBtn")).toBe("Créer");
    expect(translate("connectors.toastDeleted")).toBe("Connecteur supprimé");
    expect(translate("connectors.bulkEnabled")).toBe("Activé");
    expect(translate("connectors.dialogImportTitle")).toBe("Importer une C-line CCcam");
  });

  test("6f. traduit le namespace users en EN et FR", () => {
    setLang("en");
    expect(translate("users.title")).toBe("Users");
    expect(translate("users.createUserBtn")).toBe("Create user");
    expect(translate("users.toastDeleted")).toBe("User deleted");
    expect(translate("users.bulkEnabled")).toBe("Enabled");
    expect(translate("users.dialogCreateTitle")).toBe("Create user");

    setLang("fr");
    expect(translate("users.title")).toBe("Utilisateurs");
    expect(translate("users.createUserBtn")).toBe("Créer utilisateur");
    expect(translate("users.toastDeleted")).toBe("Utilisateur supprimé");
    expect(translate("users.bulkEnabled")).toBe("Activé");
    expect(translate("users.dialogCreateTitle")).toBe("Créer utilisateur");
  });

  test("6g. traduit le namespace relais en EN et FR", () => {
    setLang("en");
    expect(translate("relais.title")).toBe("Relays — nodes, caches, links, UDP peers");
    expect(translate("relais.newNodeBtn")).toBe("New Node");
    expect(translate("relais.newCacheBtn")).toBe("New Cache");
    expect(translate("relais.newLinkBtn")).toBe("New link");
    expect(translate("relais.toastLinkSaved")).toBe("Link saved");
    expect(translate("relais.toastPeerAdded")).toBe("Peer added");
    expect(translate("relais.toastListenerAssigned")).toBe("Listener assigned");

    setLang("fr");
    expect(translate("relais.title")).toBe("Relais — nodes, caches, liens, pairs UDP");
    expect(translate("relais.newNodeBtn")).toBe("Nouveau Node");
    expect(translate("relais.newCacheBtn")).toBe("Nouveau Cache");
    expect(translate("relais.newLinkBtn")).toBe("Nouveau lien");
    expect(translate("relais.toastLinkSaved")).toBe("Lien enregistré");
    expect(translate("relais.toastPeerAdded")).toBe("Pair ajouté");
    expect(translate("relais.toastListenerAssigned")).toBe("Listener affecté");
  });

  test("6h. traduit le namespace cacheex en EN et FR", () => {
    setLang("en");
    expect(translate("cacheex.title")).toBe("CacheEx Stats by Peer");
    expect(translate("cacheex.reasonLoop")).toBe("loop");
    expect(translate("cacheex.qualityHeading")).toBe("Peer Quality (time window)");
    expect(translate("cacheex.quarantineHeading")).toBe("Temporary Quarantine (opt-in)");
    expect(translate("cacheex.quorumHeading")).toBe("Targeted CacheEx Quorum");

    setLang("fr");
    expect(translate("cacheex.title")).toBe("Stats CacheEx par pair");
    expect(translate("cacheex.reasonLoop")).toBe("boucle");
    expect(translate("cacheex.qualityHeading")).toBe("Qualité des pairs (fenêtre temporelle)");
    expect(translate("cacheex.quarantineHeading")).toBe("Quarantaine temporaire (opt-in)");
    expect(translate("cacheex.quorumHeading")).toBe("Quorum CacheEx ciblé");
  });

  test("6i. traduit le namespace dcw en EN et FR", () => {
    setLang("en");
    expect(translate("dcw.title")).toBe("DCW Quality");
    expect(translate("dcw.tabPolicy")).toBe("Policy");
    expect(translate("dcw.tabRules")).toBe("Rules");
    expect(translate("dcw.policyToastSaved")).toBe("DCW policy saved");
    expect(translate("dcw.ruleToastCreated")).toBe("DCW rule created");
    expect(translate("dcw.ruleActionOptBlock")).toBe("block (block identified CW)");
    expect(translate("dcw.mappingNewBtn")).toBe("New mapping");
    expect(translate("dcw.obsHeading")).toBe("Observability (observation only)");

    setLang("fr");
    expect(translate("dcw.title")).toBe("Qualité DCW");
    expect(translate("dcw.tabPolicy")).toBe("Politique");
    expect(translate("dcw.tabRules")).toBe("Règles");
    expect(translate("dcw.policyToastSaved")).toBe("Politique DCW enregistrée");
    expect(translate("dcw.ruleToastCreated")).toBe("Règle DCW créée");
    expect(translate("dcw.ruleActionOptBlock")).toBe("block (bloquer le CW identifié)");
    expect(translate("dcw.mappingNewBtn")).toBe("Nouveau mapping");
    expect(translate("dcw.obsHeading")).toBe("Observabilité (observation seule)");
  });
});

describe("cohérence des catalogues de messages", () => {
  type Catalogue = { en: Record<string, string>; fr: Record<string, string> };

  const catalogues = (): Array<[string, Catalogue]> =>
    Object.entries(NAMESPACES) as unknown as Array<[string, Catalogue]>;

  /** Diacritiques français : aucune valeur anglaise ne doit en contenir. */
  const FRENCH_ACCENTS = /[éèêëàâäçùûüôöîïÉÈÊËÀÂÄÇÙÛÜÔÖÎÏ]/;

  test("9. chaque namespace expose exactement les mêmes clés en EN et en FR", () => {
    for (const [ns, dict] of catalogues()) {
      expect({ ns, keys: Object.keys(dict.en).sort() }).toEqual({
        ns,
        keys: Object.keys(dict.fr).sort(),
      });
    }
  });

  test("10. aucune valeur anglaise ne contient d'accent français", () => {
    const offenders: string[] = [];
    for (const [ns, dict] of catalogues()) {
      for (const [key, value] of Object.entries(dict.en)) {
        if (FRENCH_ACCENTS.test(value)) {
          offenders.push(`${ns}.${key} = ${JSON.stringify(value)}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});

describe("useT", () => {
  test("7. fonctionne sans I18nProvider et se re-rend au changement de langue", () => {
    render(<Probe />);
    expect(screen.getByTestId("label").textContent).toBe("Save");

    act(() => {
      setLang("fr");
    });

    expect(screen.getByTestId("label").textContent).toBe("Enregistrer");
  });
});

describe("robustesse du stockage", () => {
  test("8. un localStorage qui lève n'empêche ni la lecture ni l'écriture", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });

    expect(() => readStoredLang()).not.toThrow();
    expect(() => setLang("fr")).not.toThrow();
    expect(getLang()).toBe("fr");
  });
});
