// With its extension: the tests run this file with the type stripping of Node, which does not add it.
import { FOLDER, nameOf } from "./place.ts";

/**
 * What the editor decides without the DOM: which request a button makes, where an error of the server goes and what
 * it says, the JSON of the data, the panels of a place and their tabs, and the curl of a template; the places and their
 * URLs are in place.ts. The page (main.ts, panels.ts, output.ts) only reads the panels and paints the answers.
 */

/** The formats of `?format=`. */
export type Format = "pdf" | "svg" | "stxt";

/** A styles document of a panel: its name in the errors and its text. */
export interface StylesText {
	path: string;
	text: string;
}

/**
 * The panels of the document mode: a document and its styles documents in order (those of the chain of a document of
 * the server, or one blank panel). It has no data: a document is converted as it is. `path` and `original` are those
 * of the document of the server it shows, or `null` and `undefined` for a blank one.
 */
export interface DocumentState {
	mode: "document";
	path: string | null;
	document: string;
	styles: StylesText[];
	original?: Pick<ServerDocument, "document" | "styles">;
}

/**
 * The files of a template or a document as `GET /files/{folder}/{path}` gives them, and its `PUT` after a save: the
 * document and its chain of styles, whether the server saves them (`Save` of `server.stxt`), and after a save, the
 * files it wrote.
 */
export interface ServerFiles {
	path: string;
	document: string;
	styles: StylesText[];
	writable?: boolean;
	written?: string[];
}

/** A document as `GET /files/documents/{path}` gives it. */
export type ServerDocument = ServerFiles;

/** A template as `GET /files/templates/{path}` gives it, with its data: the text of its file, `null` without one. */
export interface ServerTemplate extends ServerFiles {
	data: string | null;
}

/** The panels of the templates mode, and the template as the server gave it. */
export interface TemplateState {
	mode: "template";
	path: string;
	document: string;
	styles: StylesText[];
	data: string;
	original: Pick<ServerTemplate, "document" | "styles" | "data">;
}

export type State = DocumentState | TemplateState;

/** An error body of the service. */
export interface ServerError {
	code: string;
	message: string;
	kind?: string;
	line?: number;
	source?: string | null;
	filled?: string;
}

/** The panel an error belongs to: `styles` with the place of the panel, from 0. */
export type Target =
	| { panel: "document" }
	| { panel: "data" }
	| { panel: "filled" }
	| { panel: "styles"; index: number };

/** A request to the service. */
export interface Request {
	method: "GET" | "POST";
	url: string;
	body?: string;
	contentType?: string;
	/** The names the styles have in the errors of this request, in the order of the panels. */
	stylesNames: string[];
}

/** An error found before asking the server: the JSON of the data panel. */
export interface LocalError {
	target: Target;
	line: number;
	message: string;
}

/** The JSON of the data panel: `undefined` when it is blank, which is no data. */
export function parseData(text: string): { value?: unknown; error?: LocalError } {
	if (text.trim() === "") {
		return {};
	}
	try {
		return { value: JSON.parse(text) };
	} catch (e) {
		const message = e instanceof Error ? e.message : String(e);
		return { error: { target: { panel: "data" }, line: jsonErrorLine(text, message), message } };
	}
}

/** The line, from 1, of a message of `JSON.parse`: `line 3 column 5` or `at position 42`; 0 if it says neither. */
export function jsonErrorLine(text: string, message: string): number {
	const line = /line (\d+) column/.exec(message);
	if (line) {
		return Number(line[1]);
	}
	const position = /position (\d+)/.exec(message);
	if (position) {
		return text.slice(0, Number(position[1])).split("\n").length;
	}
	return 0;
}

/**
 * Whether the document panel validates against the schema: not when it has data, because a document with Slots does
 * not follow `dev.stxt.pdf` until it is filled (`Margin: <margin>` is not a number). The server validates the filled
 * one.
 */
export function validatesDocument(data: string): boolean {
	return data.trim() === "";
}

/**
 * Whether the panels of a template or a document of the server are still its files, so that `/print` can convert it.
 * The line ends do not count: the server gives the files as they are, with CRLF in a Windows checkout, and the panels
 * have LF. A styles panel beyond those of the server, the blank one of a document without styles, has to stay blank.
 */
