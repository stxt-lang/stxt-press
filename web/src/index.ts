/**
 * The index of stxt-press, at GET /, in two halves, as the editor. On the left a tab for the documents of the server,
 * one for its templates and one for the routes of the API; the documents and the templates tabs show the tree of their
 * folders and the entries of the folder chosen and those below it, each with a link to the editor and a button that
 * shows its PDF. On the right, that PDF, in the viewer of the browser.
 * What it shows is in its hash (`#documents/reference/01-values`, see `parseIndexHash`), so a reload or the back button
 * shows it again. It does not load the editors: they are in editor.js.
 */
import { $, askToken, call, errorOf, say } from "./api";
import { ancestors, byFolder, defaultFolder, folderRows, indexHash, isUnder, parseIndexHash, placeUrl, visibleRows } from "./place";
import type { FolderRow, IndexPlace, Place } from "./place";

/** An entry of `GET /templates` or `GET /documents`: a document has no `data`. */
interface Entry {
	path: string;
	styles: boolean;
	data?: boolean;
}

type ListTab = "templates" | "documents";

/** What each list tab shows, and what it needs of the server. */
const LISTS: Record<ListTab, { mode: Place["mode"]; node: string; route: string }> = {
	templates: { mode: "template", node: "Templates", route: "route-templates" },
	documents: { mode: "document", node: "Documents", route: "route-documents" },
};

/** The entries of each list tab once the server gave them, or why it did not. */
const lists: Record<ListTab, { entries?: Entry[]; problem?: string; error?: boolean }> = { templates: {}, documents: {} };
/** The folders open in the tree of each list tab: those whose folders below are shown. */
const open: Record<ListTab, Set<string>> = { templates: new Set(), documents: new Set() };
/** The last folder shown in each list tab, which its tab button goes back to. */
const lastFolder: Record<ListTab, string | null> = { templates: null, documents: null };
/** The entry whose PDF the right half shows or is asking for, marked in its list. */
let previewed: { tab: ListTab; path: string } | undefined;
/** Counts the PDFs asked for, so that the answer of one left behind is not shown over the next. */
let previews = 0;
/** The `blob:` URL of the PDF shown, freed when another one takes its place. */
let previewUrl: string | undefined;

function element<K extends keyof HTMLElementTagNameMap>(tag: K, text?: string, className?: string): HTMLElementTagNameMap[K] {
	const node = document.createElement(tag);
	if (text !== undefined) {
		node.textContent = text;
	}
	if (className) {
		node.className = className;
	}
	return node;
}

function link(text: string, href: string): HTMLAnchorElement {
	const a = element("a", text);
	a.href = href;
	return a;
}

function button(text: string, className: string, onclick: () => void): HTMLButtonElement {
	const b = element("button", text, className);
	b.type = "button";
	b.onclick = onclick;
	return b;
}

/**
 * Opens what the API gives at `url` in a new tab, as a blob: a link cannot carry the token. The tab is opened before the
 * call, while the click still allows it.
 */
async function openFromApi(url: string, type?: string): Promise<void> {
	const tab = window.open("", "_blank");
	const response = await call("GET", url);
	if (!response.ok) {
		tab?.close();
		const error = await errorOf(response);
		say(`${error.code}: ${error.message}`, true);
		return;
	}
	const blob = type ? new Blob([await response.text()], { type }) : await response.blob();
	const href = URL.createObjectURL(blob);
	if (tab) {
		tab.location.href = href;
	} else {
		location.href = href;
	}
	setTimeout(() => URL.revokeObjectURL(href), 60_000);
}

/** The name of an entry, the last segment of its path, which names its PDF. */
function nameOf(path: string): string {
	return path.slice(path.lastIndexOf("/") + 1);
}

/**
 * Shows the PDF of an entry in the right half, with links to edit it and to download it with its name (the viewer would
 * save it with the random name of the blob). While it is converted, the PDF before stays; an answer that comes after
 * another PDF was asked for is not shown.
 */
