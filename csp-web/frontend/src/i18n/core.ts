import { NAMESPACES, type MessageKey } from './messages';

export type Lang = 'en' | 'fr';
export const DEFAULT_LANG: Lang = 'en';
export const STORAGE_KEY = 'csp.lang';

export function readStoredLang(): Lang {
  try {
    if (typeof localStorage !== 'undefined') {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'fr' || stored === 'en') {
        return stored;
      }
    }
  } catch {
    // Ignore storage errors (e.g. quota, security)
  }
  return DEFAULT_LANG;
}

let currentLang: Lang = readStoredLang();
const listeners = new Set<() => void>();

export function getLang(): Lang {
  return currentLang;
}

export function setLang(l: Lang): void {
  currentLang = l;
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(STORAGE_KEY, l);
    }
  } catch {
    // Ignore storage errors
  }
  if (typeof document !== 'undefined' && document.documentElement) {
    document.documentElement.lang = l;
  }
  for (const listener of listeners) {
    listener();
  }
}

export function subscribe(cb: () => void): () => void {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
}

export function translate(key: MessageKey, vars?: Record<string, string | number>): string {
  const dotIndex = key.indexOf('.');
  const ns = dotIndex === -1 ? key : key.slice(0, dotIndex);
  const k = dotIndex === -1 ? '' : key.slice(dotIndex + 1);

  const namespace = (NAMESPACES as Record<string, Record<string, Record<string, string>>>)[ns];
  let text = namespace?.[currentLang]?.[k] ?? namespace?.en?.[k] ?? key;

  if (vars) {
    text = text.replace(/\{(\w+)\}/g, (match, varName) => {
      return vars[varName] !== undefined ? String(vars[varName]) : match;
    });
  }

  return text;
}
