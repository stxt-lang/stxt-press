import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { themeOf, toggledTheme } from "../src/theme.ts";

describe("themeOf", () => {
	it("follows the system while none is chosen", () => {
		assert.equal(themeOf(undefined, false), "light");
		assert.equal(themeOf(undefined, true), "dark");
	});

	it("keeps the one chosen, whatever the system", () => {
		assert.equal(themeOf("dark", false), "dark");
		assert.equal(themeOf("light", true), "light");
	});
});

describe("toggledTheme", () => {
	it("chooses the other theme when it is not that of the system", () => {
		assert.equal(toggledTheme(undefined, false), "dark");
		assert.equal(toggledTheme(undefined, true), "light");
	});

	it("chooses none when the other theme is that of the system", () => {
		assert.equal(toggledTheme("dark", false), undefined);
		assert.equal(toggledTheme("light", true), undefined);
	});
});
