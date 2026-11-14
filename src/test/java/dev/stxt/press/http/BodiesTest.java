package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.stxt.press.templates.Templates;

/**
 * The messages of a body of {@code /render} ({@link RenderBody}) or of a save ({@link SaveBody}) that is not valid: each
 * a {@code 400} {@code BODY_NOT_VALID}, with the message that says what is wrong. The two bodies share how they are
 * read, so the messages that are the same in both are checked in both.
 */
class BodiesTest {

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertBodyError(String expected, HttpError error) {
        assertEquals(400, error.status);
        assertEquals(HttpError.BODY_NOT_VALID, error.code);
        assertEquals(expected, error.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "``|The body is not a JSON object",
            "not json|The body is not JSON: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')",
            "[1]|The body is not a JSON object",
            "{\"styles\": []}|The body has no document: \"document\" is required, as a string",
            "{\"document\": 1}|The body has no document: \"document\" is required, as a string",
            "{\"document\": \"x\", \"other\": 1}|Unknown field \"other\"",
            "{\"document\": \"x\"} {}|The body is not JSON: Trailing token (of type START_OBJECT) found after value (bound as `com.fasterxml.jackson.databind.JsonNode`): not allowed as per `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`",
            "{\"document\": \"x\", \"styles\": \"x\"}|\"styles\" is not an array of strings",
            "{\"document\": \"x\", \"options\": {\"color\": true}}|Unknown field \"options.color\"",
            "{\"document\": \"x\", \"options\": {\"pdfUa\": 3}}|\"options\" are not valid: PDF/UA is part 1 or 2, not 3"})
    void aBodyOfRenderThatIsNotValid(String body, String message) {
        assertBodyError(message, assertThrows(HttpError.class, () -> RenderBody.job(bytes(body), false)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "``|The body is not a JSON object",
            "not json|The body is not JSON: Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')",
            "[1]|The body is not a JSON object",
            "{\"styles\": []}|The body has no document: \"document\" is required, as a string",
            "{\"document\": \"x\", \"colour\": 1}|Unknown field \"colour\"",
            "{\"document\": \"x\", \"styles\": [{\"path\": \"a\", \"size\": 1}]}|Unknown field \"styles[].size\"",
            "{\"document\": \"x\", \"styles\": [1]}|\"styles\" is not an array of {\"path\", \"text\"}",
            "{\"document\": \"x\", \"data\": 1}|\"data\" is the text of the file, as a string"})
    void aBodyOfASaveThatIsNotValid(String body, String message) {
        assertBodyError(message, assertThrows(HttpError.class, () -> SaveBody.change(bytes(body), Templates.Kind.TEMPLATE)));
    }
}
