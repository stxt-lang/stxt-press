/**
 * The web editor of stxt-press: the panels of a document or of a template of the server, and the buttons that fill it,
 * preview it as SVG pages and show its PDF. It is opened from the index, and what it shows is in its URL
 * (`/editor/templates/{path}`, `/editor/documents/{path}` or `/editor`, see `parsePlace`), so a reload shows the same
 * template or document, read again from the server. What a button asks for and where an error goes is in model.ts,
 * what a URL shows in place.ts, the panels and their tabs in panels.ts, and what the buttons give in output.ts; here
 * are the place, the buttons and the start.
 */
import { $, askToken, call, errorOf, say, token } from "./api";
import { curl, documentPanels, fileName, request, serverMessage, target, templatePanels, validatesDocument } from "./model";
import type { Format, LocalError, Panels, Request, ServerDocument, ServerError, ServerTemplate, State, Target } from "./model";
import { clearOutput, filled, loadFonts, showFilled, showPages, showPdf } from "./output";
import { clearPanels, mark, panelOf, setSchema, showPanels, texts, unmarkAll, validateDocument } from "./panels";
import type { Panel } from "./panels";
import { BLANK, indexHashOf, parsePlace, placeUrl } from "./place";
import type { Place } from "./place";

/** The mode of what the page shows: a document (blank, or of the server) or a template. */
let mode: Place["mode"] = "document";
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

/** Runs a button on the panels and shows what the server answers. */
async function run(format: Format): Promise<void> {
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
		if (!response.ok) {
			const error = await errorOf(response);
			// The output of the last button is not that of these panels.
			clearOutput();
			showServer(error, asked);
			return;
		}
		if (format === "stxt") {
			showFilled(await response.text());
			say("Filled.");
		} else if (format === "svg") {
			const pages = ((await response.json()) as { pages: string[] }).pages;
			await loadFonts();
			showPages(pages);
		} else {
			showPdf(await response.blob(), fileName(state()));
		}
	} catch (e) {
		say(`The server cannot be reached: ${e instanceof Error ? e.message : String(e)}`, true);
	} finally {
		setButtons();
	}
}

// ---- The place

/** A document of the documents folder of the server, with its chain of styles. */
async function showDocument(path: string): Promise<void> {
	const response = await call("GET", `/documents/${path}`);
	if (!response.ok) {
		clear();
		say(serverMessage(await errorOf(response), null), true);
		return;
	}
	originalDocument = (await response.json()) as ServerDocument;
	display(documentPanels(originalDocument));
}

/** A template of the server: its data, its document and its chain of styles, with the URL and the curl that print it. */
async function showTemplate(path: string): Promise<void> {
	const response = await call("GET", `/templates/${path}`);
	if (!response.ok) {
		clear();
		say(serverMessage(await errorOf(response), null), true);
		return;
	}
	original = (await response.json()) as ServerTemplate;
	$("print-url").textContent = `${location.origin}/print/templates/${original.path}`;
	$("print").hidden = false;
	display(templatePanels(original));
}

/**
 * Shows what the URL says: a template, a document of the server, or a blank document. The page shows one place: it is
 * opened from the index, which is where another one is chosen.
 */
async function show(place: Place): Promise<void> {
	mode = place.mode;
	document.title = place.path === null ? "New document · stxt-press" : `${place.path} · stxt-press`;
	$("place").textContent = place.path === null ? "New document" : `${place.mode === "template" ? "templates" : "documents"}/${place.path}`;
	// The index goes back to the folder of what the page shows.
	$<HTMLAnchorElement>("home").href = `/${indexHashOf(place)}`;
	// A document is converted as it is: no data, so nothing to fill.
	$("fill").hidden = place.mode === "document";
	if (place.path === null) {
		display(documentPanels());
	} else if (place.mode === "document") {
		await showDocument(place.path);
	} else {
		await showTemplate(place.path);
	}
}

async function start(): Promise<void> {
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
	window.addEventListener("beforeunload", (event) => {
		if (dirty) {
			event.preventDefault();
			event.returnValue = "";
		}
	});
	setButtons();

	const schemaResponse = await call("GET", "/schema");
	setSchema(schemaResponse.ok ? await schemaResponse.text() : "");
	// The URL as the page writes it, without what the page does not read (a query).
	const place = parsePlace(location.pathname) ?? BLANK;
	window.history.replaceState(null, "", placeUrl(place));
	await show(place);
}

void start();
