package com.badmanners.murglar.lib.local.node

import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.NodeType.PLAYLIST
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.GenericConfiguration
import com.badmanners.murglar.lib.core.node.MappedEntity
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlDecode
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlEncode
import com.badmanners.murglar.lib.local.LocalMurglar
import com.badmanners.murglar.lib.local.localization.LocalMessages
import com.badmanners.murglar.lib.local.model.playlist.LocalPlaylist
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import com.badmanners.murglar.lib.local.model.track.LocalTrackFactory
import java.nio.file.Path as FsPath


/**
 * Node resolver that mirrors the music folder structure:
 * `My Music` root -> (music roots, if several) -> subfolder nodes -> local file tracks,
 * plus a separate `Playlists` root with `.m3u`/`.m3u8` playlists (Desktop only).
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
        if (!murglar.isAndroidPlatform)
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
                nodeSupplier = ::getTrack
            )
        )
    }


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
     * or null if [dir] is not one of the music roots.
     */
    private fun rootDisplayName(dir: String): String? {
        if (!murglar.scanner.hasMultipleRoots)
            return null

        val path = runCatching { FsPath.of(dir).toAbsolutePath().normalize() }.getOrNull() ?: return null
        val roots = murglar.musicDirs().map { it.toAbsolutePath().normalize() }
        if (path !in roots)
            return null

        val name = path.fileName?.toString() ?: return null
        val collision = roots.count { it.fileName == path.fileName } > 1
        if (!collision)
            return name

        val parent = path.parent
        val suffix = parent?.fileName?.toString()
            ?: parent?.toString()?.replace('\\', '/')?.trimEnd('/')
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
}
