/**
 * The SVG pages of a preview, shared by the editor (output.ts) and the index: the fonts of the server registered in the
 * page, and the pages painted each in a shadow root. It has no editor in it, so the index does not load CodeMirror.
 */
import { call } from "./api";

/** The fonts of the server, registered in the page by the first preview. */
let fontsLoaded: Promise<void> | undefined;

/**
 * Paints the SVG pages in `host`, each in a shadow root of its own: in the page, so that the fonts of the page apply to
 * them, and apart, because the ids of the clips of one page are those of the next one too.
 */
export function renderPages(host: HTMLElement, pages: string[]): void {
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
