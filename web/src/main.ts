/**
 * The web editor of stxt-press: the panels of a document or of a template of the server, and the buttons that fill it,
 * preview it as SVG pages and show its PDF. What it shows is in its URL (`/editor/templates/{path}`,
 * `/editor/documents/{path}`, see `parsePlace`), so a reload shows the same template or document, read again from the
 * server. What a button asks for and where an error goes is in
 * model.ts, what a URL shows in place.ts, the panels and their tabs in panels.ts, and what the buttons give in
 * output.ts; here are the places, the buttons and the start.
 */
import { $, askToken, call, errorOf, say, token } from "./api";
import { curl, documentPanels, fileName, request, serverMessage, target, templatePanels, validatesDocument } from "./model";
import type { Format, LocalError, Panels, Request, ServerDocument, ServerError, ServerTemplate, State, Target } from "./model";
import { clearOutput, filled, loadFonts, showFilled, showPages, showPdf } from "./output";
import { clearPanels, mark, panelOf, setSchema, showPanels, texts, unmarkAll, validateDocument } from "./panels";
import type { Panel } from "./panels";
import { BLANK, indexHashOf, parsePlace, placeOfMode, placeUrl, samePlace } from "./place";
import type { Place } from "./place";

let mode: Place["mode"] = "document";
/** What the page shows, as its URL says. */
let current: Place = BLANK;
/** The paths of the templates of the server, empty if it has none or `/templates` is off. */
let templatePaths: string[] = [];
/** The paths of the documents of the server, empty if it has none or `/documents` is off. */
let documentPaths: string[] = [];
/** Counts the places shown, so that the answer of one left behind is not shown over the next. */
let showing = 0;
/** Whether a panel was edited in the page since it was shown: leaving it then asks first. */
let dirty = false;
/** The template on the panels, as the server gave it, in the templates mode. */
let original: ServerTemplate | undefined;
/** The document of the server on the panels, as the server gave it, in the document mode; none for a blank one. */
let originalDocument: ServerDocument | undefined;
/** What the panels show; `undefined` without panels (a template that cannot be read), and then the buttons are off. */
let shown: Panels | undefined;

// ---- The panels

/** Removes the panels and the output, and turns the buttons off. */
function clear(): void {
	clearPanels();
	clearOutput();
	shown = undefined;
	dirty = false;
	setButtons();
}

/**
 * Shows `panels`: the document and its styles for the document mode, which converts a document as it is; the data
 * first and then the document and its styles for a template, whose data are what changes from one PDF to another.
 */
function display(panels: Panels): void {
	clear();
	showPanels(panels, edited, dataChanged);
	shown = panels;
	setButtons();
	dataChanged();
}

/** Turns the buttons on with panels, and off without them. */
function setButtons(): void {
	["fill", "preview", "pdf"].forEach((id) => ($<HTMLButtonElement>(id).disabled = shown === undefined));
}

function edited(): void {
	dirty = true;
}

function dataChanged(): void {
	const data = texts().data;
	// The document panel validates only without data: see validatesDocument.
	validateDocument(validatesDocument(data));
	if (mode === "template" && original) {
		$("curl").textContent = curl(location.origin, original.path, data, token() !== null);
	}
}

/** The panel of `where`: one of the files, or the filled document of the output. */
function panelAt(where: Target): Panel | undefined {
	return where.panel === "filled" ? filled() : panelOf(where);
}

function state(): State {
	const panels = texts();
	const styles = panels.styles.map((text, i) => ({ path: shown!.styles[i].path, text }));
	if (mode === "template" && original) {
		return { mode: "template", path: original.path, document: panels.document, styles, data: panels.data, original };
	}
	return {
		mode: "document",
		path: originalDocument?.path ?? null,
		document: panels.document,
		styles,
		original: originalDocument,
	};
}

// ---- The buttons

function showLocal(error: LocalError): void {
	say(`Data: ${error.message}`, true);
	const panel = panelAt(error.target);
	if (panel) {
		mark(panel, error.line, error.message);
	}
}

function showServer(error: ServerError, asked: Request): void {
	const where = target(error.source, asked.stylesNames);
	say(serverMessage(error, where), true);
	if (!where) {
		return;
	}
	if (where.panel === "filled" && error.filled !== undefined) {
		showFilled(error.filled);
	}
	const panel = panelAt(where);
	if (panel) {
		mark(panel, error.line ?? 0, `[${error.code}] ${error.message}`);
	}
}

/**
 * Runs a button on the panels and shows what the server answers. The list, the mode buttons, back and forward stay on
 * while it runs: if they show another place before the answer comes, the answer is of panels that are not there any
 * more, and nothing of it is shown, neither the output nor the error nor the message.
 */
