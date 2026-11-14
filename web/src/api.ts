/**
 * The calls to the API of the server, shared by the index and the editor: the token of the tab, asked for on a 401, and
 * the error body of the service. It has no DOM: what asks for the token is given by each page (`setTokenAsker`, the
 * dialog of tokenDialog.ts), so that the calls are tested with `node --test` and a fake `fetch`.
 */
import type { ServerError } from "./model";

const TOKEN_KEY = "stxt-press-token";

/** A call to the API: `headers` are others than the token and the type of the body, such as `If-Match`. */
export interface ApiRequest {
	method: string;
	url: string;
	body?: string;
	contentType?: string;
	headers?: Record<string, string>;
}

/**
 * The token of the tab: kept here, and in the `sessionStorage` of the tab so that a reload keeps it (it is cleared
 * when the tab is closed). `undefined` until it is read from there.
 */
let current: string | null | undefined;
/** What asks the user for the token: `null` if they do not give one. */
let asker: (() => Promise<string | null>) | undefined;
/** The question for the token while it is asked: every call that gets a 401 meanwhile waits for the same answer. */
let asking: Promise<string | null> | undefined;

/** The token of the tab, or `null` without one. */
export function token(): string | null {
	if (current === undefined) {
		try {
			current = sessionStorage.getItem(TOKEN_KEY);
		} catch {
			current = null;
		}
	}
	return current;
}

/** Keeps `value` as the token of the tab, or forgets it with `null`. */
export function setToken(value: string | null): void {
	current = value;
	try {
		if (value === null) {
			sessionStorage.removeItem(TOKEN_KEY);
		} else {
			sessionStorage.setItem(TOKEN_KEY, value);
		}
	} catch {
		// Without storage the token lasts until the page is left.
	}
}

/** Sets what asks the user for the token; without it (`undefined`), a 401 is returned as it is. */
export function setTokenAsker(ask: (() => Promise<string | null>) | undefined): void {
	asker = ask;
}

/**
 * Asks for the token and keeps it; `null` if none was given, and then the token of the tab stays as it was. Only one
 * question at a time: a second one while the first is open gets its answer.
 */
export function askToken(): Promise<string | null> {
	if (!asker) {
		return Promise.resolve(null);
	}
	asking ??= asker().then((value) => {
		if (value !== null) {
			setToken(value);
		}
		return value;
	}).finally(() => {
		asking = undefined;
	});
	return asking;
}

/** Calls the API with the token of the tab; on a 401, asks for the token and tries again, until none is given. */
export async function call(request: ApiRequest): Promise<Response> {
	for (;;) {
		const headers: Record<string, string> = { ...request.headers };
		const sent = token();
		if (sent) {
			headers.Authorization = `Bearer ${sent}`;
		}
		if (request.contentType) {
			headers["Content-Type"] = request.contentType;
		}
		const response = await fetch(request.url, { method: request.method, headers, body: request.body });
		if (response.status !== 401) {
			return response;
		}
		// A token given while this call was on its way is tried before asking.
		if (token() === sent && (await askToken()) === null) {
			return response;
		}
	}
}

/** A `GET` of the API. */
export function get(url: string): Promise<Response> {
	return call({ method: "GET", url });
}

/** The error body of a response that is not `ok`. */
export async function errorOf(response: Response): Promise<ServerError> {
	try {
		return (await response.json()) as ServerError;
	} catch {
		return { code: `HTTP_${response.status}`, message: response.statusText };
	}
}