export function isUnchanged(state: TemplateState | DocumentState): boolean {
	const original = state.original;
	return original !== undefined
		&& sameText(state.document, original.document)
		&& state.styles.length >= original.styles.length
		&& state.styles.every((styles, i) => i < original.styles.length
			? sameText(styles.text, original.styles[i].text)
			: styles.text.trim() === "");
}

/** Whether two texts are the same but for their line ends (CRLF, CR or LF). */
function sameText(a: string, b: string): boolean {
	return a.replace(/\r\n?/g, "\n") === b.replace(/\r\n?/g, "\n");
}

/**
 * The request of a button. A document of the server goes to `/documents` while its document and its styles are
 * those of the server; edited, or a blank one, to `/render`. A template goes to `/print` while its document
 * and its styles are those of the server, with the data of the panel; edited, to `/render` with the whole chain of
 * styles, as the server would apply them.
 */
export function request(state: State, format: Format, debug: boolean): Request | LocalError {
	const query = `?format=${format}${debug && format === "svg" ? "&debug=true" : ""}`;
	if (state.mode === "document" && state.path !== null && isUnchanged(state)) {
		const names = state.styles.map((styles, i) => i < state.original!.styles.length ? styles.path : "");
		return { method: "GET", url: `/documents/${state.path}${query}`, stylesNames: names };
	}
	if (state.mode === "template" && isUnchanged(state)) {
		const url = `/print/${state.path}${query}`;
		const names = state.styles.map((styles) => styles.path);
		if (state.data.trim() === "") {
			// Without data in the panel, GET: the template as it is, if it has no sample data.
			if (state.original.data === null) {
				return { method: "GET", url, stylesNames: names };
			}
		} else {
			return { method: "POST", url, body: state.data, contentType: "application/json", stylesNames: names };
		}
	}
	const data: ReturnType<typeof parseData> = state.mode === "template" ? parseData(state.data) : {};
	if (data.error) {
		return data.error;
	}
	// A blank styles panel of a document is not sent; its name is "", which no error has, so that every name keeps the
	// place of its panel.
	const panels = state.mode === "document"
		? state.styles.map((item) => item.text.trim() === "" ? null : item.text)
		: state.styles.map((item) => item.text);
	const styles = panels.filter((text): text is string => text !== null);
	let sent = 0;
	const stylesNames = panels.map((text) => text === null ? "" : `styles[${++sent}]`);
	const fields = [`"document":${JSON.stringify(state.document)}`];
	if (styles.length > 0) {
		fields.push(`"styles":${JSON.stringify(styles)}`);
	}
	if (state.mode === "template" && data.value !== undefined) {
		// The text of the panel, not JSON.stringify of what JSON.parse read: 1.10 would arrive as 1.1.
		fields.push(`"data":${state.data.trim()}`);
	}
	return {
		method: "POST",
		url: `/render${query}`,
		body: `{${fields.join(",")}}`,
		contentType: "application/json",
		stylesNames,
	};
}

/** The panel of the `source` of an error, or `null` if it is of none (a resource, a limit, a cancellation). */
export function target(source: string | null | undefined, stylesNames: string[]): Target | null {
	if (source === "document" || source === "data" || source === "filled") {
		return { panel: source };
	}
	if (source) {
		const index = stylesNames.indexOf(source);
		if (index >= 0) {
			return { panel: "styles", index };
		}
	}
	return null;
}

/**
 * The message of an error of the server: its code, its line and source if it is `located` in one of the files shown
 * (a panel of the editor, a file of an entry of the index), and its message.
 */
export function serverMessage(error: ServerError, located: boolean): string {
	const place = error.line && located ? ` (line ${error.line} of ${error.source})` : "";
	return `${error.code}${place}: ${error.message}`;
}

/** A panel of a file: the title of its tab and its text. */
export interface PanelText {
	title: string;
	text: string;
}

/**
 * What the panels of a place show: the document and its styles documents (each titled by its path) and, only for a
 * template, its data.
 */
export interface Panels {
	data?: PanelText;
	document: PanelText;
	styles: StylesText[];
}

/**
 * The panels of the document mode: the document and its styles, those of the chain of a document of the server or,
 * without any, one blank panel for the ones the user writes. No data: a document is converted as it is. Without
 * `document`, a blank one.
 */
