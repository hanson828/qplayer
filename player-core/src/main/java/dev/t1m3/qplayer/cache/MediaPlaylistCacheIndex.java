package dev.t1m3.qplayer.cache;

import com.google.gson.Gson;
import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.media.MediaKind;
import dev.t1m3.qplayer.media.MediaRef;
import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Bounded, source-qualified playlist snapshots for offline reopening. */
public final class MediaPlaylistCacheIndex {
    private static final int SCHEMA = 1;
    private static final int MAX_ENTRIES = 100;
    private final Path file = AppDirs.indexFile("playlists-v2.json");
    private final Gson gson = new Gson();
    private final Map<String, Playlist> byId = Collections.synchronizedMap(
            new LinkedHashMap<String, Playlist>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Playlist> entry) {
                    return size() > MAX_ENTRIES;
                }
            });
    private volatile boolean dirty;
    private volatile boolean loaded;
    private volatile CompletableFuture<Void> loadFuture;

    /** Start loading on {@code executor}; callers block on {@link #ensureLoaded()} if needed. */
    public CompletableFuture<Void> loadAsync(Executor executor) {
        if (loaded) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> future = CompletableFuture.runAsync(this::load, executor);
        loadFuture = future;
        return future;
    }

    private void ensureLoaded() {
        if (loaded) return;
        CompletableFuture<Void> pending = loadFuture;
        if (pending != null) pending.join();
    }

    public void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            State state = gson.fromJson(StorageFiles.readUtf8(file), State.class);
            if (state == null || state.schemaVersion != SCHEMA || state.items == null) {
                throw new java.io.IOException("unsupported playlist metadata index");
            }
            synchronized (byId) {
                for (Playlist playlist : state.items) {
                    if (valid(playlist)) byId.put(playlist.id, playlist);
                }
            }
        } catch (Throwable error) {
            Logger.warn("MediaPlaylistCacheIndex load failed: {}", error.getMessage());
        } finally {
            loaded = true;
        }
    }

    public void upsert(Playlist playlist) {
        ensureLoaded();
        if (!valid(playlist)) return;
        Playlist copy = copyPlaylist(playlist);
        byId.put(copy.id, copy);
        dirty = true;
    }

    public Playlist get(String id) {
        ensureLoaded();
        Playlist value = byId.get(id);
        return value != null ? copyPlaylist(value) : null;
    }

    /** Non-blocking read for the render thread: returns null if the index has not finished loading. */
    public Playlist getIfLoaded(String id) {
        if (!loaded || id == null || id.isEmpty()) return null;
        Playlist value = byId.get(id);
        return value != null ? copyPlaylist(value) : null;
    }

    private static Playlist copyPlaylist(Playlist src) {
        Playlist copy = new Playlist();
        copy.id = src.id;
        copy.name = src.name;
        copy.description = src.description;
        copy.artworkUrl = src.artworkUrl;
        copy.coverUrl = src.coverUrl;
        copy.coverThumbPath = src.coverThumbPath;
        copy.sourceName = src.sourceName;
        if (src.owner != null) copy.owner = new MediaRef(src.owner.id, src.owner.name);
        copy.trackCount = src.trackCount;
        copy.playCount = src.playCount;
        copy.subscribed = src.subscribed;
        copy.owned = src.owned;
        copy.mutable = src.mutable;
        copy.deletable = src.deletable;
        if (src.songs != null && !src.songs.isEmpty()) {
            List<Song> copiedSongs = new ArrayList<>(src.songs.size());
            for (Song song : src.songs) {
                if (song == null) continue;
                copiedSongs.add(copySong(song));
            }
            copy.songs = copiedSongs;
        }
        return copy;
    }

    private static Song copySong(Song src) {
        Song copy = new Song();
        copy.id = src.id;
        copy.title = src.title;
        copy.name = src.name;
        if (src.artists != null && !src.artists.isEmpty()) {
            List<MediaRef> copiedArtists = new ArrayList<>(src.artists.size());
            for (MediaRef ref : src.artists) {
                if (ref != null) copiedArtists.add(new MediaRef(ref.id, ref.name));
            }
            copy.artists = copiedArtists;
        }
        if (src.album != null) copy.album = new MediaRef(src.album.id, src.album.name);
        copy.durationMs = src.durationMs;
        copy.artworkUrl = src.artworkUrl;
        copy.coverUrl = src.coverUrl;
        copy.coverThumbPath = src.coverThumbPath;
        copy.artist = src.artist;
        copy.artistMediaId = src.artistMediaId;
        copy.artistIdsCsv = src.artistIdsCsv;
        copy.artistNamesCsv = src.artistNamesCsv;
        copy.isrc = src.isrc;
        copy.playable = src.playable;
        copy.trial = src.trial;
        copy.restricted = src.restricted;
        copy.cachedOffline = src.cachedOffline;
        return copy;
    }

    public void save() {
        ensureLoaded();
        if (!dirty) return;
        try {
            State state = new State();
            synchronized (byId) { state.items = new ArrayList<>(byId.values()); }
            StorageFiles.writeUtf8Atomic(file, gson.toJson(state));
            dirty = false;
        } catch (Throwable error) {
            Logger.warn("MediaPlaylistCacheIndex save failed: {}", error.getMessage());
        }
    }

    private static boolean valid(Playlist playlist) {
        if (playlist == null || playlist.id == null || playlist.id.isEmpty()) return false;
        try { MediaId.parse(playlist.id).requireKind(MediaKind.PLAYLIST); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static final class State {
        int schemaVersion = SCHEMA;
        List<Playlist> items = new ArrayList<>();
    }
}
