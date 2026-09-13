package io.github.ivanshemidko.format;

import io.github.ivanshemidko.spotify.Track;

import java.util.Arrays;
import static java.lang.Math.max;

public class PostFormatter {
    private static final int INDENT = 6;
    private static final int MAX_STRING_SIZE = 28;
    private static final double SPACE_TO_CHAR_COEFFICIENT = 2.3;
    private static final String FIRST_STRING = spaces(INDENT + 1) + "listen to";

    // premium emoji doesn't work: restriction of telegram api
    private static final String TOP_LEFT     = "<tg-emoji emoji-id=\"5418389384669988179\">⌜</tg-emoji>";
    private static final String TOP_RIGHT    = "<tg-emoji emoji-id=\"5418361304173806158\">⌝</tg-emoji>";
    private static final String BOTTOM_LEFT  = "<tg-emoji emoji-id=\"5415714247109806615\">⌞</tg-emoji>";
    private static final String BOTTOM_RIGHT = "<tg-emoji emoji-id=\"5418287559585332386\">⌟</tg-emoji>";

    public static String format(Track track) {
        String artists = String.join(", ", track.artists());
        String linkLabel = (artists + " – " + track.name()).toLowerCase();

        String alignedLinkLabel = truncate(linkLabel);
        String trackData = "<a href=\"" + track.spotifyUrl() + "\">" + escapeHtml(alignedLinkLabel) + "</a>";
        String body = FIRST_STRING + "\n" + spaces(INDENT) + trackData;

        int widthInSpaces = max(
                FIRST_STRING.length(),
                Arrays.stream(alignedLinkLabel.split("\n")).mapToInt(String::length).max().orElse(0)
        );
        widthInSpaces *= SPACE_TO_CHAR_COEFFICIENT;
        String top = TOP_LEFT + spaces(widthInSpaces) + TOP_RIGHT;
        String bottom = BOTTOM_LEFT + spaces(widthInSpaces) + BOTTOM_RIGHT;

        return top + "\n" + body + "\n" + bottom;
    }

    private static String truncate(String text) {
        String[] words = text.split("(?<=\\s)(?=\\S)");
        StringBuilder result = new StringBuilder();
        int currentLength = 0;

        for (String word: words) {
            if (!result.isEmpty() && currentLength + word.length() > MAX_STRING_SIZE) {
                result.append("\n").append(spaces(INDENT));
                currentLength = 0;
            }
            result.append(word);
            currentLength += word.length();
        }

        return result.toString();
    }
    
    private static String spaces(int count) {
        return " ".repeat(count);
    }


    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}