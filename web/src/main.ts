/**
 * The web editor of stxt-press: the panels of a document or of a template of the server, and the buttons that fill it,
 * preview it as SVG pages and download its PDF. What a button asks for and where an error goes is in model.ts; here
 * are the DOM and the calls to the API.
 */
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { lintGutter, setDiagnostics } from "@codemirror/lint";
import type { Diagnostic as CmDiagnostic } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { mount, toCmDiagnostics } from "@stxt-lang/editor";
import type { Editor } from "@stxt-lang/editor";

import { curl, fileName, parseData, request, target, validatesDocument } from "./model";
import type { Format, LocalError, Request, ServerError, State, StylesText, Target } from "./model";

/** The examples of stxt-pdf in English, put here by build.mjs. */
declare const EXAMPLES: { name: string; document: string; styles: string }[];

const TOKEN_KEY = "stxt-press-token";

/** A panel: a title and an editor, of STXT or of JSON. */
interface Panel {
	element: HTMLElement;
	view: EditorView;
	text(): string;
	/** The local problems of the panel, without the one of the server. */
	local(): CmDiagnostic[];
	/** Of an STXT panel: whether it validates against the schema. */
	setValidation?(on: boolean): void;
	destroy(): void;
}

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

let schema = "";
let mode: "document" | "template" = "document";
let documentPanel: Panel;
let stylesPanels: Panel[] = [];
let dataPanel: Panel;
let filledPanel: Panel | undefined;
/** The template on the panels, as the server gave it. */
let original: { path: string; document: string; styles: StylesText[]; data: string | null } | undefined;
/** The fonts of the server, registered in the page by the first preview. */
let fontsLoaded: Promise<void> | undefined;

// ---- The panels

function stxtPanel(title: string, value: string, options: { readOnly?: boolean; onChange?: () => void } = {}): Panel {
	const element = section(title);
	let ready = false;
	const editor: Editor = mount(element.appendChild(document.createElement("div")), {
		value,
		grammars: schema ? [schema] : [],
		readOnly: options.readOnly ?? false,
		developerInfo: false,
		onChange: () => {
			if (ready) {
				options.onChange?.();
			}
		},
	});
	ready = true;
	return {
		element,
		view: editor.view,
		text: () => editor.getValue(),
		local: () => toCmDiagnostics(editor.view.state.doc, editor.getDiagnostics()),
		setValidation: (on) => editor.setValidation(on),
		destroy: () => {
			editor.destroy();
			element.remove();
		},
	};
}

function jsonPanel(title: string, value: string, onChange: () => void): Panel {
	const element = section(title);
	const view = new EditorView({
		parent: element,
		state: EditorState.create({
			doc: value,
			extensions: [
				lineNumbers(),
				history(),
				lintGutter(),
				keymap.of([...defaultKeymap, ...historyKeymap]),
				EditorView.updateListener.of((update) => {
					if (update.docChanged) {
						// An error of the server is of the text it was sent.
						update.view.dispatch(setDiagnostics(update.view.state, []));
						onChange();
					}
				}),
			],
		}),
	});
	return {
		element,
		view,
		text: () => view.state.doc.toString(),
		local: () => [],
		destroy: () => {
			view.destroy();
			element.remove();
		},
	};
}

function section(title: string): HTMLElement {
	const element = document.createElement("div");
	element.className = "panel";
	const heading = element.appendChild(document.createElement("h2"));
	heading.textContent = title;
	return element;
}

function clearPanels(): void {
	for (const panel of [documentPanel, ...stylesPanels, dataPanel]) {
		panel?.destroy();
	}
	stylesPanels = [];
	hideFilled();
}

/**
 * Shows the panels of a document: the document, its styles and its data, in this order, or with the data first
 * (`dataFirst`), for a template, whose data are what changes from one PDF to another.
 */
