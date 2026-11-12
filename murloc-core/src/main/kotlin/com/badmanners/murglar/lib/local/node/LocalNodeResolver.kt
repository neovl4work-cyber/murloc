package com.badmanners.murglar.lib.local.node

import com.badmanners.murglar.lib.core.model.event.NamedAction
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.NodeType.PLAYLIST
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.EventConfig
import com.badmanners.murglar.lib.core.node.GenericConfiguration
import com.badmanners.murglar.lib.core.node.MappedEntity
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.core.node.UnmappedEntity
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlDecode
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlEncode
import com.badmanners.murglar.lib.local.LocalMurglar
import com.badmanners.murglar.lib.local.fs.fileNameOf
import com.badmanners.murglar.lib.local.localization.LocalMessages
import com.badmanners.murglar.lib.local.model.event.AddTrackToPlaylist
import com.badmanners.murglar.lib.local.model.event.CreateEmptyPlaylist
import com.badmanners.murglar.lib.local.model.event.DeletePlaylist
import com.badmanners.murglar.lib.local.model.event.OpenPlaylistsFolder
import com.badmanners.murglar.lib.local.model.event.PickMusicFolder
import com.badmanners.murglar.lib.local.model.event.RemoveTrackFromPlaylist
import com.badmanners.murglar.lib.local.model.event.SaveDirectoryAsPlaylist
import com.badmanners.murglar.lib.local.model.event.UseDirectoryAsMusicRoot
import com.badmanners.murglar.lib.local.model.playlist.LocalPlaylist
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import com.badmanners.murglar.lib.local.model.track.LocalTrackFactory
import java.io.File


/**
 * Node resolver that mirrors the music folder structure:
 * `My Music` root -> (music roots, if several) -> subfolder nodes -> local file tracks,
 * plus a separate `Playlists` root with `.m3u`/`.m3u8` playlists.
 *
 * File paths are url-encoded in node path segments, because [Path] can't contain '/' separators.
 */
