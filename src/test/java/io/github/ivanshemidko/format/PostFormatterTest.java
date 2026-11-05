package io.github.ivanshemidko.format;

import io.github.ivanshemidko.spotify.Track;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;

class PostFormatterTest {
    Document parse(String post) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader("<root>" + post + "</root>")));
    }
    List<String> labels(String post) throws Exception {
        var links = parse(post).getElementsByTagName("a");
        var result = new ArrayList<String>();
        for (int i = 0; i < links.getLength(); i++) result.add(links.item(i).getTextContent());
        return result;
    }
    @Test void preservesCornersAndWrapsExampleWithoutLosingWords() throws Exception {
        String post = PostFormatter.format(new Track("abc", "Super Rich Kids", List.of("Frank Ocean", "Earl Sweatshirt")));
        assertTrue(post.startsWith("⌜"));
        assertTrue(post.split("\n")[0].endsWith("⌝"));
        assertTrue(post.substring(post.lastIndexOf('\n') + 1).startsWith("⌞"));
        assertTrue(post.endsWith("⌟"));
        assertTrue(post.contains("       listen to\n"));
        assertFalse(post.contains("blockquote"));
        assertEquals("frank ocean, earl sweatshirt – super rich kids", String.join(" ", labels(post)));
        assertTrue(labels(post).size() >= 3);
    }
    @Test void fitsPlatinaAndSongOnFirstLine() throws Exception {
        String post = PostFormatter.format(new Track("abc", "На Грязном (Диана)", List.of("Платина")));
        assertEquals(List.of("платина – на грязном", "(диана)"), labels(post));
    }
    @Test void linksContainNeitherNewlinesNorIndentation() throws Exception {
        String post = PostFormatter.format(new Track("abc", "Super Rich Kids", List.of("Frank Ocean", "Earl Sweatshirt")));
        var links = parse(post).getElementsByTagName("a");
        for (int i = 0; i < links.getLength(); i++) {
            var link = links.item(i);
            String text = link.getTextContent();
            assertEquals(text.strip(), text);
            assertFalse(text.contains("\n"));
            assertEquals("https://open.spotify.com/track/abc", link.getAttributes().getNamedItem("href").getNodeValue());
            assertTrue(link.getPreviousSibling().getTextContent().endsWith("\n      "));
        }
        assertEquals(post.lines().count() - 3, links.getLength());
    }
    @Test void wrapsWideAndUnbrokenWordsEarlyAndLimitsHeight() throws Exception {
        for (String glyph : List.of("w", "щ", "界", "😀", "👨‍👩‍👧‍👦", "e\u0301")) {
            String post = PostFormatter.format(new Track("abc", glyph.repeat(160), List.of("a")));
            var lines = labels(post);
            assertTrue(lines.size() <= 6);
            assertTrue(lines.get(lines.size() - 1).endsWith("…"));
            assertTrue(post.length() < 1800);
            assertFalse(post.contains("\uFFFD"));
            for (String line : lines) {
                assertFalse(line.startsWith("\u200D"));
                assertFalse(line.endsWith("\u200D"));
                assertFalse(line.startsWith("\u0301"));
            }
            if (glyph.equals("w")) assertFalse(post.contains("w".repeat(11)));
            if (glyph.equals("щ")) assertFalse(post.contains("щ".repeat(11)));
        }
    }
    @Test void escapesHtmlAndNormalizesWhitespace() throws Exception {
        String post = PostFormatter.format(new Track("abc", "<song> & \"hi\"\n\t", List.of("A & B")));
        assertTrue(post.contains("&lt;song&gt;"));
        assertTrue(post.contains("&quot;hi&quot;"));
        assertEquals("a & b – <song> & \"hi\"", String.join(" ", labels(post)));
        assertFalse(post.contains("\t"));
    }
    @Test void lowercasesIndependentlyOfMachineLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertTrue(PostFormatter.format(new Track("a", "INDIE", List.of("I"))).contains("indie"));
        } finally { Locale.setDefault(previous); }
    }
}