function showPanels(text: string, styles: StylesText[], data: string, titles: { document: string; data: string },
	dataFirst = false): void {
	clearPanels();
	documentPanel = stxtPanel(titles.document, text);
	stylesPanels = styles.map((item) => stxtPanel(item.path, item.text));
	dataPanel = jsonPanel(titles.data, data, dataChanged);
	const order = [documentPanel, ...stylesPanels];
	if (dataFirst) {
		order.unshift(dataPanel);
	} else {
		order.push(dataPanel);
	}
	$("panels").append(...order.map((panel) => panel.element));
	dataChanged();
}

function dataChanged(): void {
	// The document panel validates only without data: see validatesDocument.
	documentPanel.setValidation?.(validatesDocument(dataPanel.text()));
	if (mode === "template" && original) {
		$("curl").textContent = curl(location.origin, original.path, dataPanel.text(), token() !== null);
	}
}

/** Marks the line of an error in its panel, until the panel changes. */
function mark(panel: Panel, line: number, message: string): void {
	const diagnostics = panel.local();
	if (line > 0 && line <= panel.view.state.doc.lines) {
		const at = panel.view.state.doc.line(line);
		diagnostics.push({ from: at.from, to: at.to, severity: "error", message, source: "server" });
		panel.view.dispatch({ selection: { anchor: at.from }, scrollIntoView: true });
	}
	panel.view.dispatch(setDiagnostics(panel.view.state, diagnostics));
}

function panelOf(where: Target): Panel | undefined {
	switch (where.panel) {
		case "document":
			return documentPanel;
		case "data":
			return dataPanel;
		case "filled":
			return filledPanel;
		case "styles":
			return stylesPanels[where.index];
	}
}

function showFilled(text: string): Panel {
	hideFilled();
	const host = $("filled");
	filledPanel = stxtPanel("", text, { readOnly: true });
	filledPanel.element.querySelector("h2")?.remove();
	host.appendChild(filledPanel.element);
	$("filled-panel").hidden = false;
	return filledPanel;
}

function hideFilled(): void {
	filledPanel?.destroy();
	filledPanel = undefined;
	$("filled-panel").hidden = true;
}

// ---- The state of the panels

function state(): State {
	if (mode === "template" && original) {
		return {
			mode: "template",
			path: original.path,
			document: documentPanel.text(),
			styles: stylesPanels.map((panel, i) => ({ path: original!.styles[i].path, text: panel.text() })),
			data: dataPanel.text(),
			original,
		};
	}
	return { mode: "document", document: documentPanel.text(), styles: stylesPanels[0]?.text() ?? "", data: dataPanel.text() };
}

// ---- The API

function token(): string | null {
	try {
		return sessionStorage.getItem(TOKEN_KEY);
	} catch {
		return null;
	}
}

function askToken(): Promise<string | null> {
	const dialog = $<HTMLDialogElement>("token-dialog");
	const input = $<HTMLInputElement>("token-input");
	input.value = "";
	dialog.showModal();
	return new Promise((resolve) => {
		const done = (value: string | null) => {
			$("token-ok").onclick = null;
			$("token-cancel").onclick = null;
			input.onkeydown = null;
			dialog.close();
			if (value !== null) {
				try {
					sessionStorage.setItem(TOKEN_KEY, value);
				} catch {
					// Without storage the token is asked again on the next 401.
				}
			}
			resolve(value);
		};
		$("token-ok").onclick = () => done(input.value.trim() || null);
		$("token-cancel").onclick = () => done(null);
		input.onkeydown = (event) => {
			if (event.key === "Enter") {
				done(input.value.trim() || null);
			}
		};
	});
}

/** Calls the API with the token of the page; on a 401, asks for the token and tries again. */
async function call(method: string, url: string, body?: string, contentType?: string): Promise<Response> {
	for (;;) {
		const headers: Record<string, string> = {};
		const current = token();
		if (current) {
			headers.Authorization = `Bearer ${current}`;
		}
		if (contentType) {
			headers["Content-Type"] = contentType;
		}
		const response = await fetch(url, { method, headers, body });
		if (response.status !== 401 || (await askToken()) === null) {
			return response;
		}
	}
}

