package io.github.ivanshemidko.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.ivanshemidko.util.UrlUtils.urlEncode;

public class SpotifyAuth {

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPE = "playlist-read-private";
    private static final int TOKEN_EXPIRY_SAFETY_MARGIN_SECONDS = 60;

    private final String clientId;
    private final String clientSecret;
    private final URI redirectUri;
    private final Path tokenFile;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private String cachedAccessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public SpotifyAuth(String clientId, String clientSecret, String redirectUri, Path tokenFile) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = URI.create(redirectUri);
        this.tokenFile = tokenFile;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public String getAccessToken() throws IOException, InterruptedException {
        if (cachedAccessToken != null && Instant.now().isBefore(accessTokenExpiresAt)) {
            return cachedAccessToken;
        }

        String refreshToken = loadRefreshToken();
        if (refreshToken == null) {
            JsonNode tokenResponse = runBrowserAuthorization();
            applyTokenResponse(tokenResponse, tokenResponse.get("refresh_token").asText());
        } else {
            JsonNode tokenResponse = refreshAccessToken(refreshToken);
            applyTokenResponse(tokenResponse, refreshToken);
        }

        return cachedAccessToken;
    }

    // ---------- one-time authorization via browser ----------

    private JsonNode runBrowserAuthorization() throws IOException, InterruptedException {
        String state = generateRandomState();
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server = startCallbackServer(state, codeFuture);

        String authorizeUrl = buildAuthorizeUrl(state);
        System.out.println("Open the link and grant access to the app");
        System.out.println(authorizeUrl);
        tryOpenInBrowser(authorizeUrl);

        String code;
        try {
            code = codeFuture.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new IOException("Confirmation in the browser was not received", e);
        } finally {
            server.stop(2);
        }

        JsonNode tokenResponse = exchangeCodeForTokens(code);
        saveRefreshToken(tokenResponse.get("refresh_token").asText());
        return tokenResponse;
    }

    private HttpServer startCallbackServer(String expectedState, CompletableFuture<String> codeFuture) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(redirectUri.getPort()), 0);

        server.createContext(redirectUri.getPath(), exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String responseHtml = handleCallback(params, expectedState, codeFuture);

            byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
        return server;
    }

    private String handleCallback(Map<String, String> params, String expectedState, CompletableFuture<String> codeFuture) {
        String error = params.get("error");
        String state = params.get("state");
        String code = params.get("code");

        if (error != null) {
            codeFuture.completeExceptionally(new IOException("Spotify denied access: " + error));
            return "<html><body>Access is not granted. You can close the tab.</body></html>";
        }
        if (!expectedState.equals(state)) {
            codeFuture.completeExceptionally(new IOException("State did not coincide – it is possible to replace the request"));
            return "<html><body>Security check error. You can close the tab.</body></html>";
        }

        codeFuture.complete(code);
        return "<html><body>Done! You can close the tab and return to the application.</body></html>";
    }

    private String buildAuthorizeUrl(String state) {
        return AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri.toString())
                + "&scope=" + urlEncode(SCOPE)
                + "&state=" + urlEncode(state);
    }

    private void tryOpenInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (IOException ignored) {
            // the link has already been displayed in the console
        }
    }

    // ---------- token exchange ----------

    private JsonNode exchangeCodeForTokens(String code) throws IOException, InterruptedException {
        String body = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri.toString());
        return postForm(body);
    }

    private JsonNode refreshAccessToken(String refreshToken) throws IOException, InterruptedException {
        String body = "grant_type=refresh_token&refresh_token=" + urlEncode(refreshToken);
        return postForm(body);
    }

    private JsonNode postForm(String formBody) throws IOException, InterruptedException {
        String basicAuth = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basicAuth)
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Spotify token error: " + response.statusCode() + " " + response.body());
        }

        return mapper.readTree(response.body());
    }

    private void applyTokenResponse(JsonNode tokenResponse, String fallbackRefreshToken) throws IOException {
        cachedAccessToken = tokenResponse.get("access_token").asText();
        int expiresIn = tokenResponse.get("expires_in").asInt();
        accessTokenExpiresAt = Instant.now().plusSeconds(expiresIn - TOKEN_EXPIRY_SAFETY_MARGIN_SECONDS);

        JsonNode newRefreshToken = tokenResponse.get("refresh_token");
        if (newRefreshToken != null && !newRefreshToken.isNull()) {
            saveRefreshToken(newRefreshToken.asText());
        } else {
            saveRefreshToken(fallbackRefreshToken);
        }
    }


    // ---------- storing refresh_token ----------

    private String loadRefreshToken() throws IOException {
        if (!Files.exists(tokenFile)) {
            return null;
        }
        JsonNode node = mapper.readTree(Files.readString(tokenFile, StandardCharsets.UTF_8));
        return node.get("refresh_token").asText();
    }

    private void saveRefreshToken(String refreshToken) throws IOException {
        String json = mapper.createObjectNode().put("refresh_token", refreshToken).toString();
        Files.writeString(tokenFile, json, StandardCharsets.UTF_8);
        restrictFilePermissions();
    }

    private void restrictFilePermissions() {
        try {
            Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // at least we tried
        }
    }

    // ---------- utilities ----------

    private static String generateRandomState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }
}