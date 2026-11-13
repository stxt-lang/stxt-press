/**
 * The web editor of stxt-press: the panels of a document or of a template of the server, and the buttons that fill it,
 * preview it as SVG pages and show its PDF. It is opened from the index, and what it shows is in its URL
 * (`/editor/templates/{path}`, `/editor/documents/{path}` or `/editor`, see `parsePlace`), so a reload shows the same
 * template or document, read again from the server. With `Save` on in the server, the Save button (or Ctrl+S) writes
 * the files that changed. What a button asks for and where an error goes is in model.ts, what a URL shows in place.ts,
 * the panels and their tabs in panels.ts, what the buttons give in output.ts and the full window of each half in
 * maximize.ts; here are the place, the buttons and the start.
 */
import { $, askToken, call, errorOf, say, token } from "./api";
import { maximizable } from "./maximize";
import {
	curl, documentPanels, fileName, request, saveRequest, serverMessage, target, templatePanels, took, validatesDocument,
} from "./model";
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
/** The version of the files on the panels (the `ETag` of the server), which a save starts from. */
let version: string | undefined;
/** Whether the server saves what the page shows (`Save` of `server.stxt`): without it there is no Save button. */
let writable = false;

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

/** Turns the buttons on with panels, and off without them; Save, only with changes since the last save. */
function setButtons(): void {
	["fill", "preview", "pdf"].forEach((id) => ($<HTMLButtonElement>(id).disabled = shown === undefined));
	const save = $<HTMLButtonElement>("save");
	save.hidden = !writable;
	save.disabled = shown === undefined || !dirty;
}

function edited(): void {
	if (!dirty) {
		dirty = true;
		setButtons();
	}
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
	const started = performance.now();
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
			const text = await response.text();
			const ms = performance.now() - started;
			showFilled(text);
			say(took("Filled", ms));
		} else if (format === "svg") {
			const pages = ((await response.json()) as { pages: string[] }).pages;
			const ms = performance.now() - started;
			await loadFonts();
			showPages(pages);
			say(took(pages.length === 1 ? "1 page" : `${pages.length} pages`, ms));
		} else {
			const pdf = await response.blob();
			const ms = performance.now() - started;
			showPdf(pdf, fileName(state()));
			say(took("PDF", ms));
		}
	} catch (e) {
		say(`The server cannot be reached: ${e instanceof Error ? e.message : String(e)}`, true);
	} finally {
		setButtons();
	}
}

/**
 * Saves the files that changed (`PUT`), if they are still those the page read: with someone else's save in between,
 * the server answers `FILES_CHANGED` and nothing is written. A `.styles.stxt` of a folder, shared by everything below
 * it, is saved only if the user says so. Afterwards the panels are those of the server: unchanged, so the buttons print
 * them with `/print`.
 */
async function save(): Promise<void> {
	const asked = saveRequest(state());
	if (!asked || version === undefined) {
		return;
	}
	if (asked.changed.length === 0) {
		dirty = false;
		setButtons();
		say("Nothing to save: the files are as they were.");
		return;
	}
	if (asked.shared.length > 0 && !window.confirm(`${asked.shared.join(", ")} ${asked.shared.length === 1 ? "is" : "are"}`
		+ " the styles of a whole folder: saving changes everything below it too. Save?")) {
		return;
	}
	$<HTMLButtonElement>("save").disabled = true;
	say("Saving…");
	const started = performance.now();
	try {
		const response = await call("PUT", asked.url, asked.body, "application/json", { "If-Match": version });
		if (!response.ok) {
			const error = await errorOf(response);
			say(error.code === "FILES_CHANGED"
				? `${serverMessage(error, null)}. Nothing was saved. Copy the changes of the panels, and reload to see the files.`
				: serverMessage(error, null), true);
			return;
		}
		const saved = (await response.json()) as ServerTemplate & ServerDocument;
		const ms = performance.now() - started;
		version = response.headers.get("ETag") ?? undefined;
		const before = mode === "template" ? original : originalDocument;
		// A file it created (the own styles of a document without any, the data of a template without them) changes
		// the files of the panels: they are shown again, as the server has them now.
		const newFiles = !before || saved.styles.length !== before.styles.length
			|| (mode === "template" && original?.data === null && saved.data !== null);
		if (mode === "template") {
			original = saved;
		} else {
			originalDocument = saved;
		}
		if (newFiles) {
			display(mode === "template" ? templatePanels(saved) : documentPanels(saved));
		}
		dirty = false;
		const written = saved.written ?? [];
		say(written.length === 0 ? "Nothing to save: the files are as they were." : took(`Saved ${written.join(", ")}`, ms));
	} catch (e) {
		say(`The server cannot be reached: ${e instanceof Error ? e.message : String(e)}`, true);
	} finally {
		setButtons();
	}
}

// ---- The place

/** Keeps what the server says of the files it gave: their version, and whether it saves them. */
function served(response: Response, body: { writable?: boolean }): void {
	version = response.headers.get("ETag") ?? undefined;
	writable = body.writable === true && version !== undefined;
}

/** A document of the documents folder of the server, with its chain of styles. */
async function showDocument(path: string): Promise<void> {
	const response = await call("GET", `/documents/${path}`);
	if (!response.ok) {
		clear();
		say(serverMessage(await errorOf(response), null), true);
		return;
	}
	originalDocument = (await response.json()) as ServerDocument;
	served(response, originalDocument);
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
	served(response, original);
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
	$("save").onclick = () => void save();
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
	// Ctrl+S (Cmd+S on a Mac) saves, instead of saving the page in the browser.
	document.addEventListener("keydown", (event) => {
		if ((event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === "s") {
			event.preventDefault();
			const button = $<HTMLButtonElement>("save");
			if (!button.hidden && !button.disabled) {
				void save();
			}
		}
	});
	window.addEventListener("beforeunload", (event) => {
		if (dirty) {
			event.preventDefault();
			event.returnValue = "";
		}
	});
	document.querySelectorAll<HTMLElement>(".workspace > section").forEach(maximizable);
	setButtons();

	const schemaResponse = await call("GET", "/schema");
	setSchema(schemaResponse.ok ? await schemaResponse.text() : "");
	// The URL as the page writes it, without what the page does not read (a query).
	const place = parsePlace(location.pathname) ?? BLANK;
	window.history.replaceState(null, "", placeUrl(place));
	await show(place);
}

void start();
