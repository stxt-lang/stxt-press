/**
 * The web editor of stxt-press: the panels of a document or of a template of the server, and the buttons that fill it,
 * preview it as SVG pages and download its PDF. What it shows is in its URL (`/editor/{path}`, see `parsePlace`), so a
 * reload shows the same template, read again from the server. What a button asks for and where an error goes is in
 * model.ts; here are the DOM and the calls to the API.
 */
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { lintGutter, setDiagnostics } from "@codemirror/lint";
import type { Diagnostic as CmDiagnostic } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { mount, toCmDiagnostics } from "@stxt-lang/editor";
import type { Editor } from "@stxt-lang/editor";

import { $, askToken, call, errorOf, token } from "./api";
import { BLANK, curl, fileName, parsePlace, placeUrl, request, samePlace, target, validatesDocument } from "./model";
import type { Format, LocalError, Place, Request, ServerError, State, StylesText, Target } from "./model";

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

let schema = "";
let mode: "document" | "template" = "document";
/** What the page shows, as its URL says. */
let current: Place = BLANK;
/** The paths of the templates of the server, empty if it has none or `/templates` is off. */
let templatePaths: string[] = [];
/** Counts the places shown, so that the answer of one left behind is not shown over the next. */
let showing = 0;
/** Whether a panel was edited in the page since it was shown: leaving it then asks first. */
let dirty = false;
/** Whether there are panels: without them (a template that cannot be read) the buttons are off. */
let hasPanels = false;
let documentPanel: Panel;
let stylesPanels: Panel[] = [];
let dataPanel: Panel;
let filledPanel: Panel | undefined;
/** The names of the styles panels of the document mode: the paths of the chain of its template, or "Styles". */
let documentStyles: string[] = [];
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
	dirty = false;
	setPanels(false);
}

/** Turns the buttons on with panels, and off without them. */
function setPanels(on: boolean): void {
	hasPanels = on;
	["fill", "preview", "pdf"].forEach((id) => ($<HTMLButtonElement>(id).disabled = !on));
}

function edited(): void {
	dirty = true;
}

/**
 * Shows the panels of a document: the document, its styles and its data, in this order, or with the data first
 * (`dataFirst`), for a template, whose data are what changes from one PDF to another.
 */
