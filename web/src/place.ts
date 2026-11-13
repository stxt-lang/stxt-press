/**
 * The places of the editor, without the DOM: what its URL shows (a template, a document of the documents folder, or a
 * blank document), the URL of each one, the place of a mode button, and the entries by folder for the index.
 */

/**
 * What the URL of the editor shows: a template of the server in the templates mode (`/editor/templates/{path}`), a
 * document of its documents folder in the document mode (`/editor/documents/{path}`), or a blank document (`/editor`,
 * `path` `null`).
 */
export interface Place {
	mode: "document" | "template";
	path: string | null;
}

/** The blank document of `/editor`. */
export const BLANK: Place = { mode: "document", path: null };

/** The path of a template or a document: segments of `[A-Za-z0-9_-]`, as the folders of the server name them. */
const PATH = /^[A-Za-z0-9_-]+(\/[A-Za-z0-9_-]+)*$/;

/** The beginning of the URL of each mode with a path. */
const PREFIX = { template: "/editor/templates/", document: "/editor/documents/" } as const;

/**
 * The place of a URL of the editor, or `null` if it is not one: the server serves the page only for these, so `null`
 * is a URL the page was not opened with.
 */
export function parsePlace(pathname: string): Place | null {
	if (pathname === "/editor") {
		return BLANK;
	}
	for (const mode of ["template", "document"] as const) {
		if (pathname.startsWith(PREFIX[mode])) {
			const path = pathname.slice(PREFIX[mode].length);
			return PATH.test(path) ? { mode, path } : null;
		}
	}
	return null;
}

/** The URL of a place, as `parsePlace` reads it. */
export function placeUrl(place: Place): string {
	return place.path === null ? "/editor" : `${PREFIX[place.mode]}${place.path}`;
}

/** Whether two places are the same. */
export function samePlace(a: Place, b: Place): boolean {
	return a.mode === b.mode && a.path === b.path;
}

/**
 * The place of a mode button: the first entry of the list of that mode, the documents of the server or its templates.
 * Without documents, the blank document; without templates, `null`.
 */
export function placeOfMode(next: Place["mode"], documentPaths: string[], templatePaths: string[]): Place | null {
	if (next === "document") {
		return { mode: "document", path: documentPaths[0] ?? null };
	}
	const path = templatePaths[0];
	return path === undefined ? null : { mode: "template", path };
}

/**
 * The entries grouped by their folder (`""` for those at the root of the folder), the folders in the order they first
 * appear in the list, and the entries of each one in the order of the list.
 */
export function byFolder<T extends { path: string }>(entries: T[]): { folder: string; entries: T[] }[] {
	const groups = new Map<string, T[]>();
	for (const entry of entries) {
		const slash = entry.path.lastIndexOf("/");
		const folder = slash < 0 ? "" : entry.path.slice(0, slash);
		const group = groups.get(folder);
		if (group) {
			group.push(entry);
		} else {
			groups.set(folder, [entry]);
		}
	}
	return [...groups].map(([folder, list]) => ({ folder, entries: list }));
}
