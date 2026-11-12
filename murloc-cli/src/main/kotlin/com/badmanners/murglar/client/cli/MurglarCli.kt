package com.badmanners.murglar.client.cli

import com.badmanners.murglar.client.cli.logger.StdLogger
import com.badmanners.murglar.client.cli.network.UrlConnectionNetworkMiddleware
import com.badmanners.murglar.client.cli.notification.StdNotificationMiddleware
import com.badmanners.murglar.client.cli.preference.JsonPreferenceMiddleware
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.Node.Companion.toTrack
import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters.asByteArray
import com.badmanners.murglar.lib.core.service.Murglar
import com.badmanners.murglar.lib.local.LocalMurglar
import com.badmanners.murglar.lib.local.model.event.AddTrackToPlaylist
import com.badmanners.murglar.lib.local.model.event.CreateEmptyPlaylist
import com.badmanners.murglar.lib.local.model.event.DeletePlaylist
import com.badmanners.murglar.lib.local.model.event.PickMusicFolder
import com.badmanners.murglar.lib.local.model.event.RemoveTrackFromPlaylist
import com.badmanners.murglar.lib.local.model.event.SaveDirectoryAsPlaylist
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import java.io.File
import java.nio.file.Path as FsPath


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
        check(murglar.murglarPreferences.any {
            it.id == LocalMurglar.PREF_MUSIC_DIR && it is com.badmanners.murglar.lib.core.preference.EditPreference
        }) { "Manual music folder input is missing from the settings!" }
        println("Manual music folder input: OK")

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

        // 4) Playlists: root menu items + .m3u content.
        checkPlaylists(murglar, nodeResolver)

        // 5) Custom node actions: save dir as playlist, create/add/delete playlist.
        checkCustomActions(murglar, nodeResolver)

        println()
        println("ALL CHECKS PASSED")

        // The embedded stream server runs non-daemon threads - force JVM shutdown for CLI usage.
        System.exit(0)
    }

    private suspend fun checkCustomActions(murglar: LocalMurglar, nodeResolver: com.badmanners.murglar.lib.core.node.NodeResolver) {
        val myMusic = nodeResolver.getRootNodes(true).first { it.nodePath.last == "myMusic" }
        val rootContent = nodeResolver.getNodeContent(myMusic.nodePath, page = null)
        val playlistsRoot = nodeResolver.getRootNodes(true).first { it.nodePath.last == "playlists" }

        // 0) 'Pick music folder' on the My Music root - in headless CLI it no-ops via log.w,
        //    the check is only that the action exists and handleEvent doesn't throw.
        val rootActions = nodeResolver.getNodeCustomActions(myMusic)
        val pickAction = rootActions.firstOrNull { it.action is PickMusicFolder }
            ?: error("No pick-music-folder action on the myMusic root node!")
        nodeResolver.handleEvent(pickAction.action, myMusic, null)
        println("Pick music folder action: OK (headless no-op)")

        // a) 'Save as playlist' + 'Create playlist' on a directory node.
        val dirNode = rootContent.first {
            it.nodePath.last.startsWith("dir-") && it.nodeName == "Artist One - Album Name"
        }
        val dirActions = nodeResolver.getNodeCustomActions(dirNode)
        val saveDirAction = dirActions.firstOrNull { it.action is SaveDirectoryAsPlaylist }
            ?: error("No save-dir action on the directory node!")
        check(dirActions.any { it.action is CreateEmptyPlaylist }) { "No create-playlist action on the directory node!" }
        nodeResolver.handleEvent(saveDirAction.action, dirNode, null)
        val savedName = "Artist One - Album Name.m3u"
        check(murglar.playlistLibrary.exists(savedName)) { "'$savedName' was not created!" }
        val savedPlaylist = murglar.listPlaylists().firstOrNull { it.title == "Artist One - Album Name" }
            ?: error("Saved playlist not in the library!")
        check(murglar.playlistLibrary.playlistTracks(savedPlaylist.id).size == 1) { "Saved playlist must contain 1 track!" }
        println("Save dir as playlist: OK ('$savedName', 1 track)")

        // b) 'Create playlist' on the 'Playlists' root node.
        val playlistsRootActions = nodeResolver.getNodeCustomActions(playlistsRoot)
        val createAction = playlistsRootActions.firstOrNull { it.action is CreateEmptyPlaylist }
            ?: error("No create-playlist action on the playlists root node!")
        nodeResolver.handleEvent(createAction.action, playlistsRoot, null)
        val createdName = murglar.listPlaylists()
            .map { FsPath.of(it.id).fileName.toString() }
            .firstOrNull { it.startsWith("New playlist (") || it.startsWith("Новый плейлист (") }
            ?: error("No created playlist found in the library!")
        check(murglar.playlistLibrary.exists(createdName)) { "'$createdName' was not created!" }
        println("Create playlist from menu: OK ('$createdName')")

        // c) Dynamic 'Add to <playlist>' on a track node - appends without duplicates.
        val trackNode = rootContent.first { it.nodeType == NodeType.TRACK }
        val trackActions = nodeResolver.getNodeCustomActions(trackNode)
        val addActions = trackActions.mapNotNull { it.action as? AddTrackToPlaylist }
        check(addActions.size >= 2) { "Expected an add-action per playlist, got $trackActions!" }
        check(addActions.any { it.playlistFileName == createdName }) { "No add-action for the created playlist!" }
        val addAction = addActions.first { it.playlistFileName == createdName }
        val created = murglar.listPlaylists()
            .firstOrNull { FsPath.of(it.id).fileName.toString() == createdName }
            ?: error("Created playlist not in the library!")
        val createdNode = nodeResolver.getNodeContent(playlistsRoot.nodePath, page = null)
            .firstOrNull { runCatching { FsPath.of(it.nodeId).fileName?.toString() }.getOrNull() == createdName }
            ?: error("Created playlist node not found!")

        nodeResolver.handleEvent(addAction, trackNode, null)
        check(murglar.playlistLibrary.playlistTracks(created.id).size == 1) { "Track was not added!" }
        nodeResolver.handleEvent(addAction, trackNode, null)
        check(murglar.playlistLibrary.playlistTracks(created.id).size == 1) { "Duplicate track was added!" }
        println("Add track via dynamic menu: OK (no duplicates)")

        // d) 'Remove from playlist' on a track inside the playlist.
        val playlistTrackNode = nodeResolver.getNodeContent(createdNode.nodePath, page = null)
            .firstOrNull { it.nodeType == NodeType.TRACK }
            ?: error("No tracks inside the created playlist!")
        val removeActions = nodeResolver.getNodeCustomActions(playlistTrackNode)
            .mapNotNull { it.action as? RemoveTrackFromPlaylist }
        check(removeActions.size == 1 && removeActions.first().playlistFileName == createdName) {
            "Expected exactly one remove-action for the containing playlist, got $removeActions!"
        }
        nodeResolver.handleEvent(removeActions.first(), playlistTrackNode, null)
        check(murglar.playlistLibrary.playlistTracks(created.id).isEmpty()) { "Track was not removed!" }
        // The library re-reads the file on every call - the playlist node content is empty now.
        check(nodeResolver.getNodeContent(createdNode.nodePath, page = null).isEmpty()) { "Playlist content is stale!" }
        println("Remove track from playlist: OK")

        // e) 'Delete playlist' on a playlist node removes the file.
        val playlistNode = nodeResolver.getNodeContent(playlistsRoot.nodePath, page = null)
            .first { it.nodeName == created.title }
        val playlistActions = nodeResolver.getNodeCustomActions(playlistNode)
        val deleteAction = playlistActions.firstOrNull { it.action is DeletePlaylist }
            ?: error("No delete action on the playlist node!")
        nodeResolver.handleEvent(deleteAction.action, playlistNode, null)
        check(!murglar.playlistLibrary.exists(createdName)) { "Playlist file was not deleted!" }
        println("Delete playlist: OK")

        // f) Rename via the same code path as the 'Rename' EditPreference in the settings.
        val renamedTitle = "Renamed by CLI"
        murglar.renamePlaylist(savedName, "Artist One - Album Name", renamedTitle)
        check(murglar.playlistLibrary.exists("$renamedTitle.m3u")) { "Playlist was not renamed!" }
        check(!murglar.playlistLibrary.exists(savedName)) { "Old playlist file still exists after rename!" }
        // An occupied target name must be refused (the playlist keeps its name).
        murglar.renamePlaylist("$renamedTitle.m3u", renamedTitle, murglar.listPlaylists()
            .first { it.title != renamedTitle }.title)
        check(murglar.playlistLibrary.exists("$renamedTitle.m3u")) { "Rename to an occupied name must be refused!" }
        // Rename back, so the following checks and the repo state stay the same.
        murglar.renamePlaylist("$renamedTitle.m3u", renamedTitle, "Artist One - Album Name")
        check(murglar.playlistLibrary.exists(savedName)) { "Playlist was not renamed back!" }
        println("Rename playlist: OK (occupied name refused)")
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