async function run(format: Format): Promise<void> {
	const seq = showing;
	const left = () => seq !== showing;
	unmarkAll();
	const asked = request(state(), format, $<HTMLInputElement>("debug").checked);
	if (!("url" in asked)) {
		clearOutput();
		showLocal(asked);
		return;
	}
	const buttons = ["fill", "preview", "pdf"].map((id) => $<HTMLButtonElement>(id));
	buttons.forEach((button) => (button.disabled = true));
	say(format === "stxt" ? "Filling…" : "Converting…");
	try {
		const response = await call(asked.method, asked.url, asked.body, asked.contentType);
		if (left()) {
			return;
		}
		if (!response.ok) {
			const error = await errorOf(response);
			if (left()) {
				return;
			}
			// The output of the last button is not that of these panels.
			clearOutput();
			showServer(error, asked);
			return;
		}
		if (format === "stxt") {
			const text = await response.text();
			if (!left()) {
				showFilled(text);
				say("Filled.");
			}
		} else if (format === "svg") {
			const pages = ((await response.json()) as { pages: string[] }).pages;
			await loadFonts();
			if (!left()) {
				showPages(pages);
			}
		} else {
			const pdf = await response.blob();
			if (!left()) {
				showPdf(pdf, fileName(state()));
			}
		}
	} catch (e) {
		if (!left()) {
			say(`The server cannot be reached: ${e instanceof Error ? e.message : String(e)}`, true);
		}
	} finally {
		// Always: they go with the panels the page shows now, which may be those of another place.
		setButtons();
	}
}

// ---- The places

/** A document of the documents folder of the server, with its chain of styles. */
async function showDocument(path: string, seq: number): Promise<void> {
	const response = await call("GET", `/documents/${path}`);
	if (seq !== showing) {
		return;
	}
	if (!response.ok) {
		const error = await errorOf(response);
		original = undefined;
		originalDocument = undefined;
		clear();
		say(serverMessage(error, null), true);
		return;
	}
	const served = (await response.json()) as ServerDocument;
	if (seq !== showing) {
		return;
	}
	original = undefined;
	originalDocument = served;
	display(documentPanels(served));
}

/** The panels of a blank document, with one blank styles panel, for the ones the user writes. */
function showBlankDocument(): void {
	original = undefined;
	originalDocument = undefined;
	display(documentPanels());
}

async function showTemplate(path: string, seq: number): Promise<void> {
	const response = await call("GET", `/templates/${path}`);
	if (seq !== showing) {
		return;
	}
	if (!response.ok) {
		const error = await errorOf(response);
		original = undefined;
		originalDocument = undefined;
		clear();
		say(serverMessage(error, null), true);
		return;
	}
	const template = (await response.json()) as ServerTemplate;
	if (seq !== showing) {
		return;
	}
	original = template;
	originalDocument = undefined;
	$("print-url").textContent = `${location.origin}/print/templates/${template.path}`;
	display(templatePanels(template));
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
	// The index goes back to the folder of what the page shows.
	$<HTMLAnchorElement>("home").href = `/${indexHashOf(place)}`;
	document.querySelectorAll<HTMLButtonElement>(".modes button").forEach((button) => {
		button.setAttribute("aria-pressed", String(button.dataset.mode === place.mode));
	});
	$("document-mode").hidden = place.mode !== "document";
	$("template-mode").hidden = place.mode !== "template";
	// A document is converted as it is: no data, so nothing to fill.
	$("fill").hidden = place.mode === "document";
	choose($<HTMLSelectElement>("documents"), place.mode === "document" ? place.path ?? "" : "");
	choose($<HTMLSelectElement>("templates"), place.mode === "template" ? place.path ?? "" : "");
	clearOutput();
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

async function start(): Promise<void> {
	const schemaResponse = await call("GET", "/schema");
	setSchema(schemaResponse.ok ? await schemaResponse.text() : "");

	// The document mode lists the documents of the server after the blank one, and the templates mode its templates.
	const documents = $<HTMLSelectElement>("documents");
	const templates = $<HTMLSelectElement>("templates");
	documents.add(new Option("New document", ""));
	const [listedDocuments, listedTemplates] = await Promise.all([call("GET", "/documents"), call("GET", "/templates")]);
	if (listedDocuments.ok) {
		documentPaths = ((await listedDocuments.json()) as { documents: { path: string }[] }).documents.map((entry) => entry.path);
		for (const path of documentPaths) {
			documents.add(new Option(path, path));
		}
	}
	if (listedTemplates.ok) {
		templatePaths = ((await listedTemplates.json()) as { templates: { path: string }[] }).templates.map((entry) => entry.path);
		for (const path of templatePaths) {
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
			const next = button.dataset.mode as Place["mode"];
			// The mode already shown stays where it is.
			const place = next === current.mode ? null : placeOfMode(next, documentPaths, templatePaths);
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
		if (shown) {
			dataChanged();
		}
	};
	$("copy-curl").onclick = async () => {
		await navigator.clipboard.writeText($("curl").textContent ?? "");
		say("curl copied.");
	};

	window.addEventListener("popstate", () => {
		const place = parsePlace(location.pathname) ?? BLANK;
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

	// The URL as the page writes it, without what the page does not read (a query).
	const place = parsePlace(location.pathname) ?? BLANK;
	window.history.replaceState(null, "", placeUrl(place));
	await show(place);
}

void start();