async function errorOf(response: Response): Promise<ServerError> {
	try {
		return (await response.json()) as ServerError;
	} catch {
		return { code: `HTTP_${response.status}`, message: response.statusText };
	}
}

// ---- The buttons

function say(text: string, error = false): void {
	const message = $("message");
	message.textContent = text;
	message.classList.toggle("error", error);
}

function showLocal(error: LocalError): void {
	say(`Data: ${error.message}`, true);
	const panel = panelOf(error.target);
	if (panel) {
		mark(panel, error.line, error.message);
	}
}

function showServer(error: ServerError, asked: Request): void {
	const where = target(error.source, asked.stylesNames);
	const place = error.line && where ? ` (line ${error.line} of ${error.source})` : "";
	say(`${error.code}${place}: ${error.message}`, true);
	if (!where) {
		return;
	}
	if (where.panel === "filled" && error.filled !== undefined) {
		showFilled(error.filled);
	}
	const panel = panelOf(where);
	if (panel) {
		mark(panel, error.line ?? 0, `[${error.code}] ${error.message}`);
	}
}

async function run(format: Format): Promise<void> {
	for (const panel of [documentPanel, ...stylesPanels, dataPanel]) {
		panel.view.dispatch(setDiagnostics(panel.view.state, panel.local()));
	}
	const asked = request(state(), format, $<HTMLInputElement>("debug").checked);
	if (!("url" in asked)) {
		hideFilled();
		$("pages").replaceChildren();
		showLocal(asked);
		return;
	}
	const buttons = ["fill", "preview", "pdf"].map((id) => $<HTMLButtonElement>(id));
	buttons.forEach((button) => (button.disabled = true));
	say(format === "stxt" ? "Filling…" : "Converting…");
	try {
		const response = await call(asked.method, asked.url, asked.body, asked.contentType);
		if (!response.ok) {
			// The output of the last button is not that of these panels.
			hideFilled();
			$("pages").replaceChildren();
			showServer(await errorOf(response), asked);
			return;
		}
		if (format === "stxt") {
			$("pages").replaceChildren();
			showFilled(await response.text());
			say("Filled.");
		} else if (format === "svg") {
			hideFilled();
			const pages = ((await response.json()) as { pages: string[] }).pages;
			await loadFonts();
			showPages(pages);
		} else {
			download(await response.blob(), fileName(state()));
		}
	} catch (e) {
		say(`The server cannot be reached: ${e instanceof Error ? e.message : String(e)}`, true);
	} finally {
		buttons.forEach((button) => (button.disabled = false));
	}
}

/**
 * Registers the fonts of the server in the page, once: an SVG of the engine names its fonts and does not carry them,
 * so the preview needs them in the page. They are fetched with the token, as ArrayBuffers, and given to `FontFace`.
 * If they cannot be had, the preview shows the fonts of the browser instead.
 */
function loadFonts(): Promise<void> {
	fontsLoaded ??= (async () => {
		const response = await call("GET", "/fonts");
		if (!response.ok) {
			return;
		}
		const faces = ((await response.json()) as { faces: { family: string; weight: number; style: string; id: string }[] }).faces;
		await Promise.all(faces.map(async (face) => {
			const file = await call("GET", `/fonts/${face.id}`);
			if (file.ok) {
				const font = new FontFace(face.family, await file.arrayBuffer(), { weight: String(face.weight), style: face.style });
				document.fonts.add(await font.load());
			}
		}));
	})().catch(() => undefined);
	return fontsLoaded;
}

/**
 * Shows the SVG pages, each in a shadow root of its own: in the page, so that the fonts of the page apply to them, and
 * apart, because the ids of the clips of one page are those of the next one too.
 */
