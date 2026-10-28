export function defineMessages<const E extends Record<string, string>>(
  en: E,
  fr: { [K in keyof E]: string },
): { en: E; fr: { [K in keyof E]: string } } {
  return { en, fr };
}
