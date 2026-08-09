package io.github.ivanshemidko.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;

import java.io.IOException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static io.github.ivanshemidko.util.UrlUtils.extractFirstUrl;
import static io.github.ivanshemidko.util.UrlUtils.urlEncode;

public class TelegramClient {
    private final String botToken;
    private final String chatId;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelegramClient(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public long sendMessage(String text) throws IOException, InterruptedException {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("parse_mode", "HTML");

        String previewUrl = extractFirstUrl(text);
        if (previewUrl != null) {
            payload.put("link_preview_options", Map.of(
                    "url", previewUrl,
                    "prefer_small_media", true
            ));
        }

        String body = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Telegram API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        if (!json.get("ok").asBoolean()) {
            throw new IOException("Telegram API returned ok=false: " + response.body());
        }

        return json.get("result").get("message_id").asLong();  // ← было void, теперь возвращаем id
    }

    public void deleteMessage(long messageId) throws IOException, InterruptedException {
        String url = "https://api.telegram.org/bot" + botToken + "/deleteMessage";

        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "message_id", messageId
        );

        String body = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Telegram API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        if (!json.get("ok").asBoolean()) {
            throw new IOException("Telegram API returned ok=false: " + response.body());
        }
    }


    public JsonNode getStickerSet(String name) throws IOException, InterruptedException {
        String url = "https://api.telegram.org/bot" + botToken + "/getStickerSet?name=" + urlEncode(name);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Telegram API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        if (!json.get("ok").asBoolean()) {
            throw new IOException("Telegram API returned ok=false: " + response.body());
        }

        return json.get("result");
    }
}