export function documentPanels(document?: ServerDocument): Panels {
	const styles = document?.styles ?? [];
	return {
		document: { title: document ? `${document.path}.stxt` : "Document", text: document?.document ?? "" },
		styles: styles.length > 0 ? styles : [{ path: "Styles", text: "" }],
	};
}

/** The panels of a template: its data (blank without sample data), its document and its chain of styles. */
export function templatePanels(template: ServerTemplate): Panels {
	return {
		data: { title: template.data === null ? "Data (JSON)" : `${template.path}.json`, text: template.data ?? "" },
		document: { title: `${template.path}.stxt`, text: template.document },
		styles: template.styles,
	};
}

/**
 * The tabs of the panels, from the most important to the least: the data first, since they are what changes from one
 * PDF to another, then the document and its styles from the nearest to the farthest (its own, that of its folder, that
 * of the folder above...). The chain stays in `panels.styles` in the order it is applied, from the root, which is the
 * order of a request and of the `styles[n]` of the errors; only the tabs go the other way. The first one is the panel
 * shown.
 */
export function tabs(panels: Panels): { target: Target; title: string }[] {
	const list: { target: Target; title: string }[] = [];
	if (panels.data) {
		list.push({ target: { panel: "data" }, title: panels.data.title });
	}
	list.push({ target: { panel: "document" }, title: panels.document.title });
	for (let index = panels.styles.length - 1; index >= 0; index--) {
		list.push({ target: { panel: "styles", index }, title: panels.styles[index].path });
	}
	return list;
}

/**
 * What the tab of a panel shows: an error with an error of the server still marked or one of its own (the syntax), a
 * warning with other problems of its own (the schema, which the STXT editor gives as warnings), or nothing.
 */
export function tabState(marked: boolean, severities: string[]): "error" | "warning" | null {
	if (marked || severities.includes("error")) {
		return "error";
	}
	return severities.length > 0 ? "warning" : null;
}

/**
 * What a save sends, and what it asks first: the body of `PUT /files/templates/{path}` or
 * `PUT /files/documents/{path}` with the files that changed, their names, and those of them that are the `.styles.stxt`
 * of a folder, shared by every template or document below it.
 */
export interface SaveRequest {
	url: string;
	body: string;
	changed: string[];
	shared: string[];
}

/**
 * The save of the panels of a template or a document of the server: only the styles and data whose text changed (the
 * line ends do not count), and the document always, which the server writes only if it changed. Text in the blank
 * styles panel of a document without styles goes to its own `<name>.styles.stxt`, which the server creates; text in
 * the data panel of a template without sample data, to its `<name>.json`. `null` for a blank document, which is no file
 * of the server.
 */
export function saveRequest(state: State): SaveRequest | null {
	if (state.path === null || state.original === undefined) {
		return null;
	}
	const original = state.original;
	const changed: string[] = [];
	if (!sameText(state.document, original.document)) {
		changed.push(`${state.path}.stxt`);
	}
	const styles: StylesText[] = [];
	state.styles.forEach((panel, i) => {
		const before = original.styles[i];
		if (before !== undefined) {
			if (!sameText(panel.text, before.text)) {
				styles.push({ path: before.path, text: panel.text });
			}
		} else if (panel.text.trim() !== "") {
			styles.push({ path: `${state.path}.styles.stxt`, text: panel.text });
		}
	});
	changed.push(...styles.map((item) => item.path));
	// The data go as the text of the panel, so this body is JSON.stringify of an object, unlike that of request.
	const body: { document: string; styles?: StylesText[]; data?: string } = { document: state.document };
	if (styles.length > 0) {
		body.styles = styles;
	}
	if (state.mode === "template") {
		const before = state.original.data;
		if (before === null ? state.data.trim() !== "" : !sameText(state.data, before)) {
			changed.push(`${state.path}.json`);
			body.data = state.data;
		}
	}
	return {
		url: filesUrl(state.mode, state.path),
		body: JSON.stringify(body),
		changed,
		shared: changed.filter((name) => name === ".styles.stxt" || name.endsWith("/.styles.stxt")),
	};
}

/**
 * Whether a save created files that the panels did not have as files: the own styles of a document that had none
 * (written from its blank styles panel), or the data of a template that had none. The panels are then shown again, as
 * the server has the files now.
 */
