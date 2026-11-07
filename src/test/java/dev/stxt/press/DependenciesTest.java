package dev.stxt.press;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.stxt.InlineNode;
import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.runtime.NodeWriter;
import dev.stxt.pdf.model.StxtPdfException;
import dev.stxt.pdf.stxt.DocumentReader;
import dev.stxt.transform.FillJson;

/** The parser, the engine and the transforms are on the classpath and work together. */
class DependenciesTest {

    @Test
    void readsALayoutDocument() {
        InlineNode document = new DocumentReader().read("""
                Document (dev.stxt.pdf):
                \tLanguage: en
                \tTitle: Test
                \tPage:
                \t\tPaper: A4
                \tSection:
                \t\tParagraph: Hello.
                """);

        assertEquals("Document", document.getName());
    }

    @Test
    void anInvalidDocumentIsAnErrorWithItsLine() {
        StxtPdfException e = assertThrows(StxtPdfException.class, () -> new DocumentReader().read("""
                Document (dev.stxt.pdf):
                \tLanguage: en
                \tTitle: Test
                \tPage:
                \t\tPaper: A4
                \tSection:
                \t\tParagrahp: Hello.
                """));

        assertEquals(StxtPdfException.Kind.INVALID_DOCUMENT, e.kind());
        assertEquals(7, e.line());
    }

    @Test
    void fillsTheSlotsOfALayoutDocumentAndReadsTheResult() {
        List<Node> filled = new FillJson().fill(new Parser().parse("""
                Document (dev.stxt.pdf):
                \tLanguage: en
                \tTitle: <title>
                \tPage:
                \t\tPaper: A4
                \tSection:
                \t\tParagraph: [lines]<text>
                """), """
                {"title": "Test", "lines": [{"text": "One."}, {"text": "Two."}]}
                """);

        InlineNode document = new DocumentReader().read(NodeWriter.toSTXT(filled));

        InlineNode section = (InlineNode) document.getChild("Section");
        assertEquals(List.of("One.", "Two."),
                section.getChildren("Paragraph").stream().map(Node::getText).toList());
    }
}
