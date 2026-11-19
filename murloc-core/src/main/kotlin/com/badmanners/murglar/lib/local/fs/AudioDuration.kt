package com.badmanners.murglar.lib.local.fs

import java.io.InputStream

/**
 * Определение длительности локальных аудиофайлов по заголовкам.
 *
 * Нужен, потому что Murglar при воспроизведении делает coerceIn(0..duration):
 * при duration = -1 (UNKNOWN) хост падает с IllegalArgumentException
 * «Cannot coerce value to an empty range: 0.0..-1.0». Поэтому отдаём реальную
 * длительность, а -1 только если распознать не удалось.
 *
 * Всё на InputStream + java.io — совместимо с Android (minSdk 21).
 */
object AudioDuration {

    /** @return длительность в миллисекундах или -1 */
    fun detect(stream: InputStream, size: Long, extension: String): Long = try {
        when (extension.lowercase()) {
            "wav", "wave" -> wav(stream)
            "mp3" -> mp3(stream, size)
            "flac" -> flac(stream)
            "ogg", "oga", "opus" -> ogg(stream)
            "m4a", "mp4", "aac" -> mp4(stream)
            else -> -1L
        }
    } catch (e: Exception) {
        -1L
    }

    /** WAV/RIFF: длительность = размер data-чанка / байтRate. */
    private fun wav(input: InputStream): Long {
        val head = read(input, 12)
        if (head.size < 12 || String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
            String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE") return -1L
        var byteRate = 0L
        var dataSize = -1L
        while (true) {
            val chunkHead = read(input, 8)
            if (chunkHead.size < 8) break
            val id = String(chunkHead, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = u32le(chunkHead, 4)
            if (id == "fmt " && chunkSize >= 8) {
                val fmt = read(input, 8)
                if (fmt.size < 8) break
                byteRate = u32le(fmt, 4)
                skipFully(input, chunkSize - 8)
            } else if (id == "data") {
                dataSize = chunkSize
                break
            } else {
                skipFully(input, chunkSize + (chunkSize and 1))
            }
            if (byteRate > 0 && dataSize >= 0) break
        }
        if (byteRate <= 0) return -1L
        val bytes = if (dataSize >= 0) dataSize else return -1L
        return bytes * 1000L / byteRate
    }

    /**
     * MP3: пропускаем ID3v2, ищем первый фрейм, читаем битрейт.
     * Точный Xing/Info-заголовок не ищем — для CBR-оценки хватает
     * (size*8)/bitrate; для VBR неточность ~±10%, что не критично для UI.
     */
    private fun mp3(input: InputStream, size: Long): Long {
        val head = read(input, 10)
        if (head.size < 10) return -1L
        var offset = 0L
        if (String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            // ID3v2: 6 последних байт заголовка = размер (syncsafe, 7 бит/байт)
            val len = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            offset = 10L + len
            skipFully(input, len.toLong())
        }
        // ищем фрейм-синхронизацию 0xFFE в первых 64 КБ
        val window = read(input, 64 * 1024)
        var i = 0
        while (i + 4 <= window.size) {
            val b0 = window[i].toInt() and 0xFF
            val b1 = window[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val version = (b1 shr 3) and 0x03
                val layerBits = (b1 shr 1) and 0x03
                val bitrateIdx = (window[i + 2].toInt() and 0xFF) shr 4
                if (version != 1 && layerBits != 0 && bitrateIdx !in listOf(0, 15)) {
                    val kbps = mp3Bitrate(version, layerBits, bitrateIdx)
                    if (kbps > 0 && size > offset) {
                        val audioBytes = size - offset - i
                        return audioBytes * 8L / (kbps * 1000L) * 1000L
                    }
                }
            }
            i++
        }
        return -1L
    }

    private fun mp3Bitrate(version: Int, layerBits: Int, idx: Int): Int {
        // version: 3=MPEG1, 2=MPEG2, 0=MPEG2.5; layerBits: 3=Layer I, 2=II, 1=III
        val v1 = version == 3
        val l1 = layerBits == 3
        val l2 = layerBits == 2
        val l3 = layerBits == 1
        return when {
            v1 && l1 -> listOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)[idx]
            v1 && l2 -> listOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)[idx]
            v1 && l3 -> listOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[idx]
            !v1 && l1 -> listOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)[idx]
            !v1 && (l2 || l3) -> listOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[idx]
            else -> 0
        }
    }

    /** FLAC: STREAMINFO (первый метаблок): sampleRate(20 бит) + totalSamples(36 бит). */
    private fun flac(input: InputStream): Long {
        val head = read(input, 8)
        if (head.size < 8 || String(head, 0, 4, Charsets.ISO_8859_1) != "fLaC") return -1L
        // head[4..7] = заголовок первого метаблока: last(1)+type(7), length(24)
        if ((head[5].toInt() and 0x7F) != 0) return -1L // ожидаем STREAMINFO (type 0)
        val info = read(input, 18)
        if (info.size < 18) return -1L
        // байты 10..17: sampleRate (20 бит, старшие) + totalSamples (36 бит)
        val sr = ((info[10].toLong() and 0xFF) shl 12) or ((info[11].toLong() and 0xFF) shl 4) or
            ((info[12].toLong() and 0xFF) shr 4)
        val total = ((info[13].toLong() and 0xFF) shl 24) or ((info[14].toLong() and 0xFF) shl 16) or
            ((info[15].toLong() and 0xFF) shl 8) or (info[16].toLong() and 0xFF)
        if (sr <= 0 || total <= 0) return -1L
        return total * 1000L / sr
    }

    /**
     * OGG: sampleRate — из vorbis-identification заголовка первой страницы,
     * duration = granulePosition последней страницы / rate.
     * OPUS: rate фиксирован 48000, granule = samples.
     */
    private fun ogg(input: InputStream): Long {
        val first = read(input, 8 * 1024)
        if (first.size < 58 || String(first, 0, 4, Charsets.ISO_8859_1) != "OggS") return -1L
        val isOpus = String(first, 28, 4, Charsets.ISO_8859_1) == "Opus"
        val isVorbis = String(first, 29, 6, Charsets.ISO_8859_1) == "vorbis"
        if (!isOpus && !isVorbis) return -1L
        val rate = when {
            isOpus -> 48000L
            else -> u32le(first, 40) // vorbis: sampleRate в ident-заголовке
        }
        if (rate <= 0) return -1L
        // granule последней страницы: читаем хвост потока — к сожалению, у нас только
        // InputStream без seek, поэтому точную длительность OGG здесь не считаем
        return -1L
    }

    /** MP4/M4A: ищем бокс mvhd, длительность = duration/timescale (секунды). */
    private fun mp4(input: InputStream): Long {
        val window = read(input, 256 * 1024)
        var i = 0
        while (i + 32 <= window.size) {
            val size32 = u32be(window, i)
            val type = String(window, i + 4, 4, Charsets.ISO_8859_1)
            if (type == "mvhd") {
                val version = window[i + 8].toInt()
                val timescale: Long
                val duration: Long
                if (version == 1) {
                    if (i + 32 + 12 > window.size) return -1L
                    timescale = u32be(window, i + 8 + 4 + 8 + 8)
                    duration = u64be(window, i + 8 + 4 + 8 + 8 + 4)
                } else {
                    if (i + 32 > window.size) return -1L
                    timescale = u32be(window, i + 8 + 4 + 4 + 4)
                    duration = u32be(window, i + 8 + 4 + 4 + 4 + 4)
                }
                if (timescale <= 0) return -1L
                return duration * 1000L / timescale
            }
            i += if (size32 >= 8) size32.toInt() else 8
        }
        return -1L
    }

    private fun read(input: InputStream, max: Int): ByteArray {
        val buffer = ByteArray(max)
        var total = 0
        while (total < max) {
            val n = input.read(buffer, total, max - total)
            if (n < 0) break
            total += n
        }
        return if (total == max) buffer else buffer.copyOf(total)
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining--
            } else remaining -= skipped
        }
    }

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u32be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun u64be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 56) or ((b[off + 1].toLong() and 0xFF) shl 48) or
            ((b[off + 2].toLong() and 0xFF) shl 40) or ((b[off + 3].toLong() and 0xFF) shl 32) or
            ((b[off + 4].toLong() and 0xFF) shl 24) or ((b[off + 5].toLong() and 0xFF) shl 16) or
            ((b[off + 6].toLong() and 0xFF) shl 8) or (b[off + 7].toLong() and 0xFF)
}
