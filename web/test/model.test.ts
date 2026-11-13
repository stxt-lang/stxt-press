import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
	createRequest, curl, documentPanels, fileName, isUnchanged, jsonErrorLine, newDocumentText, newEntryPath, parseData, printUrl, request, saveRequest, serverMessage, tabState, tabs,
	target, templatePanels, took, validatesDocument,
} from "../src/model.ts";
import type { DocumentState, Request, TemplateState } from "../src/model.ts";
import {
	ancestors, BLANK, byFolder, defaultFolder, folderOf, folderRows, indexHash, indexHashOf, isUnder, parseIndexHash, parsePlace,
	placeUrl, visibleRows,
} from "../src/place.ts";

const DOCUMENT: DocumentState = {
	mode: "document", path: null, document: "Document (dev.stxt.pdf):", styles: [{ path: "Styles", text: "" }],
};

/** A document of the server, `reference/tables/basic`, in its panels as the server gave it. */
function serverDocument(changes: Partial<DocumentState> = {}): DocumentState {
	const styles = [{ path: "reference/.styles.stxt", text: "shared" }];
	return {
		mode: "document",
		path: "reference/tables/basic",
		document: "doc",
		styles: styles.map((s) => ({ ...s })),
		original: { document: "doc", styles },
		...changes,
	};
}

function template(changes: Partial<TemplateState> = {}): TemplateState {
	const styles = [{ path: ".styles.stxt", text: "root" }, { path: "invoices/invoice.styles.stxt", text: "own" }];
	return {
		mode: "template",
		path: "invoices/invoice",
		document: "doc",
		styles: styles.map((s) => ({ ...s })),
		data: "{\"a\": 1}",
		original: { document: "doc", styles, data: "{\"a\": 1}" },
		...changes,
	};
}

function asRequest(value: ReturnType<typeof request>): Request {
	assert.ok("url" in value, JSON.stringify(value));
	return value;
}

describe("request", () => {
	it("sends a document to /render, without styles or data when their panels are blank", () => {
		const r = asRequest(request(DOCUMENT, "pdf", false));

		assert.equal(r.method, "POST");
		assert.equal(r.url, "/render?format=pdf");
		assert.deepEqual(JSON.parse(r.body!), { document: "Document (dev.stxt.pdf):" });
		assert.deepEqual(r.stylesNames, [""]);
	});

	it("sends the styles of a document as styles[1], and no data", () => {
		const styles = [{ path: "Styles", text: "Styles (dev.stxt.pdf):" }];
		const r = asRequest(request({ ...DOCUMENT, styles }, "svg", true));

		assert.equal(r.url, "/render?format=svg&debug=true");
		assert.deepEqual(JSON.parse(r.body!), { document: DOCUMENT.document, styles: ["Styles (dev.stxt.pdf):"] });
		assert.deepEqual(r.stylesNames, ["styles[1]"]);
	});

	it("sends the chain of styles of a document in order, without its blank panels, each name in the place of its panel", () => {
		const styles = [
			{ path: ".styles.stxt", text: "root" },
			{ path: "invoices/.styles.stxt", text: " \n" },
			{ path: "invoices/invoice.styles.stxt", text: "own" },
		];

		const r = asRequest(request({ ...DOCUMENT, styles }, "pdf", false));

		assert.deepEqual(JSON.parse(r.body!).styles, ["root", "own"]);
		assert.deepEqual(r.stylesNames, ["styles[1]", "", "styles[2]"]);
		assert.deepEqual(target("styles[2]", r.stylesNames), { panel: "styles", index: 2 });
	});

	it("sends the data of an edited template to /render as they are written: 1.10 stays 1.10", () => {
		const r = asRequest(request(template({ document: "edited", data: " {\"price\": 1.10}\n" }), "pdf", false));

		assert.ok(r.body!.endsWith(",\"data\":{\"price\": 1.10}}"), r.body);
	});

	it("asks for the outlines only in the preview", () => {
		assert.equal(asRequest(request(DOCUMENT, "pdf", true)).url, "/render?format=pdf");
	});

	it("prints an unchanged template with the data of the panel, as they are", () => {
		const r = asRequest(request(template({ data: "{\"a\": 1.10}" }), "pdf", false));

		assert.equal(r.method, "POST");
		assert.equal(r.url, "/print/invoices/invoice?format=pdf");
		assert.equal(r.body, "{\"a\": 1.10}");
		assert.deepEqual(r.stylesNames, [".styles.stxt", "invoices/invoice.styles.stxt"]);
	});

	it("prints a template without sample data and without data with a GET", () => {
		const t = template({ data: "" });
		t.original.data = null;

		const r = asRequest(request(t, "stxt", false));

		assert.equal(r.method, "GET");
		assert.equal(r.body, undefined);
	});

	it("renders an edited template with its whole chain of styles", () => {
		const t = template();
		t.styles[1].text = "edited";

		const r = asRequest(request(t, "pdf", false));

		assert.equal(r.url, "/render?format=pdf");
		assert.deepEqual(JSON.parse(r.body!), { document: "doc", styles: ["root", "edited"], data: { a: 1 } });
		assert.deepEqual(r.stylesNames, ["styles[1]", "styles[2]"]);
	});

	it("renders a template with its data cleared without data, not with the sample ones", () => {
		const r = asRequest(request(template({ data: " " }), "pdf", false));

		assert.equal(r.url, "/render?format=pdf");
		assert.deepEqual(JSON.parse(r.body!), { document: "doc", styles: ["root", "own"] });
	});

	it("prints an unchanged document of the server with a GET of /documents, its styles by their files", () => {
		const r = asRequest(request(serverDocument(), "svg", true));

		assert.equal(r.method, "GET");
		assert.equal(r.url, "/documents/reference/tables/basic?format=svg&debug=true");
		assert.equal(r.body, undefined);
		assert.deepEqual(r.stylesNames, ["reference/.styles.stxt"]);
	});

	it("prints a document of the server without styles, its blank styles panel left blank", () => {
		const d = serverDocument({ styles: [{ path: "Styles", text: " " }], original: { document: "doc", styles: [] } });

		const r = asRequest(request(d, "pdf", false));

		assert.equal(r.url, "/documents/reference/tables/basic?format=pdf");
		assert.deepEqual(r.stylesNames, [""]);
	});

	it("renders an edited document of the server, or one with styles written in its blank panel", () => {
		const edited = asRequest(request(serverDocument({ document: "edited" }), "pdf", false));
		assert.equal(edited.url, "/render?format=pdf");
		assert.deepEqual(JSON.parse(edited.body!), { document: "edited", styles: ["shared"] });
		assert.deepEqual(edited.stylesNames, ["styles[1]"]);

		const written = serverDocument({ styles: [{ path: "Styles", text: "mine" }], original: { document: "doc", styles: [] } });
		assert.equal(asRequest(request(written, "pdf", false)).url, "/render?format=pdf");
	});

	it("does not send data that is not JSON to /render, and says the line", () => {
		const error = request(template({ document: "edited", data: "{\n  \"a\": 1,\n}" }), "pdf", false);

		assert.ok(!("url" in error));
		assert.deepEqual(error.target, { panel: "data" });
		assert.equal(error.line, 3);
	});
});

