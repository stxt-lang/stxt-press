/**
 * The index of stxt-press, at GET /, in two halves, as the editor. On the left a tab for the documents of the server,
 * one for its templates and one for the routes of the API; the documents and the templates tabs show the tree of their
 * folders and the entries of the folder chosen and those below it: a click on one shows its SVG pages, and each has a
 * link to the editor and a button that shows its PDF. On the right, those pages or that PDF, in the viewer of the
 * browser.
 * What it shows is in its hash (`#documents/reference/01-values`, see `parseIndexHash`), so a reload or the back button
 * shows it again. It does not load the editors: they are in editor.js.
 */
import { askToken, call, errorOf, get, setTokenAsker } from "./api";
import { $, button, element, link, say } from "./dom";
import { maximizable } from "./maximize";
import { createRequest, newEntryPath, pagesLabel, printUrl, took, unreachable } from "./model";
import { ancestors, byFolder, defaultFolder, folderRows, indexHash, isUnder, parseIndexHash, placeUrl, visibleRows } from "./place";
import type { FolderRow, IndexPlace, Place } from "./place";
import { setupThemeToggle } from "./theme";
import { askTokenInDialog } from "./tokenDialog";
import { loadFonts, Viewer } from "./viewer";

/** An entry of `GET /files/templates` or `GET /files/documents`: a document has no `data`. */
interface Entry {
	path: string;
	styles: boolean;
	data?: boolean;
}

type ListTab = "templates" | "documents";

/** What each list tab shows, and its title. */
const LISTS: Record<ListTab, { mode: Place["mode"]; title: string }> = {
	templates: { mode: "template", title: "Templates" },
	documents: { mode: "document", title: "Documents" },
};

/**
 * The entries of each list tab once the server gave them, and whether it creates new ones (`Save` on), or why it did
 * not give them.
 */
const lists: Record<ListTab, { entries?: Entry[]; writable?: boolean; problem?: string; error?: boolean }> = {
	templates: {},
	documents: {},
};
/** The folders open in the tree of each list tab: those whose folders below are shown. */
const open: Record<ListTab, Set<string>> = { templates: new Set(), documents: new Set() };
/** The last folder shown in each list tab, which its tab button goes back to. */
const lastFolder: Record<ListTab, string | null> = { templates: null, documents: null };
/** The entry the right half shows or is asking for, marked in its list. */
let previewed: { tab: ListTab; path: string } | undefined;
/** Counts the previews asked for, so that the answer of one left behind is not shown over the next. */
let previews = 0;
/** The pages or the PDF of the entry shown, in the right half. */
const viewer = new Viewer($<HTMLIFrameElement>("preview-frame"), $("preview-pages"),
	$<HTMLAnchorElement>("preview-download"));

/**
 * Opens what the API gives at `url` in a new tab, as a blob: a link cannot carry the token. The tab is opened before the
 * call, while the click still allows it.
 */
