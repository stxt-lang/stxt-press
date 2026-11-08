/**
 * What the editor decides without the DOM: which request a button makes, where an error of the server goes, the JSON
 * of the data and the curl of a template. The page (main.ts) only reads the panels and paints the answers.
 */

/** The formats of `?format=`. */
export type Format = "pdf" | "svg" | "stxt";

/** A styles document of a panel: its name in the errors and its text. */
export interface StylesText {
	path: string;
	text: string;
}

/**
 * The panels of the document mode: a document, its styles documents in order (those of the chain of the template it
 * comes from, or one blank panel) and its data.
 */
export interface DocumentState {
	mode: "document";
	document: string;
	styles: StylesText[];
	data: string;
}

/** The panels of the templates mode, and the template as the server gave it. */
export interface TemplateState {
	mode: "template";
	path: string;
	document: string;
	styles: StylesText[];
	data: string;
	original: { document: string; styles: StylesText[]; data: string | null };
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

/** Whether the panels of a template are still the template of the server, so that `/print` can convert it. */
export function isUnchanged(state: TemplateState): boolean {
	return state.document === state.original.document
		&& state.styles.length === state.original.styles.length
		&& state.styles.every((styles, i) => styles.text === state.original.styles[i].text);
}

/**
 * The request of a button. A document goes to `/render`. A template goes to `/print` while its document and its
 * styles are those of the server, with the data of the panel; edited, to `/render` with the whole chain of styles, as
 * the server would apply them.
 */
export function request(state: State, format: Format, debug: boolean): Request | LocalError {
	const query = `?format=${format}${debug && format === "svg" ? "&debug=true" : ""}`;
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
	const data = parseData(state.data);
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
	if (data.value !== undefined) {
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

/** The name of the PDF that is downloaded. */
export function fileName(state: State): string {
	return state.mode === "template" ? `${state.path.slice(state.path.lastIndexOf("/") + 1)}.pdf` : "document.pdf";
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
	const name = path.slice(path.lastIndexOf("/") + 1);
	const parts = ["curl", "-f", "-o", `${name}.pdf`];
	if (token) {
		parts.push("-H", "\"Authorization: Bearer $STXT_PRESS_TOKEN\"");
	}
	if (data.trim() !== "") {
		parts.push("-H", "'Content-Type: application/json'", "--data-binary", shellQuote(data.trim()));
	}
	parts.push(`${origin}/print/${path}`);
	return parts.join(" ");
}