function showPanels(text: string, styles: StylesText[], data: string, titles: { document: string; data: string },
	dataFirst = false): void {
	clearPanels();
	documentPanel = stxtPanel(titles.document, text, { onChange: edited });
	stylesPanels = styles.map((item) => stxtPanel(item.path, item.text, { onChange: edited }));
	dataPanel = jsonPanel(titles.data, data, () => {
		edited();
		dataChanged();
	});
	setPanels(true);
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
	return {
		mode: "document",
		document: documentPanel.text(),
		styles: stylesPanels.map((panel, i) => ({ path: documentStyles[i], text: panel.text() })),
		data: dataPanel.text(),
	};
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
		buttons.forEach((button) => (button.disabled = !hasPanels));
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

// ---- The places

/** The template of the server as a document: filled with its sample data, with its chain of styles, without data. */
async function showDocument(path: string, seq: number): Promise<void> {
	const [response, filled] = await Promise.all([call("GET", `/templates/${path}`), call("GET", `/print/${path}?format=stxt`)]);
	if (seq !== showing) {
		return;
	}
	const failed = [response, filled].find((r) => !r.ok);
	if (failed) {
		const error = await errorOf(failed);
		original = undefined;
		clearPanels();
		say(`${error.code}: ${error.message}`, true);
		return;
	}
	const template = (await response.json()) as { styles: StylesText[] };
	const text = await filled.text();
	if (seq === showing) {
		showBlankDocument(text, template.styles);
	}
}

/** The panels of a document, with one blank styles panel if it has no styles, for the ones the user writes. */
function showBlankDocument(text = "", styles: StylesText[] = []): void {
	original = undefined;
	const panels = styles.length > 0 ? styles : [{ path: "Styles", text: "" }];
	documentStyles = panels.map((item) => item.path);
	showPanels(text, panels, "", { document: "Document", data: "Data (JSON)" });
}

async function showTemplate(path: string, seq: number): Promise<void> {
	const response = await call("GET", `/templates/${path}`);
	if (seq !== showing) {
		return;
	}
	if (!response.ok) {
		const error = await errorOf(response);
		original = undefined;
		clearPanels();
		say(`${error.code}: ${error.message}`, true);
		return;
	}
	const template = (await response.json()) as { path: string; document: string; styles: { path: string; text: string }[]; data: string | null };
	if (seq !== showing) {
		return;
	}
	original = { path: template.path, document: template.document, styles: template.styles, data: template.data };
	$("print-url").textContent = `${location.origin}/print/${template.path}`;
	showPanels(template.document, template.styles, template.data ?? "",
		{ document: `${template.path}.stxt`, data: template.data === null ? "Data (JSON)" : `${template.path}.json` }, true);
}

/** Selects `value` in `select`, or nothing if it is not one of its options. */
function choose(select: HTMLSelectElement, value: string): void {
	select.value = value;
	if (select.value !== value) {
		select.selectedIndex = -1;
	}
}

/** Shows `place`, without touching the history of the browser: the start, a link, the back and forward buttons. */
async function show(place: Place): Promise<void> {
	const seq = ++showing;
	current = place;
	mode = place.mode;
	document.title = place.path === null ? "New document · stxt-press" : `${place.path} · stxt-press`;
	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((button) => {
		button.setAttribute("aria-pressed", String(button.dataset.mode === place.mode));
	});
	$("document-mode").hidden = place.mode !== "document";
	$("template-mode").hidden = place.mode !== "template";
	choose($<HTMLSelectElement>("documents"), place.mode === "document" ? place.path ?? "" : "");
	choose($<HTMLSelectElement>("templates"), place.mode === "template" ? place.path ?? "" : "");
	$("pages").replaceChildren();
	say("");
	if (place.path === null) {
		showBlankDocument();
	} else if (place.mode === "document") {
		await showDocument(place.path, seq);
	} else {
		await showTemplate(place.path, seq);
	}
}

/** Whether the page can leave what it shows: without edits in the panels, or if the user says so. */
function canLeave(): boolean {
	return !dirty || window.confirm("The panels have changes that will be lost. Leave them?");
}

/** Goes to `place`: a new entry in the history of the browser, so that a reload shows it again. */
function go(place: Place): void {
	if (samePlace(place, current)) {
		return;
	}
	if (!canLeave()) {
		// The selects go back to what the page shows.
		choose($<HTMLSelectElement>("documents"), current.mode === "document" ? current.path ?? "" : "");
		choose($<HTMLSelectElement>("templates"), current.mode === "template" ? current.path ?? "" : "");
		return;
	}
	window.history.pushState(null, "", placeUrl(place));
	void show(place);
}

/** The place of a mode button: the same template in the other mode, or the first one if the page shows none. */
function placeOfMode(next: "document" | "template"): Place | null {
	if (next === "document") {
		return { mode: "document", path: current.path };
	}
	const path = current.path ?? templatePaths[0];
	return path === undefined ? null : { mode: "template", path };
}

async function start(): Promise<void> {
	const schemaResponse = await call("GET", "/schema");
	schema = schemaResponse.ok ? await schemaResponse.text() : "";

	// The two modes list the templates of the server: as documents, filled with their sample data, and as templates.
	const documents = $<HTMLSelectElement>("documents");
	const templates = $<HTMLSelectElement>("templates");
	documents.add(new Option("New document", ""));
	const listed = await call("GET", "/templates");
	if (listed.ok) {
		templatePaths = ((await listed.json()) as { templates: { path: string }[] }).templates.map((entry) => entry.path);
		for (const path of templatePaths) {
			documents.add(new Option(path, path));
			templates.add(new Option(path, path));
		}
	}
	if (templatePaths.length === 0) {
		// No templates, or /templates is off in this server: only the blank document.
		document.querySelector<HTMLButtonElement>(".modes button[data-mode=template]")!.hidden = true;
	}
	documents.onchange = () => go({ mode: "document", path: documents.value || null });
	templates.onchange = () => go({ mode: "template", path: templates.value });
	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((button) => {
		button.onclick = () => {
			const place = placeOfMode(button.dataset.mode as "document" | "template");
			if (place) {
				go(place);
			}
		};
	});
	$("fill").onclick = () => void run("stxt");
	$("preview").onclick = () => void run("svg");
	$("pdf").onclick = () => void run("pdf");
	$("token").onclick = async () => {
		await askToken();
		if (hasPanels) {
			dataChanged();
		}
	};
	$("copy-curl").onclick = async () => {
		await navigator.clipboard.writeText($("curl").textContent ?? "");
		say("curl copied.");
	};

	window.addEventListener("popstate", () => {
		const place = parsePlace(location.pathname, location.search) ?? BLANK;
		if (canLeave()) {
			void show(place);
		} else {
			// The page stays where it was, and so does its URL.
			window.history.pushState(null, "", placeUrl(current));
		}
	});
	window.addEventListener("beforeunload", (event) => {
		if (dirty) {
			event.preventDefault();
			event.returnValue = "";
		}
	});

	// The URL as the page writes it: /editor?mode=template, without a template, is /editor.
	const place = parsePlace(location.pathname, location.search) ?? BLANK;
	window.history.replaceState(null, "", placeUrl(place));
	await show(place);
}

void start();
