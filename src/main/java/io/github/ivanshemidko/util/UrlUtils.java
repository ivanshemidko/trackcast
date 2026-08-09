package io.github.ivanshemidko.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlUtils {
    private static final Pattern HREF_PATTERN = Pattern.compile("href=\"([^\"]+)\"");

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    public static String extractFirstUrl(String htmlText) {
        Matcher matcher = HREF_PATTERN.matcher(htmlText);
        return matcher.find() ? matcher.group(1) : null;
    }
}