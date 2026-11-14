/**
 * The web editor of stxt-press: the panels of a document or of a template of the server, and the buttons that fill it,
 * preview it as SVG pages and show its PDF. It is opened from the index, and what it shows is in its URL
 * (`/editor/templates/{path}`, `/editor/documents/{path}` or `/editor`, see `parsePlace`), so a reload shows the same
 * template or document, read again from the server. With `Save` on in the server, the Save button (or Ctrl+S) writes
 * the files that changed. What a button asks for and where an error goes is in model.ts, what a URL shows in place.ts,
 * the panels and their tabs in panels.ts, what the buttons give in output.ts and the full window of each half in
 * maximize.ts; here are the place, the buttons and the start.
 */
import { askToken, call, errorOf, get, setTokenAsker, token } from "./api";
import { $, say } from "./dom";
import { maximizable, restore } from "./maximize";
import {
	curl, documentPanels, fileName, filesAdded, filesUrl, pagesLabel, request, saveRequest, serverMessage, target, templatePanels, took,
	unreachable, validatesDocument,
} from "./model";
import type {
	Format, LocalError, Panels, Request, ServerDocument, ServerError, ServerTemplate, State, Target,
} from "./model";
import { clearOutput, filled, showFilled, showPages, showPdf } from "./output";
import { clearPanels, mark, panelOf, setSchema, showPanels, texts, unmarkAll, validateDocument } from "./panels";
import type { Panel } from "./panels";
import { BLANK, FOLDER, indexHashOf, parsePlace, placeUrl } from "./place";
import type { Place } from "./place";
import { setupThemeToggle } from "./theme";
import { askTokenInDialog } from "./tokenDialog";
import { loadFonts } from "./viewer";

/**
 * What the page shows, as the server gave it: a template, or a document of the server (`server`) or a blank one (no
 * `server`). `version` is that of its files (the `ETag` of the server), which a save starts from; `writable`, whether
 * the server saves them (`Save` of `server.stxt`): without it there is no Save button.
 */
type Loaded = (
	| { mode: "template"; server: ServerTemplate }
	| { mode: "document"; server?: ServerDocument }
) & { version?: string; writable: boolean };

/** A blank document, which nothing on the server holds. */
const BLANK_LOADED: Loaded = { mode: "document", writable: false };

let loaded: Loaded = BLANK_LOADED;
/** What the panels show; `undefined` without panels (a template that cannot be read), and then the buttons are off. */
let shown: Panels | undefined;
/** Whether a panel was edited in the page since it was shown: leaving it then asks first. */
let dirty = false;

/** The panels of what is loaded: the data first for a template, then the document and its styles. */
function panelsOf(what: Loaded): Panels {
	return what.mode === "template" ? templatePanels(what.server) : documentPanels(what.server);
}

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
	save.hidden = !loaded.writable;
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
	if (loaded.mode === "template") {
		$("curl").textContent = curl(location.origin, loaded.server.path, data, token() !== null);
	}
}

/** The panel of `where`: one of the files, or the filled document of the output. */
function panelAt(where: Target): Panel | undefined {
	return where.panel === "filled" ? filled() : panelOf(where);
}

/** The texts of the panels `panels` shows, with what is loaded. */
function state(panels: Panels): State {
	const now = texts();
	const styles = now.styles.map((text, i) => ({ path: panels.styles[i].path, text }));
	if (loaded.mode === "template") {
		const template = loaded.server;
		return {
			mode: "template", path: template.path, document: now.document, styles, data: now.data, original: template,
		};
	}
	const server = loaded.server;
	return { mode: "document", path: server?.path ?? null, document: now.document, styles, original: server };
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
	say(serverMessage(error, where !== null), true);
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
	// What the button gives is shown in the output: a part over the window that is not the output (the files) goes
	// back, so that it is seen, and so are the errors in the files. The output over the window stays, and shows it.
	if (!shown) {
		return;
	}
	restore(document.querySelector(".workspace .output"));
	unmarkAll();
	const current = state(shown);
	const asked = request(current, format, $<HTMLInputElement>("debug").checked);
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
		const response = await call(asked);
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
			say(took(pagesLabel(pages.length), ms));
		} else {
			const pdf = await response.blob();
			const ms = performance.now() - started;
			showPdf(pdf, fileName(current));
			say(took("PDF", ms));
		}
	} catch (e) {
		say(unreachable(e), true);
	} finally {
		setButtons();
	}
}