class LocalNodeResolver(
    murglar: LocalMurglar,
    messages: LocalMessages
) : BaseNodeResolver<LocalMurglar, LocalMessages>(murglar, messages) {

    override val configurations = buildList<GenericConfiguration> {
        add(
            Root(
                pattern = "myMusic",
                name = messages::myMusic,
                paging = NON_PAGEABLE,
                hasSubdirectories = true,
                isOwn = true,
                contentNodeType = NODE,
                nodeContentSupplier = ::getMusicContent
            )
        )
        add(
            Root(
                pattern = "playlists",
                name = messages::playlistRoot,
                paging = NON_PAGEABLE,
                hasSubdirectories = false,
                isOwn = true,
                contentNodeType = PLAYLIST,
                nodeContentSupplier = ::getPlaylistFiles
            )
        )
        add(
            MappedEntity(
                pattern = "*/playlist-<playlistId>",
                paging = NON_PAGEABLE,
                hasSubdirectories = false,
                type = PLAYLIST,
                contentNodeType = TRACK,
                events = listOf(
                    EventConfig(DeletePlaylist::class) { event, _ ->
                        murglar.deletePlaylist(event.playlistId)
                    }
                ),
                nodeSupplier = ::getPlaylist,
                nodeContentSupplier = ::getPlaylistTracks
            )
        )
        add(
            Directory(
                pattern = "*/dir-<dir>",
                paging = NON_PAGEABLE,
                hasSubdirectories = true,
                contentNodeType = NODE,
                nodeSupplier = ::getDirectory,
                nodeContentSupplier = ::getDirectoryContent
            )
        )
        add(
            Track(
                pattern = "*/track-<trackId>",
                events = listOf(
                    EventConfig(AddTrackToPlaylist::class) { event, _ ->
                        murglar.addTrackToPlaylist(event.trackId, event.playlistFileName)
                    },
                    EventConfig(RemoveTrackFromPlaylist::class) { event, _ ->
                        murglar.removeTrackFromPlaylist(event.trackId, event.playlistFileName)
                    }
                ),
                nodeSupplier = ::getTrack
            )
        )
        // Directory nodes have NODE type and their Directory configuration has no `events`,
        // so custom actions for them are declared through UnmappedEntity(type = NODE).
        add(
            UnmappedEntity(
                type = NODE,
                events = listOf(
                    EventConfig(SaveDirectoryAsPlaylist::class) { event, _ ->
                        murglar.saveDirectoryAsPlaylist(event.dir)
                    },
                    EventConfig(UseDirectoryAsMusicRoot::class) { event, _ ->
                        murglar.useDirectoryAsMusicRoot(event.dir)
                    },
                    EventConfig(CreateEmptyPlaylist::class) { _, _ ->
                        murglar.createPlaylist()
                    },
                    EventConfig(PickMusicFolder::class) { _, _ ->
                        murglar.pickMusicDir()
                    },
                    EventConfig(OpenPlaylistsFolder::class) { _, _ ->
                        murglar.openPlaylistsDir()
                    }
                )
            )
        )
    }

    /**
     * Custom node menu actions.
     *
     * The host shows them from the node menu and calls [handleEvent] with the action
     * from [NamedAction.action] when the user clicks one.
     */
    override fun getNodeCustomActions(node: Node): List<NamedAction> {
        val lastSegment = node.nodePath.last
        return when {
            node.nodeType == PLAYLIST ->
                listOf(NamedAction(messages.deletePlaylist, DeletePlaylist(playlistFileName(node))))

            node.nodeType == TRACK -> trackActions(node)

            node.nodePath.size == 2 && lastSegment == PLAYLISTS_ROOT_ID ->
                buildList {
                    add(NamedAction(messages.createPlaylist, CreateEmptyPlaylist))
                    if (!murglar.isAndroidPlatform)
                        add(NamedAction(messages.openPlaylistsFolder, OpenPlaylistsFolder))
                }

            node.nodePath.size == 2 && lastSegment == MY_MUSIC_ROOT_ID ->
                if (murglar.isAndroidPlatform)
                    emptyList()
                else
                    listOf(NamedAction(messages.pickMusicFolder, PickMusicFolder))

            node.nodeType == NODE && lastSegment.startsWith("dir-") -> {
                val dir = lastSegment.removePrefix("dir-").urlDecode()
                buildList {
                    add(NamedAction(messages.saveDirAsPlaylist, SaveDirectoryAsPlaylist(dir)))
                    add(NamedAction(messages.useAsMusicFolder, UseDirectoryAsMusicRoot(dir)))
                    add(NamedAction(messages.createPlaylist, CreateEmptyPlaylist))
                }
            }

            else -> emptyList()
        }
    }

    /**
     * Actions of a track node: one 'Add to <playlist>' action per existing playlist
     * (nothing is shown if there are no playlists), plus 'Remove from playlist' for a track
     * that is opened from inside a playlist.
     */
    private fun trackActions(node: Node): List<NamedAction> {
        val actions = mutableListOf<NamedAction>()

        containingPlaylistFileName(node)?.let { playlistFileName ->
            actions += NamedAction(
                messages.removeFromPlaylist,
                RemoveTrackFromPlaylist(node.nodeId, playlistFileName)
            )
        }

        murglar.listPlaylists().forEach { playlist ->
            val fileName = fileNameOf(playlist.id)
            actions += NamedAction(
                messages.addToPlaylist(playlist.title),
                AddTrackToPlaylist(node.nodeId, fileName)
            )
        }

        return actions
    }

    /**
     * File name of the playlist containing this track node (derived from the parent
     * path segment `playlist-<id>`), or null if the track is not inside a playlist.
     */
    private fun containingPlaylistFileName(node: Node): String? {
        val path = node.nodePath
        if (path.size < 3)
            return null

        val parentSegment = path[path.size - 2]
        if (!parentSegment.startsWith("playlist-"))
            return null

        val playlistId = parentSegment.removePrefix("playlist-").urlDecode()
        return fileNameOf(playlistId)
    }

    private fun playlistFileName(node: Node): String =
        fileNameOf(node.nodeId)


    private fun getMusicContent(parentPath: Path, page: Int?, params: Map<String, String>): List<Node> =
        if (murglar.scanner.hasMultipleRoots) {
            murglar.scanner.listSubdirs(null).map { entry ->
                val name = rootDisplayName(entry.relPath) ?: entry.name
                subdirectoryNode("dir-${entry.relPath.urlEncode()}", name, parentPath)
            }
        } else {
            getContent(null, parentPath)
        }

    private fun getDirectoryContent(parentPath: Path, page: Int?, params: Map<String, String>) =
        getContent(params["dir"]!!.urlDecode(), parentPath)

    private fun getDirectory(parentPath: Path, params: Map<String, String>): Node {
        val dir = params["dir"]!!.urlDecode()
        val name = rootDisplayName(dir) ?: dir.substringAfterLast('/', dir)
        return subdirectoryNode("dir-${dir.urlEncode()}", name, parentPath)
    }

    private fun getTrack(parentPath: Path, params: Map<String, String>): Node {
        val trackId = params["trackId"]!!.urlDecode()
        val track = murglar.scanner.audioFile(trackId)
            ?.let { LocalTrackFactory.create(it, LocalMurglar.SERVICE_ID) }
            ?: error("Track not found: '$trackId'!")
        return track.convertTrack(parentPath)
    }

    private fun getContent(dir: String?, parentPath: Path): List<Node> {
        val subdirs = murglar.scanner.listSubdirs(dir)
            .map { subdirectoryNode("dir-${it.relPath.urlEncode()}", it.name, parentPath) }

        val tracks = murglar.scanner.listAudio(dir)
            .map { LocalTrackFactory.create(it, LocalMurglar.SERVICE_ID) }
            .convertLocalTracks(parentPath)

        return subdirs + tracks
    }


    private fun getPlaylistFiles(parentPath: Path, page: Int?, params: Map<String, String>) =
        murglar.listPlaylists().convertLocalPlaylists(parentPath)

    private fun getPlaylist(parentPath: Path, params: Map<String, String>): Node {
        val playlistId = params["playlistId"]!!.urlDecode()
        val playlist = murglar.listPlaylists()
            .filterIsInstance<LocalPlaylist>()
            .firstOrNull { it.id == playlistId }
            ?: error("Playlist not found: '$playlistId'!")
        return playlist.convertLocalPlaylist(parentPath)
    }

    private fun getPlaylistTracks(parentPath: Path, page: Int?, params: Map<String, String>): List<Node> {
        val playlistId = params["playlistId"]!!.urlDecode()
        return murglar.playlistLibrary.playlistTracks(playlistId).convertLocalTracks(parentPath)
    }


    /**
     * Display name for a music root directory node (with parent suffix on name collisions),
     * or null if [dir] is not one of the configured music roots.
     */
    private fun rootDisplayName(dir: String): String? {
        if (!murglar.scanner.hasMultipleRoots)
            return null

        val path = runCatching { File(dir).absoluteFile.normalize() }.getOrNull() ?: return null
        val roots = murglar.scanner.listSubdirs(null).map { File(it.relPath).absoluteFile.normalize() }
        if (path !in roots)
            return null

        val name = path.name.ifEmpty { return null }
        val collision = roots.count { it.name == path.name } > 1
        if (!collision)
            return name

        val parent = path.parentFile
        val suffix = parent?.name?.takeIf { it.isNotEmpty() }
            ?: parent?.path?.replace('\\', '/')?.trimEnd('/')
            ?: "?"
        return "$name ($suffix)"
    }

    private fun List<LocalTrack>.convertLocalTracks(parentPath: Path) = convert(::trackNodePath, parentPath)

    private fun List<LocalPlaylist>.convertLocalPlaylists(parentPath: Path) = convert(::playlistNodePath, parentPath)

    private fun LocalPlaylist.convertLocalPlaylist(parentPath: Path) = convert(::playlistNodePath, parentPath)

    private fun trackNodePath(parentPath: Path, track: LocalTrack): Path =
        parentPath.child("track-${track.id.urlEncode()}")

    private fun playlistNodePath(parentPath: Path, playlist: LocalPlaylist): Path =
        parentPath.child("playlist-${playlist.id.urlEncode()}")


    private companion object {
        const val PLAYLISTS_ROOT_ID = "playlists"
        const val MY_MUSIC_ROOT_ID = "myMusic"
    }
}
