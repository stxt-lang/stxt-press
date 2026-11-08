package dev.stxt.press.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import dev.stxt.pdf.convert.Converter;
import dev.stxt.pdf.resources.Resources;
import dev.stxt.press.Options;
import dev.stxt.press.Press;

/**
 * The templates folder of the project ({@code templates/}), the one of the editor and of the Docker image: every
 * template has its sample data, and converts with them to a PDF.
 */
class TemplateFolderTest {

    private static final Press PRESS = new Press(Converter.builder()
            .resources(new Resources(Path.of("..", "stxt-pdf", "resources"))).build());

    @TestFactory
    Stream<DynamicTest> everyTemplateConvertsWithItsSampleData() throws IOException {
        Templates templates = new Templates(Path.of("templates"));
        List<Templates.Entry> entries = templates.list();
        assertFalse(entries.isEmpty(), "no templates in templates/");
        return entries.stream().map(entry -> DynamicTest.dynamicTest(entry.path(), () -> {
            Template template = templates.get(entry.path());
            assertFalse(template.data() == null, entry.path() + " has no sample data");

            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            PRESS.pdf(template.job(Options.DEFAULT), pdf);

            assertEquals("%PDF", new String(pdf.toByteArray(), 0, 4, StandardCharsets.US_ASCII));
        }));
    }
}
