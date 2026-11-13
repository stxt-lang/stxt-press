/**
 * Full window for a part of a page: a button in the part (the files of the editor, its output, the PDF of the index)
 * puts it over the window, under the bar of the page, which stays with its buttons and gets a cross that puts the part
 * back; so does Esc. It is only CSS: the part does not move in the page, so the text, the cursor, the scroll and the
 * page of the PDF stay as they were. With the focus in the viewer of the PDF, Esc does not reach the page: the cross
 * does. The idea is that of the web editor of pdf_gen.
 */

/** Adds the button to `part`, which stays where it is in the page. */
export function maximizable(part: HTMLElement): void {
	part.classList.add("maximizable");
	const button = document.createElement("button");
	button.type = "button";
	button.className = "maximize";
	button.textContent = "⛶";
	button.title = "Full window";
	button.setAttribute("aria-label", button.title);
	button.onclick = () => maximize(part, true);
	part.appendChild(button);
}

/** The bar of the page, which a part over the window leaves in view. */
function bar(): HTMLElement | null {
	return document.querySelector<HTMLElement>("header.bar");
}

/** The cross in the bar that puts the part back, made the first time; before the token button, at the right. */
function cross(): HTMLButtonElement | null {
	const host = bar();
	if (!host) {
		return null;
	}
	let button = host.querySelector<HTMLButtonElement>(".restore");
	if (!button) {
		button = document.createElement("button");
		button.type = "button";
		button.className = "restore quiet";
		button.textContent = "✕";
		button.title = "Back (Esc)";
		button.setAttribute("aria-label", button.title);
		button.hidden = true;
		button.onclick = restore;
		host.insertBefore(button, host.querySelector("#token"));
	}
	return button;
}

function maximize(part: HTMLElement, on: boolean): void {
	part.classList.toggle("maximized", on);
	place(part);
	// No scroll of the page behind a part over the window.
	document.body.classList.toggle("with-maximized", document.querySelector(".maximized") !== null);
	const button = cross();
	if (button) {
		button.hidden = !on;
	}
}

/** Puts back the part over the window, if there is one. */
function restore(): void {
	const part = document.querySelector<HTMLElement>(".maximized");
	if (part) {
		maximize(part, false);
	}
}

/** The top of a part over the window: under the bar, whose height changes when it wraps in a narrow window. */
function place(part: HTMLElement): void {
	const host = bar();
	part.style.top = part.classList.contains("maximized") && host ? `${host.getBoundingClientRect().bottom}px` : "";
}

// Unless the editor already used the Esc (to close its completion, for instance).
document.addEventListener("keydown", (event) => {
	if (event.key === "Escape" && !event.defaultPrevented) {
		restore();
	}
});

window.addEventListener("resize", () => {
	const part = document.querySelector<HTMLElement>(".maximized");
	if (part) {
		place(part);
	}
});
