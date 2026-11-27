package dev.t1m3.qplayer.cache;

import dev.t1m3.qplayer.media.Playlist;
import dev.t1m3.qplayer.media.Song;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class MediaPlaylistCacheIndexTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void keepsProviderQualifiedPlaylistAndSongs() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        try {
            Path root = temporary.newFolder("playlist-index").toPath();
            AppDirs.setBase(root.toString());
            Playlist playlist = new Playlist();
            playlist.id = "fixture:playlist:daily";
            playlist.name = "Daily";
            Song song = new Song();
            song.id = "fixture:song:1";
            song.title = "One";
            song.name = "One";
            song.artist = "Artist";
            song.artists.add(new dev.t1m3.qplayer.media.MediaRef("fixture:artist:1", "Artist"));
            song.album = new dev.t1m3.qplayer.media.MediaRef("fixture:album:1", "Album");
            playlist.songs.add(song);

            MediaPlaylistCacheIndex written = new MediaPlaylistCacheIndex();
            assertNull(written.getIfLoaded(playlist.id));
            written.upsert(playlist);
            // Mutate caller DTO to verify structural copy isolation.
            playlist.name = "Mutated";
            playlist.songs.get(0).title = "MutatedSong";
            playlist.songs.clear();
            written.save();

            MediaPlaylistCacheIndex restored = new MediaPlaylistCacheIndex();
            restored.load();

            Playlist value = restored.getIfLoaded(playlist.id);
            assertNotNull(value);
            assertEquals("Daily", value.name);
            assertEquals("fixture:song:1", value.songs.get(0).id);
            assertEquals("One", value.songs.get(0).title);
            assertEquals("Artist", value.songs.get(0).artists.get(0).name);
            assertEquals("Album", value.songs.get(0).album.name);

            value.songs.get(0).title = "CallerMutated";
            assertEquals("One", restored.get(playlist.id).songs.get(0).title);

            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                MediaPlaylistCacheIndex asyncRestored = new MediaPlaylistCacheIndex();
                asyncRestored.loadAsync(pool);
                Playlist asyncValue = asyncRestored.get(playlist.id);
                assertEquals("Daily", asyncValue.name);
                assertEquals("fixture:song:1", asyncValue.songs.get(0).id);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }
}
