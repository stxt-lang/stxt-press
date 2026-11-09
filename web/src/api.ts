/**
 * The calls to the API of the server, shared by the index and the editor: the token of the tab, the dialog that asks
 * for it on a 401, and the error body of the service; and the message line. Both pages have the dialog
 * (`#token-dialog`) and the message line (`#message`).
 */
import type { ServerError } from "./model";

const TOKEN_KEY = "stxt-press-token";

export const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

/** Writes `text` in the message line of the page (`#message`), as an error or not. */
export function say(text: string, error = false): void {
	const message = $("message");
	message.textContent = text;
	message.classList.toggle("error", error);
}

/** The token of the tab, or `null` without one. */
export function token(): string | null {
	try {
		return sessionStorage.getItem(TOKEN_KEY);
	} catch {
		return null;
	}
}

/** Asks for the token and keeps it in the `sessionStorage` of the tab; `null` if the dialog is cancelled. */
export function askToken(): Promise<string | null> {
	const dialog = $<HTMLDialogElement>("token-dialog");
	const input = $<HTMLInputElement>("token-input");
	input.value = "";
	dialog.showModal();
	return new Promise((resolve) => {
		const done = (value: string | null) => {
			$("token-ok").onclick = null;
			$("token-cancel").onclick = null;
			input.onkeydown = null;
			dialog.close();
			if (value !== null) {
				try {
					sessionStorage.setItem(TOKEN_KEY, value);
				} catch {
					// Without storage the token is asked again on the next 401.
				}
			}
			resolve(value);
		};
		$("token-ok").onclick = () => done(input.value.trim() || null);
		$("token-cancel").onclick = () => done(null);
		input.onkeydown = (event) => {
			if (event.key === "Enter") {
				done(input.value.trim() || null);
			}
		};
	});
}

/** Calls the API with the token of the page; on a 401, asks for the token and tries again. */
export async function call(method: string, url: string, body?: string, contentType?: string): Promise<Response> {
	for (;;) {
		const headers: Record<string, string> = {};
		const current = token();
		if (current) {
			headers.Authorization = `Bearer ${current}`;
		}
		if (contentType) {
			headers["Content-Type"] = contentType;
		}
		const response = await fetch(url, { method, headers, body });
		if (response.status !== 401 || (await askToken()) === null) {
			return response;
		}
	}
}

/** The error body of a response that is not `ok`. */
export async function errorOf(response: Response): Promise<ServerError> {
	try {
		return (await response.json()) as ServerError;
	} catch {
		return { code: `HTTP_${response.status}`, message: response.statusText };
	}
}
