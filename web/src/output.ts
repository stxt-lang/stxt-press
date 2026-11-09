/**
 * The output of the editor, at the right of the panels: what the last button gave, one at a time. The filled document
 * (a read-only panel), the SVG pages with the fonts of the server, or the PDF in the viewer of the browser with its
 * download link. This module owns the panel of the filled document, the URL of the PDF and the fonts of the page.
 */
import { $, call, say } from "./api";
import { stxtPanel } from "./panels";
import type { Panel } from "./panels";

/** The read-only panel of the filled document, while it is the output. */
let filledPanel: Panel | undefined;
/** The URL of the PDF in the output, revoked when the output changes. */
let pdfUrl: string | undefined;
/** The fonts of the server, registered in the page by the first preview. */
let fontsLoaded: Promise<void> | undefined;

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

/**
 * Shows the SVG pages, each in a shadow root of its own: in the page, so that the fonts of the page apply to them, and
 * apart, because the ids of the clips of one page are those of the next one too.
 */
export function showPages(pages: string[]): void {
	clearOutput();
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
	showOutput("pages");
	say(pages.length === 1 ? "1 page." : `${pages.length} pages.`);
}

/**
 * Registers the fonts of the server in the page, once: an SVG of the engine names its fonts and does not carry them,
 * so the preview needs them in the page. They are fetched with the token, as ArrayBuffers, and given to `FontFace`.
 * If they cannot be had, the preview shows the fonts of the browser instead, and the next one tries again: `/fonts`
 * fails with the token dialog cancelled, or on a lazy server whose resources cannot be read yet. A face whose file
 * fails is left out and not tried again, since the others are registered already.
 */
export function loadFonts(): Promise<void> {
	fontsLoaded ??= (async () => {
		const response = await call("GET", "/fonts");
		if (!response.ok) {
			throw new Error(`/fonts answered ${response.status}`);
		}
		const faces = ((await response.json()) as { faces: { family: string; weight: number; style: string; id: string }[] }).faces;
		await Promise.all(faces.map(async (face) => {
			try {
				const file = await call("GET", `/fonts/${face.id}`);
				if (file.ok) {
					const font = new FontFace(face.family, await file.arrayBuffer(), { weight: String(face.weight), style: face.style });
					document.fonts.add(await font.load());
				}
			} catch {
				// This face is drawn with a font of the browser.
			}
		}));
	})().catch(() => {
		fontsLoaded = undefined;
	});
	return fontsLoaded;
}
