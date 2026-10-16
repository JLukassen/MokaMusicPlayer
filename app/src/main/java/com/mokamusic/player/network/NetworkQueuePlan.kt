package com.mokamusic.player.network

/** Keep the chosen track in the queue while deduplicating visible Navidrome results.
 * The Media3 player owns shuffle order and next/previous behavior.
 */
internal object NetworkQueuePlan {
    fun build(selected: NetworkSong, visible: List<NetworkSong>): List<NetworkSong> =
        (visible + selected).distinctBy(NetworkSong::id)
}
