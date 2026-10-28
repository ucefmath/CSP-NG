import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";
import { setLang } from "../i18n";

// Les tests unitaires existants sont écrits en français : on force la langue
// avant chaque test (les `beforeEach` des fichiers de test s'exécutent ensuite
// et peuvent donc la redéfinir, ex. i18n.test.tsx remet DEFAULT_LANG).
beforeEach(() => {
  setLang("fr");
});

afterEach(() => {
  cleanup();
});

// jsdom : <dialog> minimal (showModal/close/open) pour les tests composants.
if (typeof HTMLDialogElement !== "undefined") {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    (this as HTMLDialogElement & { open: boolean }).open = true;
    this.setAttribute("open", "");
  });
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    (this as HTMLDialogElement & { open: boolean }).open = false;
    this.removeAttribute("open");
  });
}
if (typeof Element !== "undefined" && !Element.prototype.getBoundingClientRect) {
  Element.prototype.getBoundingClientRect = () => ({
    x: 0,
    y: 0,
    width: 0,
    height: 0,
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    toJSON: () => {},
  });
}
