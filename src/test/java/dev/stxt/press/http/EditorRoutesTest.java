package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import dev.stxt.press.templates.TemplateException;

/**
 * The routes of the editor on a server of the test ({@link ServerFixture}): the index and the pages, {@code /schema} and
 * {@code /fonts}, the lists and the texts of the templates and the documents under {@code /files/}, their save and their
 * creation.
 */
class EditorRoutesTest extends ServerFixture {

    @Test
    void theDocumentsAreListedWithTheirFiles() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode list = json(get("/files/documents")).get("documents");
        JsonNode letter = json(get("/files/documents/letters/letter"));

        assertEquals("[{\"path\":\"hello\",\"styles\":false},{\"path\":\"letters/letter\",\"styles\":true}]",
                list.toString());
        assertEquals(DOCUMENT, letter.get("document").asText());
        assertEquals(List.of(".styles.stxt", "letters/letter.styles.stxt"),
                letter.get("styles").findValuesAsText("path"));
        assertFalse(letter.has("data"));
    }

    @Test
    void aServerWithoutFoldersHasNoTemplatesOrDocuments() throws Exception {
        server = PressServer.start(SHARED, null, null, ServerConfig.DEFAULT.withAddress("127.0.0.1", 0));

        assertEquals("{\"templates\":[],\"writable\":false}", text(get("/files/templates")));
        assertEquals("{\"documents\":[],\"writable\":false}", text(get("/files/documents")));
        error(get("/print/hello"), 404, "TEMPLATE_NOT_FOUND");
        error(get("/documents/hello"), 404, "DOCUMENT_NOT_FOUND");
        error(get("/files/documents/hello"), 404, "DOCUMENT_NOT_FOUND");
    }

    @Test
    void theTemplatesAreListedWithTheirFiles() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode list = json(get("/files/templates")).get("templates");
        JsonNode invoice = json(get("/files/templates/invoices/invoice"));

        assertEquals("[{\"path\":\"hello\",\"styles\":false,\"data\":false},"
                + "{\"path\":\"invoices/invoice\",\"styles\":false,\"data\":true}]", list.toString());
        assertEquals(WITH_SLOTS, invoice.get("document").asText());
        assertEquals(".styles.stxt", invoice.get("styles").get(0).get("path").asText());
        assertEquals(STYLES, invoice.get("styles").get(0).get("text").asText());
        assertEquals(SAMPLE, invoice.get("data").asText());
        assertTrue(json(get("/files/templates/hello")).get("data").isNull());
    }

    @Test
    void theSchemaAndTheFontsOfTheServer() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> schema = get("/schema");
        JsonNode fonts = json(get("/fonts")).get("families");

        assertEquals("text/stxt; charset=utf-8", schema.headers().firstValue("Content-Type").orElse(null));
        assertTrue(text(schema).contains("dev.stxt.pdf"));
        assertTrue(fonts.toString().contains("\"IBM Plex Sans\""), fonts::toString);
    }

    @Test
    void theFontsHaveTheirFacesAndTheirFiles() throws Exception {
        start(ServerConfig.DEFAULT);

        JsonNode faces = json(get("/fonts")).get("faces");
        JsonNode bold = null;
        for (JsonNode face : faces) {
            if (face.get("family").asText().equals("IBM Plex Sans") && face.get("weight").asInt() == 700
                    && face.get("style").asText().equals("normal")) {
                bold = face;
            }
        }
        assertTrue(bold != null, faces::toString);
        String id = bold.get("id").asText();
        HttpResponse<byte[]> file = get("/fonts/" + id);

        assertEquals(200, file.statusCode());
        assertEquals("font/ttf", file.headers().firstValue("Content-Type").orElse(null));
        assertEquals(id, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.body())));
        error(get("/fonts/" + "0".repeat(64)), 404, HttpError.NOT_FOUND);
        error(get("/fonts/IBM-Plex-Sans"), 404, HttpError.NOT_FOUND);
    }

    /**
     * The index and the editor are the build of {@code web/}, which Maven leaves in {@code target/classes/editor} before
     * the tests.
     */
    @Test
    void theIndexAndTheEditorAreServedWithoutToken() throws Exception {
        start(ServerConfig.DEFAULT.withToken("s3cret"));

        HttpResponse<byte[]> index = get("/");
        assertEquals(200, index.statusCode(), () -> text(index));
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").orElse(null));
        assertTrue(index.headers().firstValue("Content-Security-Policy").orElse("").startsWith("default-src 'self'"));
        assertTrue(text(index).contains("/assets/index.js"));

        // One page for the blank document and for every template and document, which exists or not: that is not told
        // without token.
        for (String url : List.of("/editor", "/editor/templates/hello", "/editor/templates/catalogs/catalog",
                "/editor/templates/missing/one", "/editor/documents/hello", "/editor/documents/reference/tables/basic")) {
            HttpResponse<byte[]> page = get(url);
            assertEquals(200, page.statusCode(), url);
            String policy = page.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(policy.startsWith("default-src 'self'"), policy);
            // The PDF is shown in a frame of a blob: URL; nothing else is framed.
            assertTrue(policy.contains("; frame-src blob:;"), policy);
            assertTrue(text(page).contains("/assets/editor.js"), url);
        }
        for (String url : List.of("/editor/templates/", "/editor/documents/", "/editor/templates/a//b",
                "/editor/templates/a/", "/editor/documents/a.b", "/editor/templates/..%2Fx", "/editor/documents/a%2Fb")) {
            error(get(url), 404, HttpError.NOT_FOUND);
        }
        // Any other path under /editor is not a page, and asks for the token as any route that does not exist.
        for (String url : List.of("/editor/", "/editor/hello", "/editor/editor.js", "/editor/template/hello")) {
            error(get(url), 401, HttpError.UNAUTHORIZED);
        }
        error(get("/editor/templates/hello?mode=document"), 400, HttpError.PARAMETER_NOT_VALID);
        error(get("/editor?format=pdf"), 400, HttpError.PARAMETER_NOT_VALID);

        HttpResponse<byte[]> script = get("/assets/editor.js");
        assertEquals(200, script.statusCode());
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").orElse(null));
        assertEquals(200, get("/assets/index.js").statusCode());
        assertEquals(200, get("/assets/editor.css").statusCode());
        error(get("/assets/missing.js"), 404, HttpError.NOT_FOUND);
        error(get("/assets/.hidden"), 404, HttpError.NOT_FOUND);
        error(get("/assets/..%2Fstxt%2Fdev.stxt.press.stxt"), 404, HttpError.NOT_FOUND);
        error(get("/assets/"), 404, HttpError.NOT_FOUND);
        // A page has one URL.
        error(get("/assets/index.html"), 404, HttpError.NOT_FOUND);
        error(get("/assets/editor.html"), 404, HttpError.NOT_FOUND);

        error(post("/", ""), 405, HttpError.METHOD_NOT_ALLOWED);
        error(post("/editor/templates/hello", ""), 405, HttpError.METHOD_NOT_ALLOWED);
        error(get("/schema"), 401, HttpError.UNAUTHORIZED);
        error(get("/files/templates/hello"), 401, HttpError.UNAUTHORIZED);
        error(get("/files/documents/hello"), 401, HttpError.UNAUTHORIZED);
    }

    /** A {@code PUT} with a body of {@code type} and, if not {@code null}, {@code If-Match}. */
    private HttpResponse<byte[]> put(String path, String body, String type, String match) throws Exception {
        HttpRequest.Builder builder = request(path).PUT(BodyPublishers.ofString(body)).header("Content-Type", type);
        if (match != null) {
            builder.header("If-Match", match);
        }
        return CLIENT.send(builder.build(), BodyHandlers.ofByteArray());
    }

    private static String etag(HttpResponse<byte[]> response) {
        return response.headers().firstValue("ETag").orElseThrow();
    }

    @Test
    void theTextsHaveTheirVersionAndSayWhetherTheyAreSaved() throws Exception {
        start(ServerConfig.DEFAULT);

        HttpResponse<byte[]> invoice = get("/files/templates/invoices/invoice");
        HttpResponse<byte[]> letter = get("/files/documents/letters/letter");

        assertTrue(etag(invoice).matches("\"[0-9a-f]{64}\""), etag(invoice));
        assertEquals("\"" + templates.get("invoices/invoice").version() + "\"", etag(invoice));
        assertFalse(json(invoice).get("writable").asBoolean());
        assertFalse(json(letter).get("writable").asBoolean());
        // Off by default: the method is not one of the route.
        HttpResponse<byte[]> refused = put("/files/templates/hello", "{\"document\": \"x\"}", "application/json", etag(invoice));
        error(refused, 405, HttpError.METHOD_NOT_ALLOWED);
        assertEquals("GET", refused.headers().firstValue("Allow").orElse(null));
        assertEquals(DOCUMENT, Files.readString(templates.folder().resolve("hello.stxt")));
    }

    @Test
    void aSaveWritesTheFilesOfItsVersion() throws Exception {
        start(ServerConfig.DEFAULT.withSave(true));
        HttpResponse<byte[]> before = get("/files/templates/invoices/invoice");
        assertTrue(json(before).get("writable").asBoolean());
        String edited = WITH_SLOTS.replace("Paper: A4", "Paper: A5");

        HttpResponse<byte[]> saved = put("/files/templates/invoices/invoice",
                JSON.writeValueAsString(Map.of("document", edited, "styles", List.of(Map.of("path", ".styles.stxt",
                        "text", STYLES)))), "application/json; charset=utf-8", etag(before));

        assertEquals(200, saved.statusCode(), () -> text(saved));
        assertEquals("[\"invoices/invoice.stxt\"]", json(saved).get("written").toString());
        assertEquals(edited, json(saved).get("document").asText());
        assertEquals(edited, Files.readString(templates.folder().resolve("invoices/invoice.stxt")));
        assertFalse(etag(saved).equals(etag(before)));
        assertEquals(etag(saved), etag(get("/files/templates/invoices/invoice")));

        // From the version before, someone else's save would be undone: nothing is written.
        JsonNode stale = error(put("/files/templates/invoices/invoice", JSON.writeValueAsString(Map.of("document", WITH_SLOTS)),
                "application/json", etag(before)), 409, TemplateException.FILES_CHANGED);
        assertTrue(stale.get("message").asText().contains("invoices/invoice"));
        assertEquals(edited, Files.readString(templates.folder().resolve("invoices/invoice.stxt")));
    }

    @Test
    void aSaveOfADocument() throws Exception {
        start(ServerConfig.DEFAULT.withSave(true));
        String version = etag(get("/files/documents/letters/letter"));

        HttpResponse<byte[]> saved = put("/files/documents/letters/letter", JSON.writeValueAsString(Map.of("document",
                DOCUMENT.replace("Hello.", "Saved."))), "application/json", "W/" + version);

        assertEquals(200, saved.statusCode(), () -> text(saved));
        assertTrue(Files.readString(documents.folder().resolve("letters/letter.stxt")).contains("Saved."));
        assertFalse(json(saved).has("data"));
        error(put("/files/documents/letters/letter", JSON.writeValueAsString(Map.of("document", DOCUMENT, "data", "{}")),
                "application/json", etag(saved)), 400, HttpError.BODY_NOT_VALID);
    }

    @Test
    void aSaveThatIsNotValidWritesNothing() throws Exception {
        start(ServerConfig.DEFAULT.withSave(true).withToken("s3cret"));
        String body = JSON.writeValueAsString(Map.of("document", "changed"));
        String version = "\"" + templates.get("hello").version() + "\"";

        error(put("/files/templates/hello", body, "application/json", version), 401, HttpError.UNAUTHORIZED);
        server.close();
        start(ServerConfig.DEFAULT.withSave(true));
        error(put("/files/templates/hello", body, "text/plain", version), 415, HttpError.CONTENT_TYPE_NOT_VALID);
        error(put("/files/templates/hello", body, "application/json", null), 428, HttpError.VERSION_REQUIRED);
        error(put("/files/templates/hello", "{\"styles\": []}", "application/json", version), 400, HttpError.BODY_NOT_VALID);
        error(put("/files/templates/hello", "{\"document\": \"x\", \"colour\": 1}", "application/json", version), 400,
                HttpError.BODY_NOT_VALID);
        error(put("/files/templates/hello", JSON.writeValueAsString(Map.of("document", "x", "styles",
                List.of(Map.of("path", "../outside.stxt", "text", "x")))), "application/json", version), 400,
                HttpError.BODY_NOT_VALID);
        error(put("/files/templates/missing", body, "application/json", version), 404, TemplateException.NOT_FOUND);
        assertEquals(DOCUMENT, Files.readString(templates.folder().resolve("hello.stxt")));
    }

    /** A {@code PUT} with {@code If-None-Match: *}, which creates. */
    private HttpResponse<byte[]> create(String path, String body) throws Exception {
        return CLIENT.send(request(path).PUT(BodyPublishers.ofString(body)).header("Content-Type", "application/json")
                .header("If-None-Match", "*").build(), BodyHandlers.ofByteArray());
    }

    @Test
    void aNewDocumentIsCreatedOnceAndThenPrinted() throws Exception {
        start(ServerConfig.DEFAULT.withSave(true));
        assertTrue(json(get("/files/documents")).get("writable").asBoolean());
        String body = JSON.writeValueAsString(Map.of("document", DOCUMENT.replace("Hello.", "New.")));

        HttpResponse<byte[]> created = create("/files/documents/letters/note", body);

        assertEquals(201, created.statusCode(), () -> text(created));
        assertEquals("[\"letters/note.stxt\"]", json(created).get("written").toString());
        assertEquals(etag(created), etag(get("/files/documents/letters/note")));
        // It takes the styles of its folder, as the others.
        assertTrue(json(created).get("styles").findValuesAsText("path").contains(".styles.stxt"));
        assertTrue(isPdf(get("/documents/letters/note").body()));

        // Once it exists, another creation is refused and writes nothing.
        String other = JSON.writeValueAsString(Map.of("document", DOCUMENT.replace("Hello.", "Other.")));
        error(create("/files/documents/letters/note", other), 412, TemplateException.DOCUMENT_EXISTS);
        assertTrue(Files.readString(documents.folder().resolve("letters/note.stxt")).contains("New."));
        error(create("/files/templates/hello", other), 412, TemplateException.EXISTS);
        assertEquals(DOCUMENT, Files.readString(templates.folder().resolve("hello.stxt")));
        // Its folder has to exist.
        error(create("/files/documents/missing/note", body), 404, TemplateException.DOCUMENT_NOT_FOUND);
        assertFalse(Files.exists(documents.folder().resolve("missing")));
    }

    @Test
    void withoutSaveNothingIsCreated() throws Exception {
        start(ServerConfig.DEFAULT);
        assertFalse(json(get("/files/templates")).get("writable").asBoolean());

        error(create("/files/templates/fresh", JSON.writeValueAsString(Map.of("document", DOCUMENT))), 405,
                HttpError.METHOD_NOT_ALLOWED);

        assertFalse(Files.exists(templates.folder().resolve("fresh.stxt")));
    }
}
