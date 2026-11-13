/**
 * Full window for a part of a page: a button in the part (the files of the editor, its output, the PDF of the index)
 * puts it over the whole window and back, and so does Esc. It is only CSS: the part does not move in the page, so the
 * text, the cursor, the scroll and the page of the PDF stay as they were. With the focus in the viewer of the PDF, Esc
 * does not reach the page: the button does. The idea is that of the web editor of pdf_gen.
 */

/** Adds the button to `part`, which stays where it is in the page. */
export function maximizable(part: HTMLElement): void {
	part.classList.add("maximizable");
	const button = document.createElement("button");
	button.type = "button";
	button.className = "maximize";
	button.onclick = () => maximize(part, !part.classList.contains("maximized"));
	part.appendChild(button);
	label(button, false);
}

function maximize(part: HTMLElement, on: boolean): void {
	part.classList.toggle("maximized", on);
	// No scroll of the page behind a part over the window.
	document.body.classList.toggle("with-maximized", document.querySelector(".maximized") !== null);
	const button = part.querySelector<HTMLButtonElement>(":scope > .maximize");
	if (button) {
		label(button, on);
	}
}

function label(button: HTMLButtonElement, on: boolean): void {
	button.textContent = on ? "✕" : "⛶";
	button.title = on ? "Back (Esc)" : "Full window";
	button.setAttribute("aria-label", button.title);
	button.setAttribute("aria-pressed", String(on));
}

// Unless the editor already used the Esc (to close its completion, for instance).
document.addEventListener("keydown", (event) => {
	const part = document.querySelector<HTMLElement>(".maximized");
	if (event.key === "Escape" && part && !event.defaultPrevented) {
		maximize(part, false);
	}
});
