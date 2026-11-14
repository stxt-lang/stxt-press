import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";

import { askToken, call, get, setToken, setTokenAsker, token } from "../src/api.ts";

/** The calls the fake `fetch` got: the URL, the method, the headers and the body. */
let calls: { url: string; method?: string; headers: Record<string, string>; body?: string }[] = [];

/** A fake `fetch` that answers 200 to the token `good` and 401 to any other, or `status` to everything if it is given. */
function server(status?: number): void {
	globalThis.fetch = (async (url: string, init: RequestInit = {}) => {
		const headers = (init.headers ?? {}) as Record<string, string>;
		calls.push({ url, method: init.method, headers, body: init.body as string | undefined });
		await new Promise((resolve) => setTimeout(resolve, 1));
		const answer = status ?? (headers.Authorization === "Bearer good" ? 200 : 401);
		return new Response(answer === 200 ? "ok" : '{"code":"UNAUTHORIZED","message":"A token is needed"}', { status: answer });
	}) as typeof fetch;
}

/** An asker that gives `answer` and counts how many times it was asked. */
function asker(answer: string | null): { asked: number } {
	const counter = { asked: 0 };
	setTokenAsker(async () => {
		counter.asked++;
		await new Promise((resolve) => setTimeout(resolve, 5));
		return answer;
	});
	return counter;
}

describe("the calls to the API", () => {
	beforeEach(() => {
		calls = [];
		setToken(null);
		asker(null);
	});

	it("send the method, the body, its type and the other headers, and no token without one", async () => {
		server(200);
		const response = await call({ method: "PUT", url: "/files/documents/a", body: "{}", contentType: "application/json",
			headers: { "If-Match": "\"v1\"" } });
		assert.equal(response.status, 200);
		assert.deepEqual(calls, [{ url: "/files/documents/a", method: "PUT", body: "{}",
			headers: { "If-Match": "\"v1\"", "Content-Type": "application/json" } }]);
	});

	it("ask for the token on a 401, keep it and call again with it", async () => {
		server();
		const counter = asker("good");
		const response = await get("/schema");
		assert.equal(response.status, 200);
		assert.equal(counter.asked, 1);
		assert.deepEqual(calls.map((c) => c.headers.Authorization), [undefined, "Bearer good"]);
		assert.equal(token(), "good");
		// The next call carries it, without asking.
		assert.equal((await get("/fonts")).status, 200);
		assert.equal(counter.asked, 1);
	});

	it("ask again while the token given is refused", async () => {
		server();
		const answers = ["bad", "good"];
		setTokenAsker(async () => answers.shift() ?? null);
		assert.equal((await get("/schema")).status, 200);
		assert.deepEqual(calls.map((c) => c.headers.Authorization), [undefined, "Bearer bad", "Bearer good"]);
	});

	it("give the 401 when no token is given, and keep the token there was", async () => {
		server();
		setToken("old");
		const counter = asker(null);
		const response = await get("/schema");
		assert.equal(response.status, 401);
		assert.equal(counter.asked, 1);
		assert.equal(calls.length, 1);
		assert.equal(token(), "old");
	});

	it("give the 401 as it is without anything that asks", async () => {
		server();
		setTokenAsker(undefined);
		assert.equal((await get("/schema")).status, 401);
		assert.equal(await askToken(), null);
	});

	it("ask once for two calls that get a 401 at the same time, and call both again with the token", async () => {
		server();
		const counter = asker("good");
		const [one, two] = await Promise.all([get("/print/a?format=pdf"), get("/print/b?format=pdf")]);
		assert.equal(one.status, 200);
		assert.equal(two.status, 200);
		assert.equal(counter.asked, 1);
		assert.deepEqual(calls.filter((c) => c.headers.Authorization === "Bearer good").map((c) => c.url).sort(),
			["/print/a?format=pdf", "/print/b?format=pdf"]);
	});

	it("ask once for two calls whose 401s arrive while the first one is being answered", async () => {
		server();
		const counter = asker(null);
		const [one, two] = await Promise.all([get("/a"), get("/b")]);
		assert.equal(one.status, 401);
		assert.equal(two.status, 401);
		assert.equal(counter.asked, 1);
	});
});
