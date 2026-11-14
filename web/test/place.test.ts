import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
	ancestors, BLANK, byFolder, defaultFolder, folderOf, folderRows, FOLDER, indexHash, indexHashOf, isUnder, nameOf,
	parseIndexHash, parsePlace, placeUrl, visibleRows,
} from "../src/place.ts";

describe("parsePlace and placeUrl", () => {
	it("reads a template, a document and the blank document", () => {
		assert.deepEqual(parsePlace("/editor/templates/catalogs/catalog"), { mode: "template", path: "catalogs/catalog" });
		assert.deepEqual(parsePlace("/editor/documents/reference/tables/basic"),
			{ mode: "document", path: "reference/tables/basic" });
		assert.deepEqual(parsePlace("/editor/templates/hello"), { mode: "template", path: "hello" });
		assert.deepEqual(parsePlace("/editor"), BLANK);
	});

	it("is null for what is not a URL of the editor", () => {
		for (const pathname of ["/", "/editor/", "/editor/templates/", "/editor/documents/", "/editor/templates/a//b",
			"/editor/templates/a/", "/editor/catalogs/catalog", "/editor/editor.js", "/editor/templates/../x",
			"/editor/documents/a%20b", "/editors/templates/a", "/editor/template/a", "/editor/docs/a"]) {
			assert.equal(parsePlace(pathname), null, pathname);
		}
	});

	it("writes the URL that it reads", () => {
		for (const place of [BLANK, { mode: "template" as const, path: "invoices/invoice" }, { mode: "document" as const, path: "menu" }]) {
			assert.deepEqual(parsePlace(new URL(placeUrl(place), "http://localhost").pathname), place);
		}
		assert.equal(placeUrl({ mode: "template", path: "catalogs/catalog" }), "/editor/templates/catalogs/catalog");
		assert.equal(placeUrl({ mode: "document", path: "examples/poster" }), "/editor/documents/examples/poster");
		assert.equal(placeUrl(BLANK), "/editor");
	});
});

describe("byFolder", () => {
	it("groups the entries by folder, in the order they first appear", () => {
		const groups = byFolder([{ path: "a/a1" }, { path: "a/b/y" }, { path: "a/c" }, { path: "root" }]);
		assert.deepEqual(groups.map((g) => [g.folder, g.entries.map((t) => t.path)]),
			[["a", ["a/a1", "a/c"]], ["a/b", ["a/b/y"]], ["", ["root"]]]);
		assert.deepEqual(byFolder([]), []);
	});
});

describe("the hash of the index", () => {
	it("reads a tab and a folder, and the documents tab without a valid one", () => {
		assert.deepEqual(parseIndexHash("#documents/reference/01-values"), { tab: "documents", folder: "reference/01-values" });
		assert.deepEqual(parseIndexHash("#templates"), { tab: "templates", folder: null });
		assert.deepEqual(parseIndexHash("#api"), { tab: "api", folder: null });
		assert.deepEqual(parseIndexHash("#api/x"), { tab: "api", folder: null });
		assert.deepEqual(parseIndexHash(""), { tab: "documents", folder: null });
		assert.deepEqual(parseIndexHash("#pdf/a"), { tab: "documents", folder: null });
		assert.deepEqual(parseIndexHash("#documents/a//b"), { tab: "documents", folder: null });
		assert.deepEqual(parseIndexHash("#documents/../x"), { tab: "documents", folder: null });
	});

	it("writes the hash that it reads", () => {
		for (const place of [{ tab: "documents" as const, folder: "reference/14-tables" }, { tab: "api" as const, folder: null },
			{ tab: "templates" as const, folder: null }]) {
			assert.deepEqual(parseIndexHash(indexHash(place)), place);
		}
	});

	it("is the folder of a place of the editor in its tab", () => {
		assert.equal(indexHashOf({ mode: "document", path: "reference/14-tables/01-basic" }), "#documents/reference/14-tables");
		assert.equal(indexHashOf({ mode: "template", path: "invoices/invoice" }), "#templates/invoices");
		assert.equal(indexHashOf({ mode: "template", path: "hello" }), "#templates");
		assert.equal(indexHashOf(BLANK), "#documents");
	});
});

describe("the folders of the index", () => {
	const paths = ["examples/poster/blue-hour", "examples/slides/review", "hello", "reference/01-values/01-lengths",
		"reference/01-values/02-colors", "reference/02-box/01-margin", "reference-old/a"];

	it("are a tree, each folder before those below it, with the entries in it and below it", () => {
		assert.deepEqual(folderRows(paths).map((row) => [row.path, row.label, row.depth, row.count, row.parent]), [
			["", "/", 0, 7, false],
			["examples", "examples", 0, 2, true],
			["examples/poster", "poster", 1, 1, false],
			["examples/slides", "slides", 1, 1, false],
			["reference", "reference", 0, 3, true],
			["reference/01-values", "01-values", 1, 2, false],
			["reference/02-box", "02-box", 1, 1, false],
			["reference-old", "reference-old", 0, 1, false],
		]);
		assert.deepEqual(folderRows(["a/b/c"]).map((row) => row.path), ["a", "a/b"]);
		assert.deepEqual(folderRows([]), []);
	});

	it("show those whose folders above are open", () => {
		const rows = folderRows(paths);
		assert.deepEqual(visibleRows(rows, new Set(["reference"])).map((row) => row.path),
			["", "examples", "reference", "reference/01-values", "reference/02-box", "reference-old"]);
		assert.deepEqual(ancestors("a/b/c"), ["a", "a/b"]);
		assert.deepEqual(ancestors("a"), []);
		assert.deepEqual(ancestors(""), []);
	});

	it("start at the first folder with entries of its own", () => {
		assert.equal(defaultFolder(folderRows(paths), paths), "");
		const nested = ["examples/poster/blue-hour", "reference/01-values/a"];
		assert.equal(defaultFolder(folderRows(nested), nested), "examples/poster");
		assert.equal(defaultFolder([], []), null);
	});

	it("hold the entries in a folder and below it", () => {
		assert.deepEqual(paths.filter((path) => isUnder(path, "examples")), ["examples/poster/blue-hour", "examples/slides/review"]);
		assert.equal(paths.filter((path) => isUnder(path, "reference")).length, 3);
		assert.equal(paths.filter((path) => isUnder(path, "")).length, 7);
		assert.equal(folderOf("a/b/c"), "a/b");
		assert.equal(folderOf("c"), "");
	});
});

describe("the name and the folder of a path", () => {
	it("are its last segment and what is before it", () => {
		assert.equal(nameOf("invoices/2026/invoice"), "invoice");
		assert.equal(folderOf("invoices/2026/invoice"), "invoices/2026");
		assert.equal(nameOf("letter"), "letter");
		assert.equal(folderOf("letter"), "");
	});

	it("and the folder of each mode names it", () => {
		assert.equal(FOLDER.template, "templates");
		assert.equal(FOLDER.document, "documents");
	});
});
