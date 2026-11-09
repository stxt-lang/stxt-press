/**
 * The places of the editor, without the DOM: what its URL shows (a template, the same template as a document, or a
 * blank document), the URL of each one, the place of a mode button, and the templates by folder for the index.
 */

/**
 * What the URL of the editor shows: a template of the server in the templates mode (`/editor/{path}`), as a document
 * (`/editor/{path}?mode=document`), or a blank document (`/editor`, `path` `null`).
 */
export interface Place {
	mode: "document" | "template";
	path: string | null;
}

/** The blank document of `/editor`. */
export const BLANK: Place = { mode: "document", path: null };

/** The path of a template: segments of `[A-Za-z0-9_-]`, as the templates folder of the server names them. */
const TEMPLATE_PATH = /^[A-Za-z0-9_-]+(\/[A-Za-z0-9_-]+)*$/;

/**
 * The place of a URL of the editor, or `null` if it is not one: the server serves the page only for these, so `null`
 * is a URL the page was not opened with. Without a path there is no template to show, so `/editor?mode=template` is
 * the blank document too.
 */
export function parsePlace(pathname: string, search: string): Place | null {
	const mode = new URLSearchParams(search).get("mode");
	if (mode !== null && mode !== "document" && mode !== "template") {
		return null;
	}
	if (pathname === "/editor") {
		return BLANK;
	}
	if (!pathname.startsWith("/editor/")) {
		return null;
	}
	const path = pathname.slice("/editor/".length);
	if (!TEMPLATE_PATH.test(path)) {
		return null;
	}
	return { mode: mode === "document" ? "document" : "template", path };
}

/** The URL of a place, as `parsePlace` reads it. */
export function placeUrl(place: Place): string {
	if (place.path === null) {
		return "/editor";
	}
	return `/editor/${place.path}${place.mode === "document" ? "?mode=document" : ""}`;
}

/** Whether two places are the same. */
export function samePlace(a: Place, b: Place): boolean {
	return a.mode === b.mode && a.path === b.path;
}

/**
 * The place of a mode button: the template shown in the other mode or, if the page shows none (the blank document),
 * the first template of the server; `null` without templates.
 */
export function placeOfMode(next: Place["mode"], current: Place, templatePaths: string[]): Place | null {
	if (next === "document") {
		return { mode: "document", path: current.path };
	}
	const path = current.path ?? templatePaths[0];
	return path === undefined ? null : { mode: "template", path };
}

/**
 * The templates grouped by their folder (`""` for those at the root of the templates folder), the folders in the order
 * they first appear in the list, and the templates of each one in the order of the list.
 */
export function byFolder<T extends { path: string }>(templates: T[]): { folder: string; templates: T[] }[] {
	const groups = new Map<string, T[]>();
	for (const template of templates) {
		const slash = template.path.lastIndexOf("/");
		const folder = slash < 0 ? "" : template.path.slice(0, slash);
		const group = groups.get(folder);
		if (group) {
			group.push(template);
		} else {
			groups.set(folder, [template]);
		}
	}
	return [...groups].map(([folder, list]) => ({ folder, templates: list }));
}