describe("the panels", () => {
	it("validate the document only without data", () => {
		assert.equal(validatesDocument(""), true);
		assert.equal(validatesDocument("  \n"), true);
		assert.equal(validatesDocument("{}"), false);
	});

	it("tell a changed template from the one of the server", () => {
		assert.equal(isUnchanged(template()), true);
		assert.equal(isUnchanged(template({ document: "other" })), false);
		assert.equal(isUnchanged(template({ data: "{}" })), true);
	});

	it("tell a changed document from the one of the server, and a blank one is never the server's", () => {
		assert.equal(isUnchanged(serverDocument()), true);
		assert.equal(isUnchanged(serverDocument({ document: "other" })), false);
		assert.equal(isUnchanged(DOCUMENT), false);
	});

	it("take a template with CRLF from the server as unchanged in panels with LF, and print it", () => {
		const t = template({ document: "doc\n\tline\n" });
		t.original = { document: "doc\r\n\tline\r\n", styles: [{ path: ".styles.stxt", text: "root\r\n" },
			{ path: "invoices/invoice.styles.stxt", text: "own" }], data: "{\"a\": 1}\r\n" };
		t.styles[0].text = "root\n";

		assert.equal(isUnchanged(t), true);
		assert.equal(asRequest(request(t, "pdf", false)).url, "/print/invoices/invoice?format=pdf");
		assert.equal(isUnchanged({ ...t, document: "doc\n\tother\n" }), false);
	});

	it("take the error of each source", () => {
		const names = [".styles.stxt", "invoices/invoice.styles.stxt"];

		assert.deepEqual(target("document", names), { panel: "document" });
		assert.deepEqual(target("data", names), { panel: "data" });
		assert.deepEqual(target("filled", names), { panel: "filled" });
		assert.deepEqual(target("invoices/invoice.styles.stxt", names), { panel: "styles", index: 1 });
		assert.deepEqual(target("styles[1]", ["styles[1]"]), { panel: "styles", index: 0 });
		assert.equal(target(null, names), null);
		assert.equal(target("styles[3]", names), null);
	});

	it("are a document and its styles in the document mode, with one blank styles panel without styles", () => {
		const blank = documentPanels();
		assert.deepEqual(blank, { document: { title: "Document", text: "" }, styles: [{ path: "Styles", text: "" }] });
		assert.deepEqual(tabs(blank).map((tab) => tab.title), ["Document", "Styles"]);

		const alone = documentPanels({ path: "posters/fair", document: "text", styles: [] });
		assert.deepEqual(tabs(alone).map((tab) => tab.title), ["posters/fair.stxt", "Styles"]);

		const chain = [{ path: "reference/.styles.stxt", text: "root" }, { path: "reference/tables/basic.styles.stxt", text: "own" }];
		const panels = documentPanels({ path: "reference/tables/basic", document: "text", styles: chain });
		assert.deepEqual(panels.styles, chain);
		assert.deepEqual(panels.document, { title: "reference/tables/basic.stxt", text: "text" });
	});

	it("are the data first, then the document and its chain of styles, for a template", () => {
		const styles = [{ path: ".styles.stxt", text: "root" }, { path: "invoices/invoice.styles.stxt", text: "own" }];
		const panels = templatePanels({ path: "invoices/invoice", document: "doc", styles, data: "{}" });

		assert.deepEqual(tabs(panels), [
			{ target: { panel: "data" }, title: "invoices/invoice.json" },
			{ target: { panel: "document" }, title: "invoices/invoice.stxt" },
			{ target: { panel: "styles", index: 0 }, title: ".styles.stxt" },
			{ target: { panel: "styles", index: 1 }, title: "invoices/invoice.styles.stxt" },
		]);
		assert.deepEqual(panels.data, { title: "invoices/invoice.json", text: "{}" });
	});

	it("have a blank data panel for a template without sample data", () => {
		const panels = templatePanels({ path: "hello", document: "doc", styles: [], data: null });

		assert.deepEqual(panels.data, { title: "Data (JSON)", text: "" });
		assert.deepEqual(tabs(panels).map((tab) => tab.title), ["Data (JSON)", "hello.stxt"]);
	});

	it("mark their tab with an error of the server or of their own, and with a warning", () => {
		assert.equal(tabState(false, []), null);
		assert.equal(tabState(true, []), "error");
		assert.equal(tabState(false, ["warning", "error"]), "error");
		assert.equal(tabState(false, ["warning"]), "warning");
		assert.equal(tabState(true, ["warning"]), "error");
	});
});

