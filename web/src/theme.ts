/**
 * The theme of the pages, as in the playground: light or dark, the one chosen with the button of the bar or, while
 * none is chosen, the one of the system, which the page then follows when it changes. The theme is the `data-theme`
 * of `<html>`: `themeHead.ts` (theme.js, loaded in the head) sets it before the first paint, and `setupThemeToggle`
 * after that. Only the choice is stored, in the `localStorage` of the server's origin; choosing the theme of the system
 * stores nothing, so that the page follows the system again.
 */

export type Theme = "light" | "dark";

/** Where the choice is stored. */
const KEY = "stxt-press.theme";

const systemTheme = (systemDark: boolean): Theme => (systemDark ? "dark" : "light");

/** The theme to show: the one chosen, if any, or the one of the system. */
export function themeOf(chosen: Theme | undefined, systemDark: boolean): Theme {
	return chosen ?? systemTheme(systemDark);
}

/** What a press of the button leaves chosen: the other theme, or none when that is the one of the system. */
export function toggledTheme(chosen: Theme | undefined, systemDark: boolean): Theme | undefined {
	const next: Theme = themeOf(chosen, systemDark) === "dark" ? "light" : "dark";
	return next === systemTheme(systemDark) ? undefined : next;
}

/** The theme stored, if a valid one is; without storage (blocked, a private window), none. */
export function storedTheme(): Theme | undefined {
	try {
		const value = localStorage.getItem(KEY);
		return value === "dark" || value === "light" ? value : undefined;
	} catch {
		return undefined;
	}
}

function store(theme: Theme | undefined): void {
	try {
		if (theme === undefined) {
			localStorage.removeItem(KEY);
		} else {
			localStorage.setItem(KEY, theme);
		}
	} catch {
		// Without storage the choice lasts until the page is left.
	}
}

/** The dark colour scheme of the system. */
export function systemDark(): MediaQueryList {
	return matchMedia("(prefers-color-scheme: dark)");
}

/** Wires the theme button of the bar and paints the theme. */
export function setupThemeToggle(button: HTMLElement): void {
	const system = systemDark();
	let chosen = storedTheme();
	const render = (): void => {
		const theme = themeOf(chosen, system.matches);
		document.documentElement.dataset.theme = theme;
		button.setAttribute("aria-pressed", String(theme === "dark"));
		button.title = theme === "dark" ? "Switch to the light theme" : "Switch to the dark theme";
	};
	button.addEventListener("click", () => {
		chosen = toggledTheme(chosen, system.matches);
		store(chosen);
		render();
	});
	system.addEventListener("change", render);
	render();
}
