/**
 * The viewer of what the server converts, shared by the editor (output.ts) and the index: the SVG pages, with the fonts
 * of the server registered in the page and each page in a shadow root, or the PDF in the viewer of the browser, from a
 * `blob:` URL, with a link that downloads it with its name (the viewer would save it with the random name of the blob).
 * It has no editor in it, so the index does not load CodeMirror.
 */
import { get } from "./api";

/**
 * The pages or the PDF of one part of a page, one at a time: `frame` shows the PDF, `pages` the SVG pages, and `link`
 * downloads the PDF. The `blob:` URL of a PDF is freed when something else takes its place.
 */
export class Viewer {
	private readonly frame: HTMLIFrameElement;
	private readonly pages: HTMLElement;
	private readonly link: HTMLAnchorElement;
	private url: string | undefined;

	// Without parameter properties, which the type stripping of Node (the tests) does not take.
	constructor(frame: HTMLIFrameElement, pages: HTMLElement, link: HTMLAnchorElement) {
		this.frame = frame;
		this.pages = pages;
		this.link = link;
	}

	/** Shows the SVG pages, from the first one. */
	showPages(pages: string[]): void {
		this.freePdf();
		renderPages(this.pages, pages);
		this.pages.hidden = false;
		this.pages.scrollTop = 0;
	}

	/** Shows the PDF, and the link that downloads it as `name`. */
	showPdf(blob: Blob, name: string): void {
		this.clear();
		this.url = URL.createObjectURL(blob);
		this.frame.src = this.url;
		this.frame.hidden = false;
		this.link.href = this.url;
		this.link.download = name;
		this.link.hidden = false;
	}

	/** Shows nothing, and frees the PDF. */
	clear(): void {
		this.freePdf();
		this.pages.replaceChildren();
		this.pages.hidden = true;
	}

	private freePdf(): void {
		if (this.url) {
			this.frame.src = "about:blank";
			URL.revokeObjectURL(this.url);
			this.url = undefined;
		}
		this.frame.hidden = true;
		this.link.removeAttribute("href");
		this.link.hidden = true;
	}
}

/** The fonts of the server, registered in the page by the first preview. */
let fontsLoaded: Promise<void> | undefined;

/**
 * Paints the SVG pages in `host`, each in a shadow root of its own: in the page, so that the fonts of the page apply to
 * them, and apart, because the ids of the clips of one page are those of the next one too.
 */
function renderPages(host: HTMLElement, pages: string[]): void {
	host.replaceChildren(...pages.map((svg, i) => {
		const page = document.createElement("div");
		page.className = "page";
		page.setAttribute("role", "img");
		page.setAttribute("aria-label", `Page ${i + 1}`);
		// The SVG has its size in points: it is scaled down to the width of the column.
		page.attachShadow({ mode: "open" }).innerHTML = `<style>svg { display: block; max-width: 100%; height: auto; }</style>${svg}`;
		return page;
	}));
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
		const response = await get("/fonts");
		if (!response.ok) {
			throw new Error(`/fonts answered ${response.status}`);
		}
		const faces = ((await response.json()) as { faces: { family: string; weight: number; style: string; id: string }[] }).faces;
		await Promise.all(faces.map(async (face) => {
			try {
				const file = await get(`/fonts/${face.id}`);
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
