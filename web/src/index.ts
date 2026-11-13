/**
 * The index of stxt-press, at GET /: the templates and the documents of the server, grouped by folder, each with links
 * to the editor and its PDF, and the routes of the API. It does not load the editors: they are in editor.js.
 */
import { $, askToken, call, errorOf, say } from "./api";
import { byFolder, placeUrl } from "./place";
import type { Place } from "./place";

/** An entry of `GET /templates` or `GET /documents`: a document has no `data`. */
interface Entry {
	path: string;
	styles: boolean;
	data?: boolean;
}

/** What the index shows of each folder of the server. */
const FOLDERS = {
	template: { list: "/templates", key: "templates", host: "templates", message: "templates-message", route: "route-templates", node: "Templates" },
	document: { list: "/documents", key: "documents", host: "documents", message: "documents-message", route: "route-documents", node: "Documents" },
} as const;

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

/** Shows the entries of a folder, by folder, each with its link to the editor and its PDF. */
function showEntries(mode: Place["mode"], entries: Entry[]): void {
	const host = $(FOLDERS[mode].host);
	host.replaceChildren();
	for (const group of byFolder(entries)) {
		const section = host.appendChild(element("div", undefined, "folder"));
		section.appendChild(element("h2", group.folder === "" ? "/" : `${group.folder}/`));
		const list = section.appendChild(element("ul"));
		for (const entry of group.entries) {
			const item = list.appendChild(element("li"));
			item.appendChild(link(entry.path.slice(entry.path.lastIndexOf("/") + 1), placeUrl({ mode, path: entry.path })));
			item.appendChild(element("span", marks(entry), "muted"));
			const actions = item.appendChild(element("span", undefined, "links"));
			const pdf = actions.appendChild(element("button", "PDF", "quiet small"));
			pdf.type = "button";
			pdf.onclick = () => void openFromApi(`/print/${FOLDERS[mode].key}/${entry.path}`);
		}
	}
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

/** Lists the entries of a folder of the server, or says why they are not listed. */
async function list(mode: Place["mode"]): Promise<void> {
	const folder = FOLDERS[mode];
	const message = $(folder.message);
	const listed = await call("GET", folder.list);
	if (listed.ok) {
		const entries = ((await listed.json()) as Record<string, Entry[]>)[folder.key];
		showEntries(mode, entries);
		message.textContent = entries.length === 0 ? `The server has no ${folder.key}.` : "";
	} else if (listed.status === 404) {
		off(folder.route);
		message.textContent = `The ${folder.key} are not listed in this server (${folder.node}: false).`;
	} else {
		const error = await errorOf(listed);
		message.textContent = `${error.code}: ${error.message}`;
		message.classList.add("error");
	}
}

async function start(): Promise<void> {
	$("token").onclick = () => void askToken().then(() => location.reload());
	$("schema").onclick = () => void openFromApi("/schema", "text/plain; charset=utf-8");
	void versions();
	// One after the other: with a token, the first 401 asks for it, and the second call uses it.
	await list("template");
	await list("document");
}

void start();