describe("the message of an error of the server", () => {
	it("says the line and the source of a panel", () => {
		const error = { code: "INVALID_VALUE", message: "Not a number", line: 8, source: "styles[2]" };

		assert.equal(serverMessage(error, { panel: "styles", index: 1 }), "INVALID_VALUE (line 8 of styles[2]): Not a number");
	});

	it("says no line for an error of no panel, or without a line", () => {
		assert.equal(serverMessage({ code: "RESOURCE", message: "No font", line: 3, source: "x" }, null), "RESOURCE: No font");
		assert.equal(serverMessage({ code: "LIMIT", message: "Too many pages", source: "document" }, { panel: "document" }),
			"LIMIT: Too many pages");
	});
});

describe("the data", () => {
	it("are none when blank", () => {
		assert.deepEqual(parseData(" \n"), {});
	});

	it("give the line of an error from its position or from its line", () => {
		assert.equal(jsonErrorLine("{\n\n x}", "Unexpected token x at position 4"), 3);
		assert.equal(jsonErrorLine("", "Expected ',' after property value in JSON at position 9 (line 2 column 3)"), 2);
		assert.equal(jsonErrorLine("", "Unexpected end of JSON input"), 0);
	});
});

describe("curl", () => {
	it("prints the template with the data of the panel, quoted for the shell", () => {
		assert.equal(curl("http://h:8080", "invoices/invoice", "{\"name\": \"O'Brien\"}\n", false),
			"curl -f -o invoice.pdf -H 'Content-Type: application/json' --data-binary '{\"name\": \"O'\\''Brien\"}' "
			+ "http://h:8080/print/invoices/invoice");
	});

	it("takes the token from a variable, and without data is a GET", () => {
		assert.equal(curl("http://h", "a", "", true),
			"curl -f -o a.pdf -H \"Authorization: Bearer $STXT_PRESS_TOKEN\" http://h/print/a");
	});

	it("names the PDF after the template or the document", () => {
		assert.equal(fileName(template()), "invoice.pdf");
		assert.equal(fileName(serverDocument()), "basic.pdf");
		assert.equal(fileName(DOCUMENT), "document.pdf");
	});
});

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

