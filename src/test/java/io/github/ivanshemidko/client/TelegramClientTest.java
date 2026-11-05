package io.github.ivanshemidko.client;

import io.github.ivanshemidko.format.PostFormatter;
import io.github.ivanshemidko.spotify.Track;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramClientTest {
    @Test void onlyFirstUrlSuppliesSmallPreviewBelowMessage() {
        String post = PostFormatter.format(new Track("abc", "Super Rich Kids", List.of("Frank Ocean", "Earl Sweatshirt")));
        var payload = TelegramClient.messagePayload("channel", post);
        assertEquals("HTML", payload.get("parse_mode"));
        assertEquals(post, payload.get("text"));
        assertEquals(Map.of("url", "https://open.spotify.com/track/abc", "prefer_small_media", true,
                "show_above_text", false), payload.get("link_preview_options"));
        var distinct = TelegramClient.messagePayload("channel",
                "<a href=\"https://first.example\">one</a>\n<a href=\"https://second.example\">two</a>");
        assertEquals("https://first.example", ((Map<?, ?>) distinct.get("link_preview_options")).get("url"));
    }
}
