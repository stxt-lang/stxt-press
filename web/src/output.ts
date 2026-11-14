/**
 * The output of the editor, at the right of the panels: what the last button gave, one at a time. The filled document
 * (a read-only panel), or the SVG pages or the PDF with its download link (the viewer of viewer.ts, which the index
 * uses too). This module owns the panel of the filled document; the messages are of whoever calls it.
 */
import { $ } from "./dom";
import { stxtPanel } from "./panels";
import type { Panel } from "./panels";
import { Viewer } from "./viewer";

/** The read-only panel of the filled document, while it is the output. */
let filledPanel: Panel | undefined;
/** The pages or the PDF of the output. */
const viewer = new Viewer($<HTMLIFrameElement>("pdf-frame"), $("pages"), $<HTMLAnchorElement>("download"));

/** The panel of the filled document, if it is the output. */
export function filled(): Panel | undefined {
	return filledPanel;
}

/** Empties the output: what the last button gave is not that of the panels any more. */
export function clearOutput(): void {
	filledPanel?.destroy();
	filledPanel = undefined;
	$("filled-panel").hidden = true;
	viewer.clear();
}

/** Shows the filled document in a read-only panel, which it gives. */
export function showFilled(text: string): Panel {
	clearOutput();
	filledPanel = stxtPanel(text, { readOnly: true });
	$("filled").appendChild(filledPanel.element);
	$("filled-panel").hidden = false;
	return filledPanel;
}

/** Shows the PDF in the viewer of the browser, with a link that downloads it with its name. */
export function showPdf(blob: Blob, name: string): void {
	clearOutput();
	viewer.showPdf(blob, name);
	$("download").textContent = `Download ${name}`;
}

/** Shows the SVG pages. */
export function showPages(pages: string[]): void {
	clearOutput();
	viewer.showPages(pages);
}
