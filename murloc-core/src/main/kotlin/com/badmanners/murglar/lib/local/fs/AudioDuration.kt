package com.badmanners.murglar.lib.local.fs

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

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

    /**
     * Формат определяем по «магическим» байтам, а не по расширению:
     * Яндекс.Музыка (и др.) сохраняет сырой AAC-поток (ADTS) с расширением
     * .m4a — по расширению такой файл уходил в mp4-парсер и получал
     * фолбэк-длительность 1 сек.
     */
    fun detect(stream: InputStream, size: Long, extension: String): Long {
        val head = read(stream, 12)
        val s = SequenceInputStream(ByteArrayInputStream(head), stream)
        val tag = if (head.size >= 4) String(head, 0, 4, Charsets.ISO_8859_1) else ""
        val b0 = head.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
        val b1 = head.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
        val sniffed: String? = when {
            tag == "RIFF" -> "wav"
            tag == "fLaC" -> "flac"
            tag == "OggS" -> "ogg"
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "m4a"
            // ADTS: 11 бит синхронизации + layer == 00 (биты 1-2 второго байта)
            b0 == 0xFF && (b1 and 0xF6) == 0xF0 -> "aac"
            // MPEG-фрейм (MP3 без ID3)
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> "mp3"
            else -> null
        }
        return try {
            when (sniffed ?: extension.lowercase()) {
                "wav", "wave" -> wav(s)
                "mp3" -> mp3(s, size)
                "flac" -> flac(s)
                "ogg", "oga", "opus" -> ogg(s, size)
                "aac" -> aac(s, size)
                "m4a", "mp4" -> mp4(s)
                else -> -1L
            }
        } catch (e: Exception) {
            -1L
        }
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
     * OGG: sampleRate из vorbis-identification первой страницы (OPUS = 48000),
     * длительность = granulePosition ПОСЛЕДНЕЙ страницы / rate.
     * Granule читается из хвоста: поток без seek — читаем целиком, держим
     * последние 64 КБ. Файлы > 64 МБ пропускаем (долго по SAF).
     */
    private fun ogg(input: InputStream, size: Long): Long {
        if (size <= 0 || size > 64L * 1024 * 1024) return -1L
        val first = read(input, 8 * 1024)
        if (first.size < 58 || String(first, 0, 4, Charsets.ISO_8859_1) != "OggS") return -1L
        val isOpus = String(first, 28, 4, Charsets.ISO_8859_1) == "Opus"
        val isVorbis = String(first, 29, 6, Charsets.ISO_8859_1) == "vorbis"
        if (!isOpus && !isVorbis) return -1L
        val rate = if (isOpus) 48000L else u32le(first, 40)
        if (rate <= 0) return -1L

        val tailSize = 64 * 1024
        var tail = ByteArray(0)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            tail = if (n >= tailSize) buffer.copyOfRange(n - tailSize, n)
            else (tail + buffer.copyOf(n)).takeLast(tailSize).toByteArray()
        }
        var idx = -1
        for (i in tail.size - 27 downTo 0) {
            if (tail[i] == 0x4F.toByte() && tail[i + 1] == 0x67.toByte() &&
                tail[i + 2] == 0x67.toByte() && tail[i + 3] == 0x53.toByte()) { idx = i; break }
        }
        if (idx < 0 || idx + 14 > tail.size) return -1L
        var granule = 0L
        for (b in 6..13) granule = granule or ((tail[idx + b].toLong() and 0xFF) shl (8 * (b - 6)))
        if (granule <= 0) return -1L
        return granule * 1000L / rate
    }

    /** AAC (ADTS): считаем фреймы по заголовкам 0xFFF; frames * 1024 / sampleRate. */
    private fun aac(input: InputStream, size: Long): Long {
        if (size <= 0 || size > 64L * 1024 * 1024) return -1L
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
        val head = read(input, 8192)
        var stream = head
        var streamPos = 0
        if (head.size >= 10 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val len = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            val skipTotal = 10 + len
            if (skipTotal >= head.size) {
                skipFully(input, (skipTotal - head.size).toLong())
                stream = ByteArray(0)
            } else {
                streamPos = skipTotal
            }
        }
        var rate = 0
        var frames = 0L
        var errors = 0
        while (errors < 64) {
            if (stream.size - streamPos < 7) {
                val chunk = read(input, 64 * 1024)
                if (chunk.isEmpty()) break
                stream = stream.copyOfRange(streamPos, stream.size) + chunk
                streamPos = 0
                if (stream.size < 7) break
            }
            val b0 = stream[streamPos].toInt() and 0xFF
            val b1 = stream[streamPos + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) {
                val rateIdx = (stream[streamPos + 2].toInt() and 0xFF) shr 2 and 0x0F
                val frameLen = ((stream[streamPos + 3].toInt() and 0x03) shl 11) or
                    ((stream[streamPos + 4].toInt() and 0xFF) shl 3) or
                    ((stream[streamPos + 5].toInt() and 0xFF) shr 5)
                if (rateIdx in 1..12 && frameLen >= 7) {
                    if (rate == 0) rate = rates[rateIdx - 1]
                    frames++
                    errors = 0
                    if (frameLen <= stream.size - streamPos) {
                        streamPos += frameLen
                    } else {
                        skipFully(input, (frameLen - (stream.size - streamPos)).toLong())
                        stream = ByteArray(0); streamPos = 0
                    }
                    continue
                }
            }
            errors++
            streamPos++
        }
        if (rate <= 0 || frames <= 0) return -1L
        return frames * 1024L * 1000L / rate
    }

    /** MP4/M4A: сканируем сигнатуру бокса mvhd (внутри moov) с валидацией полей. */
    private fun mp4(input: InputStream): Long {
        val window = read(input, 4 * 1024 * 1024)
        var i = 4
        while (i + 40 <= window.size) {
            if (window[i] == 0x6D.toByte() && window[i + 1] == 0x76.toByte() &&
                window[i + 2] == 0x68.toByte() && window[i + 3] == 0x64.toByte()) {
                val version = window[i + 4].toInt()
                val timescale: Long
                val duration: Long
                if (version == 1) {
                    if (i + 36 > window.size) return -1L
                    timescale = u32be(window, i + 20)
                    duration = u64be(window, i + 24)
                } else {
                    if (i + 28 > window.size) return -1L
                    timescale = u32be(window, i + 16)
                    duration = u32be(window, i + 20)
                }
                if (timescale in 1000..10_000_000 && duration > 0)
                    return duration * 1000L / timescale
            }
            i++
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
