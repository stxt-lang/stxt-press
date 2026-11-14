/**
 * The small helpers of the DOM that both pages use: an element by its id, the message line (`#message`, which both
 * pages have) and the elements the scripts make.
 */

export const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

/** Writes `text` in the message line of the page (`#message`), as an error or not. */
export function say(text: string, error = false): void {
	const message = $("message");
	message.textContent = text;
	message.classList.toggle("error", error);
}

/** An element, with its text and its class if they are given. */
export function element<K extends keyof HTMLElementTagNameMap>(tag: K, text?: string, className?: string): HTMLElementTagNameMap[K] {
	const node = document.createElement(tag);
	if (text !== undefined) {
		node.textContent = text;
	}
	if (className) {
		node.className = className;
	}
	return node;
}

export function link(text: string, href: string): HTMLAnchorElement {
	const a = element("a", text);
	a.href = href;
	return a;
}

export function button(text: string, className: string, onclick: () => void): HTMLButtonElement {
	const b = element("button", text, className);
	b.type = "button";
	b.onclick = onclick;
	return b;
}