export function filesAdded(before: ServerDocument | ServerTemplate, saved: ServerDocument | ServerTemplate): boolean {
	const data = (files: ServerDocument | ServerTemplate) => ("data" in files ? files.data : null);
	return saved.styles.length !== before.styles.length || (data(before) === null && data(saved) !== null);
}

/** What the name of a new template or document can be: one segment of a path of the server. */
const NAME = /^[A-Za-z0-9_-]+$/;

/**
 * The path of a new template or document called `name` in `folder`, or why it cannot be: a name that is not one segment
 * of a path, or that of one already in the folder (`existing` are the paths of the list). The names are compared
 * without case: a Windows server would not tell `Letter` from `letter`, and two such names would confuse anyway.
 */
export function newEntryPath(name: string, folder: string, existing: string[], noun: string)
	: { path: string } | { problem: string } {
	const trimmed = name.trim();
	if (!NAME.test(trimmed)) {
		return { problem: "The name can have only letters, digits, - and _." };
	}
	const path = folder === "" ? trimmed : `${folder}/${trimmed}`;
	if (existing.some((other) => other.toLowerCase() === path.toLowerCase())) {
		return { problem: `There is already a ${noun} called ${trimmed} in this folder.` };
	}
	return { path };
}

/**
 * The first text of a new template or document: an A4 page with its name as title and heading, which converts as it
 * is, under the styles of its folder.
 */
export function newDocumentText(name: string): string {
	return `Document (dev.stxt.pdf):\n\tLanguage: en\n\tTitle: ${name}\n\tPage:\n\t\tPaper: A4\n\t\tMargin: 20mm\n`
		+ `\tSection:\n\t\tHeading 1:\n\t\t\tText: ${name}\n`;
}

/**
 * The request that creates a new template or document: a `PUT` of its files with `If-None-Match: *`, which the server
 * refuses if there is already one at that path.
 */
export function createRequest(mode: "template" | "document", path: string): { url: string; body: string } {
	return {
		url: filesUrl(mode, path),
		body: JSON.stringify({ document: newDocumentText(nameOf(path)) }),
	};
}

/** The URL of the files of a template or a document: `GET` reads them, and `PUT` saves or creates them. */
export function filesUrl(mode: "template" | "document", path: string): string {
	return `/files/${FOLDER[mode]}/${path}`;
}

/**
 * The URL that prints a template of the server with its sample data (`/print`) or a document as it is
 * (`/documents`): a PDF, or its SVG pages (`?format=svg`), which come faster since no PDF is written.
 */
export function printUrl(mode: "template" | "document", path: string, format: "pdf" | "svg"): string {
	return `/${mode === "template" ? "print" : "documents"}/${path}${format === "svg" ? "?format=svg" : ""}`;
}

/** How many SVG pages a preview gave, for its message: `1 page`, `3 pages`. */
export function pagesLabel(count: number): string {
	return count === 1 ? "1 page" : `${count} pages`;
}

/** The message of something that took `ms` milliseconds: `Converted in 312 ms.`, `Converted in 1.4 s.` */
export function took(what: string, ms: number): string {
	return `${what} in ${ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`}.`;
}

/**
 * The message of a call that got no answer (the server stopped or restarting, the network down): what `fetch` throws.
 */
export function unreachable(e: unknown): string {
	return `The server cannot be reached: ${e instanceof Error ? e.message : String(e)}`;
}

/** The name of the PDF that is downloaded: that of the template or the document, or `document.pdf` for a blank one. */
export function fileName(state: State): string {
	return state.path === null ? "document.pdf" : `${nameOf(state.path)}.pdf`;
}

/** `text` between single quotes for a POSIX shell. */
export function shellQuote(text: string): string {
	return `'${text.replace(/'/g, "'\\''")}'`;
}

/**
 * The curl that prints a template with the data of the panel, as the system that prints will call it. With a token,
 * it reads it from the variable `STXT_PRESS_TOKEN` instead of writing it.
 */
export function curl(origin: string, path: string, data: string, token: boolean): string {
	const parts = ["curl", "-f", "-o", `${nameOf(path)}.pdf`];
	if (token) {
		parts.push("-H", "\"Authorization: Bearer $STXT_PRESS_TOKEN\"");
	}
	if (data.trim() !== "") {
		parts.push("-H", "'Content-Type: application/json'", "--data-binary", shellQuote(data.trim()));
	}
	parts.push(`${origin}/print/${path}`);
	return parts.join(" ");
}
