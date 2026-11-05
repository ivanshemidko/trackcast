package io.github.ivanshemidko;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.ivanshemidko.client.TelegramClient;
import io.github.ivanshemidko.format.PostFormatter;
import io.github.ivanshemidko.spotify.SpotifyAuth;
import io.github.ivanshemidko.spotify.SpotifyClient;
import io.github.ivanshemidko.spotify.Track;
import io.github.ivanshemidko.spotify.TrackWithCount;
import io.github.ivanshemidko.state.StateManager;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class App {


    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String telegramBotToken = requireEnv(dotenv, "TELEGRAM_BOT_TOKEN");
        String telegramChatId = requireEnv(dotenv, "TELEGRAM_CHANNEL_ID");
        String spotifyClientId = requireEnv(dotenv, "SPOTIFY_CLIENT_ID");
        String spotifyClientSecret = requireEnv(dotenv, "SPOTIFY_CLIENT_SECRET");
        String spotifyRedirectUri = dotenv.get("SPOTIFY_REDIRECT_URI", "http://127.0.0.1:8888/callback");
        String playlistId = requireEnv(dotenv, "SPOTIFY_PLAYLIST_ID");
        long pollIntervalMs = Long.parseLong(dotenv.get("POLL_INTERVAL_SECONDS", "1800")) * 1000;
        if (pollIntervalMs < 1000) throw new IllegalArgumentException("POLL_INTERVAL_SECONDS must be positive");

        Path dataDir = Path.of(dotenv.get("DATA_DIR", "."));
        Files.createDirectories(dataDir);
        // Held for the process lifetime: two workers must not post for the same state.
        FileChannel lockChannel = FileChannel.open(dataDir.resolve("trackcast.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var processLock = lockChannel.tryLock();
        if (processLock == null) throw new IllegalStateException("Another Trackcast instance is running");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { processLock.release(); lockChannel.close(); } catch (java.io.IOException ignored) {}
        }));

        SpotifyAuth auth = new SpotifyAuth(spotifyClientId, spotifyClientSecret, spotifyRedirectUri, dataDir.resolve("spotify_token.json"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        SpotifyClient spotify = new SpotifyClient(auth, http, dataDir.resolve("spotify_playlist_cache.json"));
        TelegramClient telegram = new TelegramClient(telegramBotToken, telegramChatId);
        StateManager stateManager = new StateManager(dataDir.resolve("playlist_state.json"));
        stateManager.loadState();

        if (java.util.Arrays.asList(args).contains("--check")) {
            List<Track> tracks = spotify.getTracks(playlistId);
            System.out.println("Check OK: " + tracks.size() + " playlist entries; "
                    + groupByTrackId(tracks).size() + " unique tracks. No Telegram changes made.");
            return;
        }

        while (true) {
            try {
                pollOnce(spotify, telegram, stateManager, playlistId);
                System.out.println(java.time.Instant.now() + " Poll completed");
            } catch (UncheckedIOException e) {
                throw e;
            } catch (Exception e) {
                System.err.println("Poll cycle failed: " + e.getMessage());
            }
            Thread.sleep(pollIntervalMs);
        }
    }

    static void pollOnce(SpotifyClient spotify, TelegramClient telegram,
                                 StateManager stateManager, String playlistId) throws Exception {
        List<Track> tracks = spotify.getTracks(playlistId);

        Map<String, TrackWithCount> newState = groupByTrackId(tracks);

        StateManager.PlaylistDiff diff = stateManager.updateAndSaveState(newState);

        for (var entry : diff.added().entrySet()) {
            String trackId = entry.getKey();
            Track track = newState.get(trackId).track();

            for (int i = 0; i < entry.getValue(); i++) {
                try {
                    long messageId = telegram.sendMessage(PostFormatter.format(track));
                    stateManager.recordSentMessage(trackId, track, messageId);
                } catch (java.io.IOException e) {
                    System.err.println("Send failed for " + trackId + ": " + e.getMessage());
                    break;
                }
            }
        }

        for (var entry : diff.removedMessageIds().entrySet()) {
            for (long messageId : entry.getValue()) {
                try {
                    telegram.deleteMessage(messageId);
                    stateManager.recordDeletedMessage(entry.getKey(), messageId);
                } catch (java.io.IOException e) {
                    System.err.println("Delete failed for message " + messageId + ": " + e.getMessage());
                }
            }
        }
    }

    private static Map<String, TrackWithCount> groupByTrackId(List<Track> tracks) {
        Map<String, Track> trackById = new LinkedHashMap<>();
        Map<String, Integer> countById = new LinkedHashMap<>();

        for (Track track : tracks) {
            trackById.putIfAbsent(track.id(), track);
            countById.merge(track.id(), 1, Integer::sum);
        }

        Map<String, TrackWithCount> result = new LinkedHashMap<>();
        for (String id : trackById.keySet()) {
            result.put(id, new TrackWithCount(trackById.get(id), countById.get(id)));
        }
        return result;
    }

    private static String requireEnv(Dotenv dotenv, String name) {
        String value = dotenv.get(name, System.getenv(name));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}