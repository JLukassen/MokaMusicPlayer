package com.mokamusic.player.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.mokamusic.player.data.MusicLibraryCache
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Browse-only, driver-safe Media3 tree for Android Auto. All data access is on an IO worker,
 * independent of the UI. The car only sees browsable folders and local playable media items;
 * EQ settings and library cleanup are never exposed in the driving interface.
 */
@OptIn(UnstableApi::class)
internal class CarLibraryCallback(context: Context) : MediaLibrarySession.Callback {
    private val appContext = context.applicationContext
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val cache = MusicLibraryCache(appContext)
    private var rows: List<MusicTrack> = emptyList()
    private var expiresAt = 0L

    private fun tracks(): List<MusicTrack> {
        if (System.currentTimeMillis() >= expiresAt) {
            rows = runBlocking { cache.load() }
            expiresAt = System.currentTimeMillis() + 15_000L
        }
        return rows
    }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title).setIsBrowsable(true).setIsPlayable(false).build())
        .build()

    private fun playable(track: MusicTrack): MediaItem = MediaItem.Builder()
        .setMediaId("track:${track.id}")
        .setUri(track.uri)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .setIsBrowsable(false).setIsPlayable(true).build())
        .build()

    private fun categoryItems(): List<MediaItem> = listOf(
        folder("category:tracks", "All songs"),
        folder("category:albums", "Albums"),
        folder("category:artists", "Artists"),
        folder("category:favorites", "Favorites")
    )

    private fun albumKey(track: MusicTrack) =
        (track.albumArtist?.ifBlank { null } ?: track.artist).lowercase(Locale.ROOT) +
            "\u0001" + track.album.lowercase(Locale.ROOT)

    private fun artistKey(track: MusicTrack) = track.artist.lowercase(Locale.ROOT)

    private fun listing(id: String, tracks: List<MusicTrack>): List<MediaItem> = when {
        id == "root" -> categoryItems()
        id == "category:tracks" -> tracks.sortedWith(compareBy({it.artist},{it.album},{it.trackNumber ?: 0},{it.title}))
            .map(::playable)
        id == "category:favorites" -> {
            val favoriteIds = appContext.getSharedPreferences("moka_favorites", Context.MODE_PRIVATE)
                .getStringSet("ids", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()
            tracks.filter { it.id in favoriteIds }.sortedBy { it.title }.map(::playable)
        }
        id == "category:albums" -> tracks.groupBy(::albumKey)
            .values.sortedBy { it.first().album.lowercase(Locale.ROOT) }
            .map { group -> folder("album:" + Uri.encode(albumKey(group.first())), group.first().album) }
        id == "category:artists" -> tracks.groupBy(::artistKey)
            .values.sortedBy { it.first().artist.lowercase(Locale.ROOT) }
            .map { group -> folder("artist:" + Uri.encode(artistKey(group.first())), group.first().artist) }
        id.startsWith("album:") -> {
            val key = Uri.decode(id.removePrefix("album:"))
            tracks.filter { albumKey(it) == key }
                .sortedWith(compareBy({it.discNumber ?: 0},{it.trackNumber ?: 0},{it.title}))
                .map(::playable)
        }
        id.startsWith("artist:") -> {
            val key = Uri.decode(id.removePrefix("artist:"))
            tracks.filter { artistKey(it) == key }.sortedBy { it.album }.map(::playable)
        }
        else -> emptyList()
    }

    private fun resolve(item: MediaItem, tracks: List<MusicTrack>): MediaItem {
        // Car browse items already have URIs, but their 'track:' IDs must still be
        // converted to numeric IDs so Moka's existing Now Playing and queue sync work.
        if (item.mediaId.startsWith("track:")) {
            val id = item.mediaId.removePrefix("track:").toLongOrNull() ?: return item
            val track = tracks.firstOrNull { it.id == id } ?: return item
            return playable(track).buildUpon().setMediaId(track.id.toString()).build()
        }
        return item
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Moka Music"), params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = executor.submit(Callable {
        val children = listing(parentId, tracks())
        val begin = (page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()
        val end = (begin.toLong() + pageSize).coerceAtMost(children.size.toLong()).toInt()
        LibraryResult.ofItemList(children.subList(begin,end),params)
    })

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = executor.submit(Callable {
        val all = tracks()
        val found = when {
            mediaId == "root" -> folder("root", "Moka Music")
            mediaId.startsWith("category:") || mediaId.startsWith("album:") || mediaId.startsWith("artist:") ->
                if (mediaId.startsWith("category:")) categoryItems().firstOrNull { it.mediaId == mediaId }
                else listing(mediaId,all).firstOrNull()?.let {
                    folder(mediaId,if (mediaId.startsWith("album:")) it.mediaMetadata.albumTitle?.toString() ?: "Album" else "Artist")
                }
            mediaId.startsWith("track:") -> all.firstOrNull { "track:${it.id}" == mediaId }?.let(::playable)
            else -> null
        }
        if (found == null) LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
        else LibraryResult.ofItem(found,null)
    })

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> = executor.submit(Callable {
        val all=tracks()
        mediaItems.map { resolve(it, all) }
    })

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = executor.submit(Callable {
        val all=tracks()
        MediaSession.MediaItemsWithStartPosition(mediaItems.map { resolve(it, all) }, startIndex, startPositionMs)
    })

    fun close() { executor.shutdownNow() }
}
