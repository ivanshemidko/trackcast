package io.github.ivanshemidko.format;

import io.github.ivanshemidko.spotify.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class PostFormatter {
    private static final int INDENT = 6;
    // Widths are estimated in spaces, with extra room for wide glyphs on mobile fonts.
    private static final int MAX_LINE_WIDTH = 40;
    private static final int MAX_LINES = 6;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final String HEADER = " ".repeat(INDENT + 1) + "listen to";

    public static String format(Track track) {
        String label = (String.join(", ", track.artists()) + " – " + track.name())
                .replaceAll("[\\p{Z}\\s]+", " ").strip().toLowerCase(Locale.ROOT);
        List<String> lines = wrap(label);
        int frameWidth = Math.max(width(HEADER), INDENT + lines.stream().mapToInt(PostFormatter::width).max().orElse(0)) + 2;
        String gap = " ".repeat(frameWidth);
        StringBuilder post = new StringBuilder("⌜" + gap + "⌝\n" + HEADER);
        String url = escapeHtml(track.spotifyUrl());
        for (String line : lines) {
            // Neither indentation nor line breaks belong to the link's underlined text.
            post.append('\n').append(" ".repeat(INDENT))
                    .append("<a href=\"").append(url).append("\">")
                    .append(escapeHtml(line)).append("</a>");
        }
        return post.append("\n⌞").append(gap).append("⌟").toString();
    }

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && lineWidth + 1 + width(word) > MAX_LINE_WIDTH) {
                lines.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
            }
            if (!line.isEmpty()) {
                line.append(' ');
                lineWidth++;
            }
            var glyphs = GRAPHEME.matcher(word);
            while (glyphs.find()) {
                String glyph = glyphs.group();
                int glyphWidth = glyphWidth(glyph);
                if (lineWidth + glyphWidth > MAX_LINE_WIDTH && !line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                line.append(glyph);
                lineWidth += glyphWidth;
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.size() > MAX_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_LINES));
            StringBuilder last = new StringBuilder();
            int lastWidth = 0;
            var glyphs = GRAPHEME.matcher(lines.get(MAX_LINES - 1));
            while (glyphs.find() && lastWidth + glyphWidth(glyphs.group()) <= MAX_LINE_WIDTH - glyphWidth("…")) {
                last.append(glyphs.group());
                lastWidth += glyphWidth(glyphs.group());
            }
            lines.set(MAX_LINES - 1, last.toString().stripTrailing() + "…");
        }
        return lines;
    }

    private static int width(String text) {
        var glyphs = GRAPHEME.matcher(text);
        int width = 0;
        while (glyphs.find()) width += glyphWidth(glyphs.group());
        return width;
    }

    private static int glyphWidth(String glyph) {
        return glyph.codePoints().map(cp -> {
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK || type == Character.FORMAT) return 0;
            if (cp == ' ' || "il.,'!:;|".indexOf(cp) >= 0) return 1;
            if (cp == '–') return 2;
            if (cp >= 0x1100 || "mw@%жщшм".indexOf(cp) >= 0) return 4;
            if ("дфцюы&".indexOf(cp) >= 0) return 3;
            return 2;
        }).max().orElse(0);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