describe("the save", () => {
	it("sends the document and only the styles and the data that changed, with their names", () => {
		const t = template({ data: "{\"a\": 2}" });
		t.styles[1].text = "own changed";

		const s = saveRequest(t)!;

		assert.equal(s.url, "/files/templates/invoices/invoice");
		assert.deepEqual(JSON.parse(s.body), {
			document: "doc",
			styles: [{ path: "invoices/invoice.styles.stxt", text: "own changed" }],
			data: "{\"a\": 2}",
		});
		assert.deepEqual(s.changed, ["invoices/invoice.styles.stxt", "invoices/invoice.json"]);
		assert.deepEqual(s.shared, []);
	});

	it("says nothing changed when the panels are the files but for their line ends", () => {
		const t = template({ document: "doc\n" });
		t.original = { ...t.original, document: "doc\r\n" };

		const s = saveRequest(t)!;

		assert.deepEqual(s.changed, []);
		assert.deepEqual(JSON.parse(s.body), { document: "doc\n" });
	});

	it("tells the styles of a folder, shared by everything below it", () => {
		const t = template({ document: "edited" });
		t.styles[0].text = "root changed";

		const s = saveRequest(t)!;

		assert.deepEqual(s.changed, ["invoices/invoice.stxt", ".styles.stxt"]);
		assert.deepEqual(s.shared, [".styles.stxt"]);
	});

	it("creates the own styles of a document from its blank panel, and the data of a template without them", () => {
		const d = serverDocument({ styles: [{ path: "Styles", text: "mine" }], original: { document: "doc", styles: [] } });
		const doc = saveRequest(d)!;
		assert.equal(doc.url, "/files/documents/reference/tables/basic");
		assert.deepEqual(JSON.parse(doc.body).styles, [{ path: "reference/tables/basic.styles.stxt", text: "mine" }]);

		const blank = saveRequest(serverDocument({ styles: [{ path: "Styles", text: " " }], original: { document: "doc", styles: [] } }))!;
		assert.deepEqual(blank.changed, []);

		const t = template({ data: "{}" });
		t.original.data = null;
		assert.deepEqual(saveRequest(t)!.changed, ["invoices/invoice.json"]);
		t.data = "  ";
		assert.deepEqual(saveRequest(t)!.changed, []);
	});

	it("is none for a blank document, which is no file of the server", () => {
		assert.equal(saveRequest(DOCUMENT), null);
	});

	it("says how long something took", () => {
		assert.equal(took("PDF", 312.4), "PDF in 312 ms.");
		assert.equal(took("Saved a.stxt", 1440), "Saved a.stxt in 1.4 s.");
	});
});

describe("a new template or document", () => {
	const existing = ["examples/blue-hour", "examples/Letter", "reference/01-values/01-lengths"];

	it("is a name of one segment in the folder chosen", () => {
		assert.deepEqual(newEntryPath(" poster ", "examples", existing, "document"), { path: "examples/poster" });
		assert.deepEqual(newEntryPath("my_report-2", "", existing, "document"), { path: "my_report-2" });
	});

	it("is not a name with other characters, nor a path", () => {
		for (const name of ["", "  ", "a/b", "a.stxt", "a b", "../x", ".styles", "año"]) {
			assert.ok("problem" in newEntryPath(name, "examples", existing, "document"), name);
		}
	});

	it("is not one the folder already has, whatever its case", () => {
		assert.deepEqual(newEntryPath("blue-hour", "examples", existing, "document"),
			{ problem: "There is already a document called blue-hour in this folder." });
		assert.ok("problem" in newEntryPath("letter", "examples", existing, "template"));
		// The same name in another folder is another path.
		assert.deepEqual(newEntryPath("blue-hour", "", existing, "document"), { path: "blue-hour" });
	});

	it("starts as a page with its name, created with a PUT of its document", () => {
		const text = newDocumentText("poster");
		assert.ok(text.startsWith("Document (dev.stxt.pdf):\n"), text);
		assert.match(text, /\n\tTitle: poster\n/);
		assert.match(text, /\n\tPage:\n/);
		assert.match(text, /\n\t\t\tText: poster\n/);
		assert.deepEqual(createRequest("document", "examples/poster"),
			{ url: "/files/documents/examples/poster", body: JSON.stringify({ document: text }) });
		assert.equal(createRequest("template", "examples/poster").url, "/files/templates/examples/poster");
	});
});

describe("the previews of the index", () => {
	it("print a template with /print and a document with /documents, as a PDF or as SVG pages", () => {
		assert.equal(printUrl("template", "examples/invoice", "pdf"), "/print/examples/invoice");
		assert.equal(printUrl("template", "examples/invoice", "svg"), "/print/examples/invoice?format=svg");
		assert.equal(printUrl("document", "examples/blue-hour", "pdf"), "/documents/examples/blue-hour");
		assert.equal(printUrl("document", "examples/blue-hour", "svg"), "/documents/examples/blue-hour?format=svg");
	});
});
