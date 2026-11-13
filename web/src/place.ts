/**
 * The places of the pages, without the DOM: what the URL of the editor shows (a template, a document of the documents
 * folder, or a blank document) and the URL of each one; and for the index, what its hash shows (a tab and a folder)
 * and the tree of the folders.
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

// ---- The index

/** What the index shows: a tab, and in the templates and the documents tabs, a folder (`""` the root, `null` none yet). */
export interface IndexPlace {
	tab: "templates" | "documents" | "api";
	folder: string | null;
}

const TABS: IndexPlace["tab"][] = ["templates", "documents", "api"];

/** The place of the hash of the index (`#documents/reference/01-values`); the documents tab without a valid one. */
export function parseIndexHash(hash: string): IndexPlace {
	const text = hash.startsWith("#") ? hash.slice(1) : hash;
	const slash = text.indexOf("/");
	const tab = (slash < 0 ? text : text.slice(0, slash)) as IndexPlace["tab"];
	if (!TABS.includes(tab)) {
		return { tab: "documents", folder: null };
	}
	if (slash < 0 || tab === "api") {
		return { tab, folder: null };
	}
	const folder = text.slice(slash + 1);
	return { tab, folder: PATH.test(folder) ? folder : null };
}

/** The hash of a place of the index, as `parseIndexHash` reads it. The root folder is written as no folder. */
export function indexHash(place: IndexPlace): string {
	return place.folder ? `#${place.tab}/${place.folder}` : `#${place.tab}`;
}

/** The hash of the index that lists `place` of the editor: its folder in its tab. */
export function indexHashOf(place: Place): string {
	if (place.path === null) {
		return "#documents";
	}
	const slash = place.path.lastIndexOf("/");
	return indexHash({ tab: place.mode === "template" ? "templates" : "documents", folder: slash < 0 ? null : place.path.slice(0, slash) });
}

/** The folder of a path: `""` for one at the root. */
export function folderOf(path: string): string {
	const slash = path.lastIndexOf("/");
	return slash < 0 ? "" : path.slice(0, slash);
}

/** Whether `path` is in `folder` or below it (`""` is the root: every path). */
export function isUnder(path: string, folder: string): boolean {
	return folder === "" || path.startsWith(`${folder}/`);
}

/**
 * A folder in the tree of the index.
 *
 * - `label`: its last segment, or `/` for the root.
 * - `depth`: its number of segments, minus one; the root is 0.
 * - `count`: the entries in it and below it.
 * - `parent`: whether it has folders below it, which it shows or hides.
 */
export interface FolderRow {
	path: string;
	label: string;
	depth: number;
	count: number;
	parent: boolean;
}

/**
 * The folders of the entries `paths` as a tree, in order: each folder that has entries and every folder above it, each
 * one followed by those below it. The root is there only if it has entries of its own.
 */
export function folderRows(paths: string[]): FolderRow[] {
	const folders = new Set<string>();
	for (const path of paths) {
		const folder = folderOf(path);
		if (folder === "") {
			folders.add("");
			continue;
		}
		const segments = folder.split("/");
		for (let i = 1; i <= segments.length; i++) {
			folders.add(segments.slice(0, i).join("/"));
		}
	}
	const sorted = [...folders].sort(compareFolders);
	return sorted.map((path) => ({
		path,
		label: path === "" ? "/" : path.slice(path.lastIndexOf("/") + 1),
		depth: path === "" ? 0 : path.split("/").length - 1,
		count: paths.filter((entry) => isUnder(entry, path)).length,
		parent: path !== "" && sorted.some((other) => other.startsWith(`${path}/`)),
	}));
}

/** The order of the tree: segment by segment, so that a folder comes right before those below it; the root first. */
function compareFolders(a: string, b: string): number {
	const as = a === "" ? [] : a.split("/");
	const bs = b === "" ? [] : b.split("/");
	for (let i = 0; i < Math.min(as.length, bs.length); i++) {
		if (as[i] !== bs[i]) {
			return as[i] < bs[i] ? -1 : 1;
		}
	}
	return as.length - bs.length;
}

/** The folders above `folder`, from the top: `a/b/c` gives `a` and `a/b`. */
export function ancestors(folder: string): string[] {
	const segments = folder === "" ? [] : folder.split("/");
	return segments.slice(0, -1).map((_, i) => segments.slice(0, i + 1).join("/"));
}

/** The rows shown: those whose folders above are all open. */
export function visibleRows(rows: FolderRow[], open: Set<string>): FolderRow[] {
	return rows.filter((row) => ancestors(row.path).every((above) => open.has(above)));
}

/** The folder shown when the place names none, or one that is not there: the first one with entries of its own. */
export function defaultFolder(rows: FolderRow[], paths: string[]): string | null {
	const own = rows.find((row) => paths.some((path) => folderOf(path) === row.path));
	return own ? own.path : null;
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
