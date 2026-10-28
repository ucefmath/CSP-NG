import { fireEvent, render } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { Dialog } from "../components/ui";

// setup.ts mocke showModal/close sur le prototype : on récupère les espions
// pour compter les appels réels pendant chaque scénario.
const showModalSpy = HTMLDialogElement.prototype.showModal as unknown as ReturnType<typeof vi.fn>;
const closeSpy = HTMLDialogElement.prototype.close as unknown as ReturnType<typeof vi.fn>;

const RECT = {
  left: 100,
  top: 100,
  right: 500,
  bottom: 400,
  width: 400,
  height: 300,
  x: 100,
  y: 100,
  toJSON: () => ({}),
} as DOMRect;

function stubRect(el: Element, rect: DOMRect = RECT) {
  el.getBoundingClientRect = () => rect;
}

function dialogEl(container: HTMLElement): HTMLDialogElement {
  const dlg = container.querySelector("dialog");
  if (!dlg) throw new Error("<dialog> introuvable");
  return dlg as HTMLDialogElement;
}

beforeEach(() => {
  showModalSpy.mockClear();
  closeSpy.mockClear();
});

describe("Dialog", () => {
  test("1. un re-render du parent avec une nouvelle onClose inline ne ferme/rouvre pas le dialogue", () => {
    const { container, rerender } = render(
      <Dialog title="Test" onClose={() => {}}>
        <p>contenu</p>
      </Dialog>,
    );
    const dlg = dialogEl(container);
    stubRect(dlg);
    expect(showModalSpy).toHaveBeenCalledTimes(1);

    showModalSpy.mockClear();
    closeSpy.mockClear();

    rerender(
      <Dialog title="Test" onClose={() => {}}>
        <p>contenu</p>
      </Dialog>,
    );

    expect(closeSpy).not.toHaveBeenCalled();
    expect(showModalSpy).not.toHaveBeenCalled();
  });

  test("2. un clic sur un <select> enfant ne ferme pas le dialogue", () => {
    const onClose = vi.fn();
    const { container } = render(
      <Dialog title="Test" onClose={onClose}>
        <select data-testid="select" defaultValue="a">
          <option value="a">A</option>
        </select>
      </Dialog>,
    );
    const dlg = dialogEl(container);
    stubRect(dlg);
    const selectEl = container.querySelector("select");
    if (!selectEl) throw new Error("<select> introuvable");

    fireEvent.click(selectEl, { clientX: -10, clientY: -10, detail: 1 });

    expect(onClose).not.toHaveBeenCalled();
  });

  test("3. un clic synthétique clavier (detail 0) sur le <dialog> ne ferme pas le dialogue", () => {
    const onClose = vi.fn();
    const { container } = render(
      <Dialog title="Test" onClose={onClose}>
        <p>contenu</p>
      </Dialog>,
    );
    const dlg = dialogEl(container);
    stubRect(dlg);

    fireEvent.click(dlg, { clientX: 0, clientY: 0, detail: 0 });

    expect(onClose).not.toHaveBeenCalled();
  });

  test("4. un vrai clic pointeur sur le backdrop ferme le dialogue une fois", () => {
    const onClose = vi.fn();
    const { container } = render(
      <Dialog title="Test" onClose={onClose}>
        <p>contenu</p>
      </Dialog>,
    );
    const dlg = dialogEl(container);
    stubRect(dlg);

    fireEvent.click(dlg, { clientX: 10, clientY: 10, detail: 1 });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test("5. l'événement cancel ferme le dialogue une fois", () => {
    const onClose = vi.fn();
    const { container } = render(
      <Dialog title="Test" onClose={onClose}>
        <p>contenu</p>
      </Dialog>,
    );
    const dlg = dialogEl(container);

    fireEvent(dlg, new Event("cancel", { cancelable: true }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