async function preview(tab: ListTab, path: string): Promise<void> {
	const seq = ++previews;
	previewed = { tab, path };
	markPreviewed();
	const title = $("preview-title");
	title.textContent = `${path} · converting…`;
	title.classList.remove("error");
	const response = await call("GET", `/print/${tab}/${path}`);
	if (seq !== previews) {
		return;
	}
	if (!response.ok) {
		const error = await errorOf(response);
		if (seq === previews) {
			title.textContent = `${path} · ${error.code}${error.line ? ` (line ${error.line} of ${error.source})` : ""}: ${error.message}`;
			title.classList.add("error");
		}
		return;
	}
	const pdf = await response.blob();
	if (seq !== previews) {
		return;
	}
	if (previewUrl) {
		URL.revokeObjectURL(previewUrl);
	}
	previewUrl = URL.createObjectURL(pdf);
	title.textContent = path;
	const frame = $<HTMLIFrameElement>("preview-frame");
	frame.src = previewUrl;
	frame.hidden = false;
	const edit = $<HTMLAnchorElement>("preview-edit");
	edit.href = placeUrl({ mode: LISTS[tab].mode, path });
	edit.hidden = false;
	const download = $<HTMLAnchorElement>("preview-download");
	download.href = previewUrl;
	download.download = `${nameOf(path)}.pdf`;
	download.hidden = false;
}

/** Marks in the list the entry whose PDF the right half shows. */
function markPreviewed(): void {
	document.querySelectorAll<HTMLLIElement>("#entries li").forEach((item) => {
		const on = previewed !== undefined && item.dataset.tab === previewed.tab && item.dataset.path === previewed.path;
		item.classList.toggle("current", on);
	});
}

function marks(entry: Entry): string {
	const parts = [];
	if (entry.data) {
		parts.push("sample data");
	}
	if (entry.styles) {
		parts.push("own styles");
	}
	return parts.join(", ");
}

/** Goes to `place`: a new entry in the history of the browser, which `hashchange` shows. */
function go(place: IndexPlace): void {
	const hash = indexHash(place);
	if (location.hash === hash) {
		show();
	} else {
		location.hash = hash;
	}
}

/** The tree of the folders of a list tab, with `folder` chosen. */
function showFolders(tab: ListTab, rows: FolderRow[], folder: string): void {
	const host = $("folders");
	host.replaceChildren();
	for (const row of visibleRows(rows, open[tab])) {
		const line = host.appendChild(element("div", undefined, "folder-row"));
		line.style.paddingLeft = `${row.depth * 1.1}rem`;
		if (row.parent) {
			const expanded = open[tab].has(row.path);
			const caret = line.appendChild(button(expanded ? "▾" : "▸", "caret", () => {
				if (expanded) {
					open[tab].delete(row.path);
				} else {
					open[tab].add(row.path);
				}
				showFolders(tab, rows, folder);
			}));
			caret.setAttribute("aria-expanded", String(expanded));
			caret.setAttribute("aria-label", `${expanded ? "Close" : "Open"} ${row.path}`);
		} else {
			line.appendChild(element("span", undefined, "caret"));
		}
		const name = line.appendChild(button("", "name", () => {
			// Only the branch of the folder chosen stays open, so that the tree fits; the carets open others.
			open[tab] = new Set([...ancestors(row.path), ...(row.parent ? [row.path] : [])]);
			go({ tab, folder: row.path });
		}));
		name.appendChild(element("span", row.label));
		name.appendChild(element("span", String(row.count), "count"));
		if (row.path === folder) {
			name.setAttribute("aria-current", "true");
		}
	}
}

