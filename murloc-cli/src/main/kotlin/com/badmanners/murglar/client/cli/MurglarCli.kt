package com.badmanners.murglar.client.cli

import com.badmanners.murglar.client.cli.logger.StdLogger
import com.badmanners.murglar.client.cli.network.UrlConnectionNetworkMiddleware
import com.badmanners.murglar.client.cli.notification.StdNotificationMiddleware
import com.badmanners.murglar.client.cli.preference.JsonPreferenceMiddleware
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.Node.Companion.toTrack
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters.asByteArray
import com.badmanners.murglar.lib.core.service.Murglar
import com.badmanners.murglar.lib.local.LocalMurglar
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import java.io.File


suspend fun main() {
    MurglarCli.start()
}

/**
 * CLI test of the MURLOC plugin without a host application:
 * builds the node tree from a local folder, resolves sources and downloads first bytes.
 */
object MurglarCli {

    private val logger = StdLogger()
    private val notifications = StdNotificationMiddleware()
    private val preferences = JsonPreferenceMiddleware(File("preferences.json"), logger)
    private val network = UrlConnectionNetworkMiddleware(logger)

    suspend fun start() {
        val murglar = LocalMurglar("local", preferences, network, notifications, logger)
        murglar.onCreate()

        println("Service: '${murglar.name}' (id='${murglar.id}', login='${murglar.loginResolver.loginInfo}')")
        check(murglar.loginResolver.isLogged) { "Resolver must always be logged in!" }

        val nodeResolver = murglar.nodeResolver

        val rootNodes = nodeResolver.getRootNodes(onlyAvailable = true)
        println("Root nodes (${rootNodes.size}):")
        rootNodes.forEach { println("  '${it.nodeName}' (${it.nodePath})") }
        check(rootNodes.size == 2) { "Expected 2 root nodes (myMusic + playlists), got ${rootNodes.size}!" }

        // Walk the tree: folders -> tracks.
        val tracks = mutableListOf<LocalTrack>()
        val queue = ArrayDeque<Node>(rootNodes)
        var visitedDirs = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val parameters = nodeResolver.getNodeParameters(node.nodePath)
            if (!parameters.isDirectory) {
                tracks += node.toTrack<LocalTrack>()
                continue
            }

            visitedDirs++
            val content = nodeResolver.getNodeContent(node.nodePath, page = null)
            println("'${node.nodeName}' -> ${content.size} items:")
            content.forEach { child ->
                val childParameters = nodeResolver.getNodeParameters(child.nodePath)
                if (childParameters.isDirectory) {
                    println("  [dir]  '${child.nodeName}' (${child.nodePath})")
                    queue += child
                } else {
                    val track = child.toTrack<LocalTrack>()
                    println("  [track] ${track.tag} (${track.id})")
                    tracks += track
                }
            }
        }
        println("Visited $visitedDirs directories, found ${tracks.size} tracks")
        check(tracks.isNotEmpty()) { "No tracks found!" }

        // Tags of the first track.
        val track = tracks.first()
        val tags = murglar.getTags(track, null)
        println("Tags of '${track.tag}': $tags")
        check(track.title.isNotEmpty()) { "Track title is empty!" }

        // getNode roundtrip for the first track path.
        val restoredNode = nodeResolver.getNode(track.nodePath)
        println("getNode('${track.nodePath}') roundtrip: '${restoredNode.nodeName}'")
        check(restoredNode.nodeName == track.nodeName) { "getNode roundtrip failed!" }

        // getTracksByMediaIds roundtrip.
        val restoredTracks = murglar.getTracksByMediaIds(listOf(track.mediaId))
        check(restoredTracks.size == 1 && restoredTracks.first().id == track.id) { "getTracksByMediaIds failed!" }
        println("getTracksByMediaIds roundtrip: OK")

        // 1) Direct source url (file:// on Desktop).
        val directSource = murglar.resolveSourceForUrl(track, track.sources.first())
        println("Resolved direct source: ${directSource.url}")
        downloadFirstBytes(track, directSource)

        // 2) Embedded stream server url.
        preferences.setBoolean(LocalMurglar.PREF_STREAM_SERVER, true)
        val serverSource = murglar.resolveSourceForUrl(track, track.sources.first())
        println("Resolved stream server source: ${serverSource.url}")
        downloadFirstBytes(track, serverSource)
        preferences.setBoolean(LocalMurglar.PREF_STREAM_SERVER, false)

        // 3) Range request against the stream server (seek simulation).
        checkRangeRequest(serverSource.url!!)

        // 4) Playlists: 'My Music' must contain a 'Playlists' dir with .m3u playlists of tracks.
        checkPlaylists(murglar, nodeResolver)

        println()
        println("ALL CHECKS PASSED")

        // The embedded stream server runs non-daemon threads - force JVM shutdown for CLI usage.
        System.exit(0)
    }

    private suspend fun checkPlaylists(murglar: LocalMurglar, nodeResolver: com.badmanners.murglar.lib.core.node.NodeResolver) {
        // Playlists are a separate root menu item now.
        val playlistsRoot = nodeResolver.getRootNodes(true)
            .firstOrNull { it.nodePath.last == "playlists" }
            ?: error("No 'Playlists' root node!")
        println("Playlists root: '${playlistsRoot.nodeName}' (${playlistsRoot.nodePath})")

        val playlists = nodeResolver.getNodeContent(playlistsRoot.nodePath, page = null)
        check(playlists.isNotEmpty()) { "No .m3u playlists found in the playlists folder!" }
        playlists.forEach { println("  [playlist] '${it.nodeName}' (${it.nodeType}, ${it.nodePath})") }

        val playlist = playlists.first()
        val playlistContent = nodeResolver.getNodeContent(playlist.nodePath, page = null)
        println("'${playlist.nodeName}' -> ${playlistContent.size} tracks:")
        playlistContent.forEach { println("  [track] ${it.nodeName}") }
        check(playlistContent.isNotEmpty()) { "Playlist is empty!" }

        val playlistTrack = playlistContent.first().toTrack<LocalTrack>()
        val source = murglar.resolveSourceForUrl(playlistTrack, playlistTrack.sources.first())
        println("Resolved playlist track source: ${source.url}")
        downloadFirstBytes(playlistTrack, source)
    }

    private suspend fun downloadFirstBytes(track: BaseTrack, source: Source) {
        val url = checkNotNull(source.url) { "Resolved source has no url!" }
        val request = NetworkRequest.Builder().url(url).build()
        val bytes = network.execute(request, asByteArray()).result
        check(bytes.isNotEmpty()) { "No content downloaded from '$url'!" }
        println("Downloaded ${bytes.size} bytes from '$url'")
    }

    private suspend fun checkRangeRequest(url: String) {
        val request = NetworkRequest.Builder()
            .url(url)
            .addHeader("Range", "bytes=0-99")
            .build()
        val response = network.execute(request, asByteArray())
        check(response.statusCode == 206) { "Range request returned ${response.statusCode}, expected 206!" }
        val total = response.headers.firstOrNull { it.first.equals("Content-Range", ignoreCase = true) }
            ?.second?.substringAfterLast('/')?.toLongOrNull()
        check(total != null && total > 0) { "No valid Content-Range header!" }
        println("Range request: ${response.statusCode}, ${response.result.size} bytes of $total total")
    }
}
