import { useEffect, type ReactNode, useSyncExternalStore } from 'react';
import {
  DEFAULT_LANG,
  getLang,
  setLang,
  subscribe,
  translate,
  type Lang,
} from './core';

export function I18nProvider({ children }: { children: ReactNode }) {
  const lang = useSyncExternalStore(subscribe, getLang, () => DEFAULT_LANG);

  useEffect(() => {
    if (typeof document !== 'undefined' && document.documentElement) {
      document.documentElement.lang = lang;
    }
  }, [lang]);

  return <>{children}</>;
}

export function useI18n(): {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: typeof translate;
} {
  const lang = useSyncExternalStore(subscribe, getLang, () => DEFAULT_LANG);
  return { lang, setLang, t: translate };
}

export function useT(): typeof translate {
  useSyncExternalStore(subscribe, getLang, () => DEFAULT_LANG);
  return translate;
}
