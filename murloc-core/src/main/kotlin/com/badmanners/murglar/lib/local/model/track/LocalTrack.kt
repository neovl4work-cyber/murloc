package com.badmanners.murglar.lib.local.model.track

import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Bitrate.B_LOSSLESS
import com.badmanners.murglar.lib.core.model.track.source.Bitrate.B_UNKNOWN
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.QualityTier
import com.badmanners.murglar.lib.core.model.track.source.QualityTier.HIGH_QUALITY
import com.badmanners.murglar.lib.core.model.track.source.QualityTier.LOSSLESS
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.utils.MediaId
import com.badmanners.murglar.lib.core.utils.contract.Model
import com.badmanners.murglar.lib.local.fs.AudioFile


/**
 * Track of a local audio file.
 *
 * [id] is the canonical file path on Desktop or a SAF document URI on Android.
 */
@Model
class LocalTrack(
    id: String,
    title: String,
    artistNames: List<String>,
    albumName: String?,
    durationMs: Long,
    sources: List<Source>,
    mediaId: String
) : BaseTrack(
    id = id,
    title = title,
    artistIds = artistNames.map { it },
    artistNames = artistNames,
    albumId = albumName,
    albumName = albumName,
    durationMs = durationMs,
    sources = sources,
    mediaId = mediaId
)


/**
 * Converts [AudioFile]s, found by scanners, to plugin tracks.
 */
object LocalTrackFactory {

    fun create(audio: AudioFile, serviceId: String): LocalTrack {
        val extension = parseExtension(audio.extension)
        val bitrate = when (extension) {
            Extension.FLAC, Extension.WAV -> B_LOSSLESS
            else -> B_UNKNOWN
        }
        val quality = when (extension) {
            Extension.FLAC, Extension.WAV -> LOSSLESS
            else -> HIGH_QUALITY
        }

        val tag = extension.name
        val source = Source(
            id = "local_${audio.extension}",
            url = null,
            tag = tag,
            extension = extension,
            container = Container.PROGRESSIVE,
            bitrate = bitrate,
            qualityTier = quality,
            size = audio.size
        )

        return LocalTrack(
            id = audio.id,
            title = audio.title,
            artistNames = audio.artists,
            albumName = audio.album,
            durationMs = audio.durationMs,
            sources = listOf(source),
            mediaId = MediaId.build(serviceId, audio.id)
        )
    }

    private fun parseExtension(extension: String) = when (extension.lowercase()) {
        "mp3" -> Extension.MP3
        "flac" -> Extension.FLAC
        "ogg" -> Extension.OGG
        "m4a" -> Extension.M4A
        "wav" -> Extension.WAV
        "aac" -> Extension.AAC
        "opus" -> Extension.OPUS
        "wma" -> Extension.WMA
        else -> Extension.UNKNOWN
    }
}
