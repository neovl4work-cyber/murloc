package com.badmanners.murglar.lib.local.node

import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.NODE
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.Directory
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlDecode
import com.badmanners.murglar.lib.core.utils.MurglarLibUtils.urlEncode
import com.badmanners.murglar.lib.local.LocalMurglar
import com.badmanners.murglar.lib.local.localization.LocalMessages
import com.badmanners.murglar.lib.local.model.track.LocalTrack
import com.badmanners.murglar.lib.local.model.track.LocalTrackFactory


/**
 * Node resolver that mirrors the music folder structure:
 * `My Music` root -> subfolder nodes -> local file tracks.
 *
 * File paths are url-encoded in node path segments, because [Path] can't contain '/' separators.
 */
class LocalNodeResolver(
    murglar: LocalMurglar,
    messages: LocalMessages
) : BaseNodeResolver<LocalMurglar, LocalMessages>(murglar, messages) {

    override val configurations = listOf(
        Root(
            pattern = "myMusic",
            name = messages::myMusic,
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            isOwn = true,
            contentNodeType = NODE,
            nodeContentSupplier = ::getMusicContent
        ),
        Directory(
            pattern = "*/dir-<dir>",
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            contentNodeType = NODE,
            nodeSupplier = ::getDirectory,
            nodeContentSupplier = ::getDirectoryContent
        ),
        Track(
            pattern = "*/track-<trackId>",
            nodeSupplier = ::getTrack
        )
    )


    private fun getMusicContent(parentPath: Path, page: Int?, params: Map<String, String>) =
        getContent(null, parentPath)

    private fun getDirectoryContent(parentPath: Path, page: Int?, params: Map<String, String>) =
        getContent(params["dir"]!!.urlDecode(), parentPath)

    private fun getDirectory(parentPath: Path, params: Map<String, String>): Node {
        val dir = params["dir"]!!.urlDecode()
        val name = dir.substringAfterLast('/')
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


    private fun List<LocalTrack>.convertLocalTracks(parentPath: Path) = convert(::trackNodePath, parentPath)

    private fun trackNodePath(parentPath: Path, track: LocalTrack): Path =
        parentPath.child("track-${track.id.urlEncode()}")
}
