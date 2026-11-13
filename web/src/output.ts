/**
 * The output of the editor, at the right of the panels: what the last button gave, one at a time. The filled document
 * (a read-only panel), the SVG pages with the fonts of the server (pages.ts), or the PDF in the viewer of the browser
 * with its download link. This module owns the panel of the filled document and the URL of the PDF.
 */
import { $, say } from "./api";
import { renderPages } from "./pages";
import { stxtPanel } from "./panels";
import type { Panel } from "./panels";

export { loadFonts } from "./pages";

/** The read-only panel of the filled document, while it is the output. */
let filledPanel: Panel | undefined;
/** The URL of the PDF in the output, revoked when the output changes. */
let pdfUrl: string | undefined;

/** The panel of the filled document, if it is the output. */
export function filled(): Panel | undefined {
	return filledPanel;
}

/** Empties the output: what the last button gave is not that of the panels any more. */
export function clearOutput(): void {
	filledPanel?.destroy();
	filledPanel = undefined;
	$("pages").replaceChildren();
	const frame = $<HTMLIFrameElement>("pdf-frame");
	if (pdfUrl) {
		frame.src = "about:blank";
		URL.revokeObjectURL(pdfUrl);
		pdfUrl = undefined;
	}
	const link = $<HTMLAnchorElement>("download");
	link.removeAttribute("href");
	link.hidden = true;
	showOutput(null);
}

function showOutput(output: "filled" | "pages" | "pdf" | null): void {
	$("filled-panel").hidden = output !== "filled";
	$("pages").hidden = output !== "pages";
	$("pdf-frame").hidden = output !== "pdf";
}

/** Shows the filled document in a read-only panel, which it gives. */
export function showFilled(text: string): Panel {
	clearOutput();
	filledPanel = stxtPanel(text, { readOnly: true });
	$("filled").appendChild(filledPanel.element);
	showOutput("filled");
	return filledPanel;
}

/** Shows the PDF in the viewer of the browser, with a link that downloads it with its name. */
export function showPdf(blob: Blob, name: string): void {
	clearOutput();
	pdfUrl = URL.createObjectURL(blob);
	$<HTMLIFrameElement>("pdf-frame").src = pdfUrl;
	const link = $<HTMLAnchorElement>("download");
	link.href = pdfUrl;
	link.download = name;
	link.textContent = `Download ${name}`;
	link.hidden = false;
	showOutput("pdf");
	say("");
}

/** Shows the SVG pages (pages.ts). */
export function showPages(pages: string[]): void {
	clearOutput();
	renderPages($("pages"), pages);
	showOutput("pages");
	say(pages.length === 1 ? "1 page." : `${pages.length} pages.`);
}