/**
 * Saves the files that changed (`PUT`), if they are still those the page read: with someone else's save in between,
 * the server answers `FILES_CHANGED` and nothing is written. A `.styles.stxt` of a folder, shared by everything below
 * it, is saved only if the user says so. Afterwards the panels are those of the server: unchanged, so the buttons print
 * them with `/print` or `/documents`.
 */
async function save(): Promise<void> {
	// A blank document is no file of the server, and without a version there is nothing to save over.
	const before = loaded;
	if (!shown || !before.server || before.version === undefined) {
		return;
	}
	const asked = saveRequest(state(shown));
	if (!asked) {
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
		const response = await call({ method: "PUT", url: asked.url, body: asked.body, contentType: "application/json",
			headers: { "If-Match": before.version } });
		if (!response.ok) {
			const error = await errorOf(response);
			say(error.code === "FILES_CHANGED"
				? `${serverMessage(error, false)}. Nothing was saved. Copy the changes of the panels, and reload to see the files.`
				: serverMessage(error, false), true);
			return;
		}
		const saved = (await response.json()) as ServerTemplate | ServerDocument;
		const ms = performance.now() - started;
		// The files as the server has them now, which the next save starts from.
		const version = response.headers.get("ETag") ?? undefined;
		loaded = before.mode === "template"
			? { ...before, server: saved as ServerTemplate, version }
			: { ...before, server: saved, version };
		if (filesAdded(before.server, saved)) {
			display(panelsOf(loaded));
		}
		dirty = false;
		const written = saved.written ?? [];
		say(written.length === 0 ? "Nothing to save: the files are as they were." : took(`Saved ${written.join(", ")}`, ms));
	} catch (e) {
		say(unreachable(e), true);
	} finally {
		setButtons();
	}
}

// ---- The place

/**
 * Reads a template or a document of the server, with its version and whether the server saves it, and shows its
 * panels; a template, also with the URL and the curl that print it.
 */
async function load(mode: Place["mode"], path: string): Promise<void> {
	const response = await get(filesUrl(mode, path));
	if (!response.ok) {
		clear();
		say(serverMessage(await errorOf(response), false), true);
		return;
	}
	const version = response.headers.get("ETag") ?? undefined;
	const server = (await response.json()) as ServerTemplate | ServerDocument;
	const writable = server.writable === true && version !== undefined;
	loaded = mode === "template"
		? { mode, server: server as ServerTemplate, version, writable }
		: { mode, server, version, writable };
	if (loaded.mode === "template") {
		$("print-url").textContent = `${location.origin}/print/${loaded.server.path}`;
		$("print").hidden = false;
	}
	display(panelsOf(loaded));
}

/**
 * Shows what the URL says: a template, a document of the server, or a blank document. The page shows one place: it is
 * opened from the index, which is where another one is chosen.
 */
async function show(place: Place): Promise<void> {
	document.title = place.path === null ? "New document · stxt-press" : `${place.path} · stxt-press`;
	$("place").textContent = place.path === null ? "New document" : `${FOLDER[place.mode]}/${place.path}`;
	// The index goes back to the folder of what the page shows.
	$<HTMLAnchorElement>("home").href = `/${indexHashOf(place)}`;
	// A document is converted as it is: no data, so nothing to fill.
	$("fill").hidden = place.mode === "document";
	if (place.path === null) {
		loaded = BLANK_LOADED;
		display(panelsOf(loaded));
	} else {
		await load(place.mode, place.path);
	}
}

async function start(): Promise<void> {
	setupThemeToggle($("theme-toggle"));
	setTokenAsker(askTokenInDialog);
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
		const curlText = $("curl");
		try {
			await navigator.clipboard.writeText(curlText.textContent ?? "");
			say("curl copied.");
		} catch {
			// The browser gives the clipboard only to a secure page (HTTPS, or localhost): from any other address, such
			// as that of a server in a container, the curl is selected for the user to copy.
			const range = document.createRange();
			range.selectNodeContents(curlText);
			getSelection()?.removeAllRanges();
			getSelection()?.addRange(range);
			say("This page cannot copy (it is not HTTPS or localhost): the curl is selected, copy it with Ctrl+C.");
		}
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

	try {
		const schemaResponse = await get("/schema");
		setSchema(schemaResponse.ok ? await schemaResponse.text() : "");
		// The URL as the page writes it, without what the page does not read (a query).
		const place = parsePlace(location.pathname) ?? BLANK;
		window.history.replaceState(null, "", placeUrl(place));
		await show(place);
	} catch (e) {
		clear();
		say(unreachable(e), true);
	}
}

void start();
