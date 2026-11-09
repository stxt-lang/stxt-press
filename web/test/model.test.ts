import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
	curl, documentPanels, fileName, isUnchanged, jsonErrorLine, parseData, request, serverMessage, tabState, tabs, target,
	templatePanels, validatesDocument,
} from "../src/model.ts";
import type { DocumentState, Request, TemplateState } from "../src/model.ts";
import { BLANK, byFolder, parsePlace, placeOfMode, placeUrl, samePlace } from "../src/place.ts";

const DOCUMENT: DocumentState = { mode: "document", document: "Document (dev.stxt.pdf):", styles: [{ path: "Styles", text: "" }] };

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
		const blank = documentPanels("text", []);
		assert.deepEqual(blank, { document: { title: "Document", text: "text" }, styles: [{ path: "Styles", text: "" }] });
		assert.deepEqual(tabs(blank).map((tab) => tab.title), ["Document", "Styles"]);

		const chain = [{ path: ".styles.stxt", text: "root" }, { path: "menus/menu.styles.stxt", text: "own" }];
		assert.deepEqual(documentPanels("filled", chain).styles, chain);
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

	it("names the PDF after the template", () => {
		assert.equal(fileName(template()), "invoice.pdf");
		assert.equal(fileName(DOCUMENT), "document.pdf");
	});
});

describe("parsePlace and placeUrl", () => {
	it("reads a template, the same template as a document and the blank document", () => {
		assert.deepEqual(parsePlace("/editor/catalogs/catalog", ""), { mode: "template", path: "catalogs/catalog" });
		assert.deepEqual(parsePlace("/editor/catalogs/catalog", "?mode=document"), { mode: "document", path: "catalogs/catalog" });
		assert.deepEqual(parsePlace("/editor/hello", "?mode=template"), { mode: "template", path: "hello" });
		assert.deepEqual(parsePlace("/editor", ""), BLANK);
		assert.deepEqual(parsePlace("/editor", "?mode=template"), BLANK);
	});

	it("is null for what is not a URL of the editor", () => {
		for (const [pathname, search] of [["/", ""], ["/editor/", ""], ["/editor/a//b", ""], ["/editor/a/", ""],
			["/editor/editor.js", ""], ["/editor/../x", ""], ["/editor/a%20b", ""], ["/editors/a", ""], ["/editor/a", "?mode=pdf"]]) {
			assert.equal(parsePlace(pathname, search), null, pathname + search);
		}
	});

	it("writes the URL that it reads", () => {
		for (const place of [BLANK, { mode: "template" as const, path: "invoices/invoice" }, { mode: "document" as const, path: "menu" }]) {
			const url = new URL(placeUrl(place), "http://localhost");
			assert.deepEqual(parsePlace(url.pathname, url.search), place);
		}
		assert.equal(placeUrl({ mode: "template", path: "catalogs/catalog" }), "/editor/catalogs/catalog");
		assert.equal(placeUrl({ mode: "document", path: "catalogs/catalog" }), "/editor/catalogs/catalog?mode=document");
		assert.equal(placeUrl(BLANK), "/editor");
	});

	it("compares places", () => {
		assert.ok(samePlace({ mode: "template", path: "a" }, { mode: "template", path: "a" }));
		assert.ok(!samePlace({ mode: "template", path: "a" }, { mode: "document", path: "a" }));
		assert.ok(!samePlace(BLANK, { mode: "document", path: "a" }));
	});

	it("gives the place of a mode button: the same template in the other mode", () => {
		const paths = ["a", "b"];

		assert.deepEqual(placeOfMode("document", { mode: "template", path: "b" }, paths), { mode: "document", path: "b" });
		assert.deepEqual(placeOfMode("template", { mode: "document", path: "b" }, paths), { mode: "template", path: "b" });
		assert.deepEqual(placeOfMode("document", BLANK, paths), BLANK);
	});

	it("gives the first template for the templates mode from the blank document, and none without templates", () => {
		assert.deepEqual(placeOfMode("template", BLANK, ["a", "b"]), { mode: "template", path: "a" });
		assert.equal(placeOfMode("template", BLANK, []), null);
	});
});

describe("byFolder", () => {
	it("groups the templates by folder, in the order they first appear", () => {
		const groups = byFolder([{ path: "a/a1" }, { path: "a/b/y" }, { path: "a/c" }, { path: "root" }]);
		assert.deepEqual(groups.map((g) => [g.folder, g.templates.map((t) => t.path)]),
			[["a", ["a/a1", "a/c"]], ["a/b", ["a/b/y"]], ["", ["root"]]]);
		assert.deepEqual(byFolder([]), []);
	});
});
