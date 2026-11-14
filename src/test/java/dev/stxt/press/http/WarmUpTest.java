package dev.stxt.press.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.stxt.press.Options;
import dev.stxt.press.templates.Templates;

/**
 * The warm up with errors that the engine does not give at will: an exception that no job expects, such as a failure of
 * the engine with one document, is a failure of that document, a warning with its stack trace, and the others are
 * converted all the same. The conversions of the engine itself are in {@code PressServerTest}.
 */
class WarmUpTest {

    /** A document that the conversion of the test fails with, as the engine might with a bug. */
    private static final String BOOM = "Document (dev.stxt.pdf):\n\tTitle: boom\n";
    private static final String FINE = "Document (dev.stxt.pdf):\n\tTitle: fine\n";

    /** Fails as no job expects with {@link #BOOM}, and converts anything else. */
    private static final WarmUp.Conversion CONVERSION = job -> {
        if (job.document().contains("boom")) {
            throw new IllegalStateException("A bug of the engine");
        }
    };

    @TempDir
    Path temp;

    private Templates templates;
    private Templates documents;

    @BeforeEach
    void folders() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("templates"));
        Files.writeString(folder.resolve("fine.stxt"), FINE);
        Files.writeString(folder.resolve("fine.json"), "{}");
        Files.writeString(folder.resolve("boom.stxt"), BOOM);
        Files.writeString(folder.resolve("boom.json"), "{}");
        // Without sample data: an expected error of it would be only information, but not an unexpected one.
        Files.writeString(folder.resolve("quiet.stxt"), BOOM);
        templates = new Templates(folder);

        Path docs = Files.createDirectories(temp.resolve("documents"));
        Files.writeString(docs.resolve("fine.stxt"), FINE);
        Files.writeString(docs.resolve("boom.stxt"), BOOM);
        documents = new Templates(docs, Templates.Kind.DOCUMENT);
    }

    @Test
    void anUnexpectedErrorIsAFailureOfItsDocumentAndTheOthersAreConverted() {
        WarmUp.Result result = WarmUp.run(CONVERSION, templates, documents, "IBM Plex Sans", Options.DEFAULT);

        assertEquals(2, result.converted(), result::toString);
        assertEquals(List.of("boom", "quiet", "boom"), result.failures().stream().map(WarmUp.Failure::path).toList());
        assertEquals(List.of(Templates.Kind.TEMPLATE, Templates.Kind.TEMPLATE, Templates.Kind.DOCUMENT),
                result.failures().stream().map(WarmUp.Failure::kind).toList());
        for (WarmUp.Failure failure : result.failures()) {
            assertInstanceOf(IllegalStateException.class, failure.unexpected(), failure::toString);
            assertTrue(failure.error().contains("A bug of the engine"), failure::toString);
            assertTrue(failure.warning(), failure::toString);
        }
        assertFalse(result.failures().get(1).withData(), "quiet has no sample data, and is a warning all the same");
    }

    @Test
    void anUnexpectedErrorOfTheWarmUpDocumentIsAFailureToo() {
        WarmUp.Result result = WarmUp.run(job -> {
            throw new NullPointerException("A bug of the engine");
        }, null, null, "IBM Plex Sans", Options.DEFAULT);

        assertEquals(0, result.converted(), result::toString);
        assertEquals(1, result.failures().size(), result::toString);
        WarmUp.Failure failure = result.failures().get(0);
        assertNull(failure.path());
        assertInstanceOf(NullPointerException.class, failure.unexpected());
        assertTrue(failure.warning());
    }

    /** An error that a job expects (a file that cannot be read) says it all in its message, without a stack trace. */
    @Test
    void anExpectedErrorHasNoStackTrace() throws IOException {
        Files.write(templates.folder().resolve("fine.stxt"), new byte[] {(byte) 0xFF, (byte) 0xFE});

        WarmUp.Result result = WarmUp.run(CONVERSION, templates, null, "IBM Plex Sans", Options.DEFAULT);

        WarmUp.Failure fine = result.failures().stream().filter(f -> f.path().equals("fine")).findFirst().orElseThrow();
        assertNull(fine.unexpected(), fine::toString);
        assertTrue(fine.error().startsWith("It cannot be read"), fine::toString);
        assertTrue(fine.warning(), "it has sample data");
    }
}