/** The entries of `folder` and of those below it, by folder, each with its link to the editor and its PDF. */
function showEntries(tab: ListTab, entries: Entry[], folder: string): void {
	$("folder-title").textContent = folder === "" ? "/" : `${folder}/`;
	const host = $("entries");
	host.replaceChildren();
	const groups = byFolder(entries.filter((entry) => isUnder(entry.path, folder)));
	for (const group of groups) {
		if (groups.length > 1 || group.folder !== folder) {
			host.appendChild(element("h2", `${group.folder.slice(folder === "" ? 0 : folder.length + 1)}/`));
		}
		const list = host.appendChild(element("ul"));
		for (const entry of group.entries) {
			const item = list.appendChild(element("li"));
			item.dataset.tab = tab;
			item.dataset.path = entry.path;
			item.appendChild(link(nameOf(entry.path), placeUrl({ mode: LISTS[tab].mode, path: entry.path })));
			item.appendChild(element("span", marks(entry), "muted"));
			const actions = item.appendChild(element("span", undefined, "links"));
			actions.appendChild(button("PDF", "quiet small", () => void preview(tab, entry.path)));
		}
	}
	markPreviewed();
}

/** Shows what the hash says: the tab, and in a list tab its folders and the entries of the folder chosen. */
function show(): void {
	const place = parseIndexHash(location.hash);
	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((b) => {
		b.setAttribute("aria-pressed", String(b.dataset.tab === place.tab));
	});
	$("api").hidden = place.tab !== "api";
	$("browser").hidden = place.tab === "api";
	say("");
	if (place.tab === "api") {
		return;
	}
	const tab = place.tab;
	const list = lists[tab];
	$("folders").replaceChildren();
	$("entries").replaceChildren();
	$("folder-title").textContent = LISTS[tab].node;
	if (!list.entries) {
		say(list.problem ?? "Loading…", list.error === true);
		return;
	}
	if (list.entries.length === 0) {
		say(`The server has no ${tab}.`);
		return;
	}
	const paths = list.entries.map((entry) => entry.path);
	const rows = folderRows(paths);
	// A folder that is not there, or none: the first one with entries of its own, written in the hash as it is.
	const folder = place.folder !== null && rows.some((row) => row.path === place.folder) ? place.folder
		: defaultFolder(rows, paths) ?? "";
	if (location.hash !== indexHash({ tab, folder })) {
		history.replaceState(null, "", indexHash({ tab, folder }));
	}
	lastFolder[tab] = folder;
	for (const above of ancestors(folder)) {
		open[tab].add(above);
	}
	showFolders(tab, rows, folder);
	showEntries(tab, list.entries, folder);
}

/** Marks a route of the table as off in this server. */
function off(id: string): void {
	const row = $<HTMLTableRowElement>(id);
	row.classList.add("off");
	row.cells[1].appendChild(element("span", "off in this server", "badge"));
}

async function versions(): Promise<void> {
	try {
		const response = await fetch("/health");
		const body = (await response.json()) as { versions: Record<string, string | null> };
		$("versions").textContent = Object.entries(body.versions).map(([name, version]) => `${name} ${version ?? "dev"}`).join(" · ");
	} catch {
		// Without versions the index still works.
	}
}

/** Asks the server for the entries of a list tab, or says why they are not listed. */
async function load(tab: ListTab): Promise<void> {
	const listed = await call("GET", `/${tab}`);
	if (listed.ok) {
		lists[tab] = { entries: ((await listed.json()) as Record<string, Entry[]>)[tab] };
	} else if (listed.status === 404) {
		off(LISTS[tab].route);
		lists[tab] = { problem: `The ${tab} are not listed in this server (${LISTS[tab].node}: false).` };
	} else {
		const error = await errorOf(listed);
		lists[tab] = { problem: `${error.code}: ${error.message}`, error: true };
	}
}

async function start(): Promise<void> {
	$("token").onclick = () => void askToken().then(() => location.reload());
	$("schema").onclick = () => void openFromApi("/schema", "text/plain; charset=utf-8");
	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((b) => {
		const tab = b.dataset.tab as IndexPlace["tab"];
		b.onclick = () => go({ tab, folder: tab === "api" ? null : lastFolder[tab] });
	});
	window.addEventListener("hashchange", show);
	void versions();
	show();
	// One after the other: with a token, the first 401 asks for it, and the second call uses it. The documents first,
	// the tab the index opens with.
	await load("documents");
	show();
	await load("templates");
	show();
}

void start();
