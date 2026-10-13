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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class App {

    private static final long POLL_INTERVAL_MS = 60_000 * 30;

    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String telegramBotToken = requireEnv(dotenv, "TELEGRAM_BOT_TOKEN");
        String telegramChatId = requireEnv(dotenv, "TELEGRAM_CHANNEL_ID");
        String spotifyClientId = requireEnv(dotenv, "SPOTIFY_CLIENT_ID");
        String spotifyClientSecret = requireEnv(dotenv, "SPOTIFY_CLIENT_SECRET");
        String spotifyRedirectUri = dotenv.get("SPOTIFY_REDIRECT_URI", "http://127.0.0.1:8888/callback");
        String playlistId = requireEnv(dotenv, "SPOTIFY_PLAYLIST_ID");

        SpotifyAuth auth = new SpotifyAuth(spotifyClientId, spotifyClientSecret, spotifyRedirectUri, Path.of("spotify_token.json"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        SpotifyClient spotify = new SpotifyClient(auth, http);
        TelegramClient telegram = new TelegramClient(telegramBotToken, telegramChatId);
        StateManager stateManager = new StateManager();
        stateManager.loadState();

        while (true) {
            try {
                pollOnce(spotify, telegram, stateManager, playlistId);
            } catch (Exception e) {
                System.err.println("Poll cycle failed: " + e.getMessage());
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    private static void pollOnce(SpotifyClient spotify, TelegramClient telegram,
                                 StateManager stateManager, String playlistId) throws Exception {
        List<Track> tracks = spotify.getTracks(playlistId);

        Map<String, TrackWithCount> newState = groupByTrackId(tracks);

        StateManager.PlaylistDiff diff = stateManager.updateAndSaveState(newState);

        for (var entry : diff.added().entrySet()) {
            String trackId = entry.getKey();
            Track track = newState.get(trackId).track();

            for (int i = 0; i < entry.getValue(); i++) {
                long messageId = telegram.sendMessage(PostFormatter.format(track));
                stateManager.recordSentMessage(trackId, messageId);
            }
        }

        for (var entry : diff.removedMessageIds().entrySet()) {
            for (long messageId : entry.getValue()) {
                telegram.deleteMessage(messageId);
            }
        }
    }

    private static Map<String, TrackWithCount> groupByTrackId(List<Track> tracks) {
        Map<String, Track> trackById = new HashMap<>();
        Map<String, Integer> countById = new HashMap<>();

        for (Track track : tracks) {
            trackById.putIfAbsent(track.id(), track);
            countById.merge(track.id(), 1, Integer::sum);
        }

        Map<String, TrackWithCount> result = new HashMap<>();
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