async function openFromApi(url: string, type?: string): Promise<void> {
	const tab = window.open("", "_blank");
	let response: Response;
	try {
		response = await get(url);
	} catch (e) {
		tab?.close();
		say(unreachable(e), true);
		return;
	}
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
 * Shows an entry in the right half: its PDF in the viewer of the browser, with a link to download it with its name
 * (the viewer would save it with the random name of the blob), or its SVG pages, which come faster since no PDF is
 * written, with the fonts of the server (pages.ts); and a link to edit it. While it is converted, what was shown
 * stays; an answer that comes after another one was asked for is not shown.
 */
async function preview(tab: ListTab, path: string, format: "pdf" | "svg"): Promise<void> {
	const seq = ++previews;
	previewed = { tab, path };
	markPreviewed();
	const title = $("preview-title");
	title.textContent = `${path} · converting…`;
	title.classList.remove("error");
	try {
		const started = performance.now();
		const response = await get(printUrl(LISTS[tab].mode, path, format));
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
		if (format === "svg") {
			const pages = ((await response.json()) as { pages: string[] }).pages;
			// The fonts once, the first time: an SVG names its fonts and does not carry them.
			await loadFonts();
			if (seq !== previews) {
				return;
			}
			viewer.showPages(pages);
			title.textContent = `${path} · ${took(pagesLabel(pages.length), performance.now() - started)}`;
		} else {
			const pdf = await response.blob();
			const ms = performance.now() - started;
			if (seq !== previews) {
				return;
			}
			viewer.showPdf(pdf, `${nameOf(path)}.pdf`);
			title.textContent = `${path} · ${took("PDF", ms)}`;
		}
	} catch (e) {
		// No answer: the server stopped, or the network is down.
		if (seq === previews) {
			title.textContent = `${path} · ${unreachable(e)}`;
			title.classList.add("error");
		}
		return;
	}
	const edit = $<HTMLAnchorElement>("preview-edit");
	edit.href = placeUrl({ mode: LISTS[tab].mode, path });
	edit.hidden = false;
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

/**
 * What adds an entry to the folder shown, to the right of its title: with `Save` on, a button that asks for the name of
 * a new template or document and creates it there; without it, in the documents tab, the link to a blank document of
 * the editor, which is not kept.
 */
function showNew(tab: ListTab | null, folder: string): void {
	const writable = tab !== null && lists[tab].writable === true;
	const add = $<HTMLButtonElement>("new-entry");
	add.hidden = !writable;
	if (tab !== null && writable) {
		add.textContent = `New ${LISTS[tab].mode}`;
		add.title = `A new ${LISTS[tab].mode} in ${folder === "" ? "the root folder" : `${folder}/`}`;
		add.onclick = () => void create(tab, folder);
	}
	$("blank-document").hidden = writable || tab !== "documents";
}

/**
 * Asks for the name of a new template or document in `folder`, until it is a valid one that the folder does not have,
 * and creates it in the server (`PUT` with `If-None-Match: *`); then opens it in the editor.
 */
async function create(tab: ListTab, folder: string): Promise<void> {
	const noun = LISTS[tab].mode;
	const dialog = $<HTMLDialogElement>("new-dialog");
	const input = $<HTMLInputElement>("new-name");
	const problem = $("new-problem");
	$("new-question").textContent = `The name of the new ${noun} in ${folder === "" ? "the root folder" : `${folder}/`}:`;
	input.value = "";
	problem.textContent = "";
	const ok = $<HTMLButtonElement>("new-ok");
	ok.disabled = false;
	dialog.showModal();
	// However it closes (Create, Cancel or Esc), the dialog is left without the handlers of this folder.
	dialog.addEventListener("close", () => {
		ok.onclick = null;
		$("new-cancel").onclick = null;
		input.onkeydown = null;
		input.oninput = null;
	}, { once: true });
	const submit = async () => {
		const asked = newEntryPath(input.value, folder, (lists[tab].entries ?? []).map((entry) => entry.path), noun);
		if ("problem" in asked) {
			problem.textContent = asked.problem;
			input.focus();
			return;
		}
		ok.disabled = true;
		const request = createRequest(noun, asked.path);
		let response: Response;
		try {
			response = await call({ method: "PUT", url: request.url, body: request.body, contentType: "application/json",
				headers: { "If-None-Match": "*" } });
		} catch (e) {
			problem.textContent = unreachable(e);
			return;
		} finally {
			ok.disabled = false;
		}
		if (!response.ok) {
			const error = await errorOf(response);
			problem.textContent = error.code === "TEMPLATE_EXISTS" || error.code === "DOCUMENT_EXISTS"
				? `There is already a ${noun} called ${nameOf(asked.path)} in this folder.`
				: `${error.code}: ${error.message}`;
			return;
		}
		dialog.close();
		location.href = placeUrl({ mode: noun, path: asked.path });
	};
	ok.onclick = () => void submit();
	$("new-cancel").onclick = () => dialog.close();
	input.oninput = () => {
		problem.textContent = "";
	};
	input.onkeydown = (event) => {
		if (event.key === "Enter") {
			void submit();
		}
	};
}

/** The entries of `folder` and of those below it, by folder, each with its link to the editor and its PDF. */
function showEntries(tab: ListTab, entries: Entry[], folder: string): void {
	$("folder-title").textContent = folder === "" ? "/" : `${folder}/`;
	showNew(tab, folder);
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
			// The entry itself shows its SVG pages, the fast preview; its name is the button, for the keyboard, and a
			// click anywhere on the row but its actions does the same.
			const show = () => void preview(tab, entry.path, "svg");
			item.onclick = (event) => {
				if (!(event.target as Element).closest("a, button")) {
					show();
				}
			};
			const name = item.appendChild(button(nameOf(entry.path), "entry", show));
			name.title = `Show the pages of ${entry.path}`;
			item.appendChild(element("span", marks(entry), "muted"));
			const actions = item.appendChild(element("span", undefined, "links"));
			const edit = actions.appendChild(link("Edit", placeUrl({ mode: LISTS[tab].mode, path: entry.path })));
			edit.className = "button quiet small";
			actions.appendChild(button("PDF", "quiet small", () => void preview(tab, entry.path, "pdf")));
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
	showNew(null, "");
	if (place.tab === "api") {
		return;
	}
	const tab = place.tab;
	const list = lists[tab];
	$("folders").replaceChildren();
	$("entries").replaceChildren();
	$("folder-title").textContent = LISTS[tab].title;
	if (!list.entries) {
		say(list.problem ?? "Loading…", list.error === true);
		if (list.problem !== undefined) {
			showNew(tab, "");
		}
		return;
	}
	if (list.entries.length === 0) {
		say(`The server has no ${tab}.`);
		// The first one goes in the root folder.
		showNew(tab, "");
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

/** Marks a route of the table as off in this server, once. */
function off(id: string): void {
	const row = $<HTMLTableRowElement>(id);
	if (row.classList.contains("off")) {
		return;
	}
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
	let listed: Response;
	try {
		listed = await get(`/files/${tab}`);
	} catch (e) {
		// A reload asks again.
		lists[tab] = { problem: unreachable(e), error: true };
		return;
	}
	if (listed.ok) {
		const body = (await listed.json()) as Record<string, Entry[]> & { writable?: boolean };
		lists[tab] = { entries: body[tab], writable: body.writable === true };
	} else if (listed.status === 404) {
		off("route-files");
		off("route-files-texts");
		lists[tab] = { problem: `The ${tab} are not listed in this server (Files: false).` };
	} else {
		const error = await errorOf(listed);
		lists[tab] = { problem: `${error.code}: ${error.message}`, error: true };
	}
}

async function start(): Promise<void> {
	setupThemeToggle($("theme-toggle"));
	setTokenAsker(askTokenInDialog);
	$("token").onclick = () => void askToken().then(() => location.reload());
	$("schema").onclick = () => void openFromApi("/schema", "text/plain; charset=utf-8");
	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((b) => {
		const tab = b.dataset.tab as IndexPlace["tab"];
		b.onclick = () => go({ tab, folder: tab === "api" ? null : lastFolder[tab] });
	});
	window.addEventListener("hashchange", show);
	maximizable(document.querySelector<HTMLElement>(".preview")!);
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
