/**
 * The dialog that asks for the token, made in the page the first time it is needed, so that the pages do not carry it.
 * Its answer comes when it closes, however it closes: Use or Enter give what was written, Cancel and Esc give `null`.
 * Only one question at a time is asked (`askToken` in api.ts), so the dialog is never opened twice.
 */
import { button, element } from "./dom";

let dialog: HTMLDialogElement | undefined;
let input: HTMLInputElement;
/** The token written when the dialog closes: `null` unless Use or Enter closed it. */
let given: string | null = null;

function create(): HTMLDialogElement {
	const made = element("dialog");
	made.appendChild(element("p", "The server asks for a token."));
	input = made.appendChild(element("input"));
	input.type = "password";
	input.autocomplete = "off";
	input.setAttribute("aria-label", "Token");
	const use = (): void => {
		given = input.value.trim() || null;
		made.close();
	};
	input.onkeydown = (event) => {
		if (event.key === "Enter") {
			use();
		}
	};
	const buttons = made.appendChild(element("div", undefined, "buttons"));
	buttons.appendChild(button("Cancel", "quiet", () => made.close()));
	buttons.appendChild(button("Use", "", use));
	document.body.appendChild(made);
	return made;
}

/** Asks for the token in the dialog: what was written, or `null` if it was closed without it. */
export function askTokenInDialog(): Promise<string | null> {
	dialog ??= create();
	const open = dialog;
	given = null;
	input.value = "";
	open.showModal();
	return new Promise((resolve) => open.addEventListener("close", () => resolve(given), { once: true }));
}
