package io.github.ivanshemidko;

import io.github.ivanshemidko.client.TelegramClient;
import io.github.ivanshemidko.spotify.*;
import io.github.ivanshemidko.state.StateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AppTest {
    @TempDir Path dir;
    Track a = new Track("a", "Song A", List.of("Artist"));
    Track b = new Track("b", "Song B", List.of("Artist"));
    List<Track> playlist = List.of();
    SpotifyClient spotify = new SpotifyClient(null, null) {
        @Override public List<Track> getTracks(String id) { return playlist; }
    };
    class Telegram extends TelegramClient {
        int sends;
        int deletes;
        boolean failSend;
        boolean failDelete;
        Telegram() { super("unused", "unused"); }
        @Override public long sendMessage(String text) throws IOException {
            if (failSend && text.contains("song a")) throw new IOException("Simulated send failure");
            return ++sends;
        }
        @Override public void deleteMessage(long id) throws IOException {
            if (failDelete) throw new IOException("Simulated delete failure");
            deletes++;
        }
    }
    StateManager state() {
        var state = new StateManager(dir.resolve("state.json"));
        state.loadState();
        return state;
    }
    @Test void sendsAndDeletesDuplicateCopiesExactlyOnceAcrossRestarts() throws Exception {
        var telegram = new Telegram();
        App.pollOnce(spotify, telegram, state(), "p");
        playlist = List.of(a, a, b);
        App.pollOnce(spotify, telegram, state(), "p");
        App.pollOnce(spotify, telegram, state(), "p");
        assertEquals(3, telegram.sends);
        playlist = List.of(a);
        App.pollOnce(spotify, telegram, state(), "p");
        App.pollOnce(spotify, telegram, state(), "p");
        assertEquals(2, telegram.deletes);
        playlist = List.of(a, a);
        App.pollOnce(spotify, telegram, state(), "p");
        assertEquals(4, telegram.sends);
    }
    @Test void retriesFailuresAndContinuesOtherTracks() throws Exception {
        var telegram = new Telegram();
        App.pollOnce(spotify, telegram, state(), "p");
        playlist = List.of(a, b);
        telegram.failSend = true;
        App.pollOnce(spotify, telegram, state(), "p");
        assertEquals(1, telegram.sends);
        telegram.failSend = false;
        App.pollOnce(spotify, telegram, state(), "p");
        assertEquals(2, telegram.sends);
        playlist = List.of();
        telegram.failDelete = true;
        App.pollOnce(spotify, telegram, state(), "p");
        assertEquals(0, telegram.deletes);
        telegram.failDelete = false;
        App.pollOnce(spotify, telegram, state(), "p");
        App.pollOnce(spotify, telegram, state(), "p");
        assertEquals(2, telegram.deletes);
    }
}
