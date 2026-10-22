import { uiStrings } from "./strings";
import { zh } from "./zh";
import { en } from "./en";
import type { Copy, Locale } from "./lang";

/* zh defines the shape; en is checked against it with `satisfies`. */

const copies: Record<Locale, Copy> = {
  zh: zh as Copy,
  en: en as Copy,
};

export function getCopy(locale: Locale): Copy {
  return copies[locale];
}

export function getStrings(locale: Locale) {
  return uiStrings[locale];
}

export type { Copy, Locale };
