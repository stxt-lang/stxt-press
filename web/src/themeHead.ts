/**
 * theme.js: the theme of the page before the first paint, loaded in the head of both pages before the stylesheet. It is
 * a file and not a script in the page because the Content-Security-Policy of the pages forbids inline scripts.
 */
import { storedTheme, systemDark, themeOf } from "./theme";

document.documentElement.dataset.theme = themeOf(storedTheme(), systemDark().matches);
