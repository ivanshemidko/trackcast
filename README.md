# Trackcast

Synchronizes a Spotify playlist with a Telegram channel: new tracks become linked
posts, and removing tracks removes their corresponding posts. Duplicate entries
are tracked separately.

Playlist snapshots are cached to avoid unnecessary downloads. Spotify rate-limit
cooldowns and synchronization state persist across restarts. The first run without
existing state establishes a baseline without publishing the entire playlist.

## Configuration

Create `.env` from [`.env.example`](.env.example).

| Variable | Purpose | Default |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | Bot token | Required |
| `TELEGRAM_CHANNEL_ID` | Target channel ID | Required |
| `SPOTIFY_CLIENT_ID` | Spotify application ID | Required |
| `SPOTIFY_CLIENT_SECRET` | Spotify application secret | Required |
| `SPOTIFY_PLAYLIST_ID` | Playlist to synchronize | Required |
| `SPOTIFY_REDIRECT_URI` | OAuth callback URI | `http://127.0.0.1:8888/callback` |
| `POLL_INTERVAL_SECONDS` | Playlist polling interval | `1800` |
| `TRACKCAST_UID` / `TRACKCAST_GID` | Container user and group IDs | `10001` / `10001` |

The bot needs permission to post and delete messages in the target channel.

## Deployment

Authorize Spotify locally using `mvn compile exec:java` (Java 17+ and Maven), then
stop the process and place the generated `spotify_token.json` in `data/`. When
migrating an existing instance, also transfer `playlist_state.json` to preserve
message associations. Set `TRACKCAST_UID` and `TRACKCAST_GID` to the owner of the
writable `data/` directory.

```sh
docker compose up -d --build
```

The `data/` bind mount stores authentication, playlist state, cache, and cooldowns.
Preserve it across deployments.

## Development

`mvn clean verify` builds the application and runs the test suite.