function showPages(pages: string[]): void {
	const host = $("pages");
	host.replaceChildren(...pages.map((svg, i) => {
		const page = document.createElement("div");
		page.className = "page";
		page.setAttribute("role", "img");
		page.setAttribute("aria-label", `Page ${i + 1}`);
		// The SVG has its size in points: it is scaled down to the width of the column.
		page.attachShadow({ mode: "open" }).innerHTML = `<style>svg { display: block; max-width: 100%; height: auto; }</style>${svg}`;
		return page;
	}));
	say(pages.length === 1 ? "1 page." : `${pages.length} pages.`);
}

function download(blob: Blob, name: string): void {
	const url = URL.createObjectURL(blob);
	const link = document.createElement("a");
	link.href = url;
	link.download = name;
	link.click();
	setTimeout(() => URL.revokeObjectURL(url), 1000);
	say(`Downloaded ${name}.`);
}

// ---- The modes

function showExample(index: number): void {
	const example = EXAMPLES[index];
	original = undefined;
	showPanels(example.document, [{ path: "Styles", text: example.styles }], "", { document: "Document", data: "Data (JSON)" });
}

async function showTemplate(path: string): Promise<void> {
	const response = await call("GET", `/templates/${path}`);
	if (!response.ok) {
		const error = await errorOf(response);
		say(`${error.code}: ${error.message}`, true);
		return;
	}
	const template = (await response.json()) as { path: string; document: string; styles: { path: string; text: string }[]; data: string | null };
	original = { path: template.path, document: template.document, styles: template.styles, data: template.data };
	$("print-url").textContent = `${location.origin}/print/${template.path}`;
	showPanels(template.document, template.styles, template.data ?? "",
		{ document: `${template.path}.stxt`, data: template.data === null ? "Data (JSON)" : `${template.path}.json` }, true);
	say("");
}

async function setMode(next: "document" | "template"): Promise<void> {
	mode = next;
	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((button) => {
		button.setAttribute("aria-pressed", String(button.dataset.mode === next));
	});
	$("document-mode").hidden = next !== "document";
	$("template-mode").hidden = next !== "template";
	$("pages").replaceChildren();
	say("");
	if (next === "document") {
		showExample($<HTMLSelectElement>("examples").selectedIndex);
	} else {
		const select = $<HTMLSelectElement>("templates");
		if (select.value) {
			await showTemplate(select.value);
		} else {
			clearPanels();
			say("The server has no templates.");
		}
	}
}

async function start(): Promise<void> {
	const schemaResponse = await call("GET", "/schema");
	schema = schemaResponse.ok ? await schemaResponse.text() : "";

	const examples = $<HTMLSelectElement>("examples");
	EXAMPLES.forEach((example, i) => examples.add(new Option(example.name, String(i))));
	examples.onchange = () => showExample(examples.selectedIndex);

	const templates = $<HTMLSelectElement>("templates");
	const listed = await call("GET", "/templates");
	if (listed.ok) {
		for (const entry of ((await listed.json()) as { templates: { path: string }[] }).templates) {
			templates.add(new Option(entry.path, entry.path));
		}
		templates.onchange = () => void showTemplate(templates.value);
	} else {
		// /templates is off in this server.
		document.querySelector<HTMLButtonElement>(".modes button[data-mode=template]")!.hidden = true;
	}

	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((button) => {
		button.onclick = () => void setMode(button.dataset.mode as "document" | "template");
	});
	$("fill").onclick = () => void run("stxt");
	$("preview").onclick = () => void run("svg");
	$("pdf").onclick = () => void run("pdf");
	$("token").onclick = async () => {
		await askToken();
		dataChanged();
	};
	$("copy-curl").onclick = async () => {
		await navigator.clipboard.writeText($("curl").textContent ?? "");
		say("curl copied.");
	};
	await setMode("document");
}

void start();
