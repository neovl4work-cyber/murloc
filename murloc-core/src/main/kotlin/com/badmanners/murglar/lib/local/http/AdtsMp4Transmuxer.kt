package com.badmanners.murglar.lib.local.http

import java.io.EOFException
import java.io.InputStream

/**
 * Трансмуксирование сырого AAC (ADTS) в MP4 «на лету» для StreamServer.
 *
 * Зачем: файлы Яндекс.Музыки — сырой ADTS-поток с расширением .m4a. ExoPlayer
 * разбирает их через AdtsExtractor, который ПО УМОЛЧАНИЮ не строит seek-карту
 * (media3 AdtsExtractor с flags=0 всегда отдаёт SeekMap.Unseekable) — хост
 * запрещает перемотку. MP4Extractor с полноценным индексом (stco/stsz/stts)
 * перематывает нормально, поэтому для ADTS-контента синтезируем MP4:
 * ftyp + moov (faststart) + mdat, полезная нагрузка — ADTS-фреймы без
 * заголовков (raw AAC). Индекс фреймов строится одним проходом и кэшируется;
 * Range-запросы маппятся на смещения исходного файла.
 */
internal class AdtsIndex(
    val sampleRate: Int,
    val channels: Int,
    val frames: Int,
    /** смещение полезной нагрузки фрейма в исходном файле */
    val payloadOffsets: LongArray,
    /** размер полезной нагрузки фрейма */
    val payloadSizes: IntArray,
    /** накопительные смещения в mdat: cum[i]..cum[i+1] — фрейм i; cum[frames] = total */
    val cumPayload: LongArray,
    /** ftyp + moov + 8-байтовый заголовок mdat */
    val prefix: ByteArray,
) {
    /** полный размер синтезированного MP4 */
    val totalSize: Long = prefix.size.toLong() + cumPayload[frames]

    /** файл-смещение начала полезной нагрузки в синтезированном MP4 */
    val payloadFileOffset: Long get() = prefix.size.toLong()
}

internal object AdtsMp4Transmuxer {

    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

    fun isAdts(head: ByteArray): Boolean {
        val b0 = head.getOrNull(0)?.toInt()?.and(0xFF) ?: return false
        val b1 = head.getOrNull(1)?.toInt()?.and(0xFF) ?: return false
        return b0 == 0xFF && (b1 and 0xF6) == 0xF0
    }

    /** [head] — первые 12 байт (уже прочитаны вызывающим кодом), [stream] — остаток */
    /**
     * [head] — первые 12 байт файла (уже прочитаны вызывающим кодом),
     * [stream] — ОСТАТОК файла (позиция сразу после head!). Контракт важен:
     * иначе head будет учтён дважды и смещения «поплывут» на его размер.
     */
    fun build(head: ByteArray, stream: InputStream): AdtsIndex? = try {
        build(PushbackCountingStream(head, stream))
    } catch (e: Throwable) {
        null
    }

    private fun build(input: PushbackCountingStream): AdtsIndex? {
        // ID3v2 в начале
        val head12 = read(input, 12)
        if (head12.size >= 10 && String(head12, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val len = ((head12[6].toInt() and 0x7F) shl 21) or ((head12[7].toInt() and 0x7F) shl 14) or
                ((head12[8].toInt() and 0x7F) shl 7) or (head12[9].toInt() and 0x7F)
            skipFully(input, 10L + len - head12.size)
        } else if (head12.isNotEmpty()) {
            input.unread(head12)
        }

        val offsets = ArrayList<Long>(4096)
        val sizes = ArrayList<Int>(4096)
        var rate = 0
        var channels = 2
        var firstAsc = -1
        var guard = 0

        while (guard++ < 5_000_000) {
            // ищем синхронизацию и читаем 7-байтовый заголовок фрейма
            var b0: Int
            var b1: Int
            var scanned = 0
            while (true) {
                val pair = read(input, 2)
                if (pair.size < 2) return finalize(offsets, sizes, rate, channels, firstAsc)
                b0 = pair[0].toInt()
                b1 = pair[1].toInt()
                if ((b0 and 0xFF) == 0xFF && (b1 and 0xF6) == 0xF0) break
                if (++scanned > 4096) return null // не ADTS
                input.unread(pair.copyOfRange(1, 2))
            }
            val tail5 = read(input, 5)
            if (tail5.size < 5) return finalize(offsets, sizes, rate, channels, firstAsc)
            val h = byteArrayOf(b0.toByte(), b1.toByte()) + tail5
            val protectionAbsent = (b1 shr 1) and 1
            val rateIdx = ((h[2].toInt() and 0xFF) shr 2) and 0x0F
            val chanConfig = ((h[2].toInt() and 1) shl 2) or ((h[3].toInt() and 0xC0) shr 6)
            val headerLen = if (protectionAbsent == 1) 7 else 9
            val frameLen = ((h[3].toInt() and 0x03) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
            if (rateIdx !in 1..12 || frameLen < headerLen + 1) return null
            if (rate == 0) {
                rate = SAMPLE_RATES[rateIdx]
                channels = if (chanConfig in 1..7) chanConfig else 2
                val profile = ((h[2].toInt() and 0xC0) shr 6) + 1 // AAC LC = 2
                firstAsc = (profile shl 11) or (rateIdx shl 7) or (chanConfig shl 3)
            }
            offsets.add(input.position + (headerLen - 7))
            sizes.add(frameLen - headerLen)
            val skipped = skipCounted(input, (frameLen - 7).toLong())
            if (skipped < frameLen - 7) {
                // обрезанный фрейм в конце файла — не включаем
                offsets.removeAt(offsets.lastIndex)
                sizes.removeAt(sizes.lastIndex)
                break
            }
        }
        return finalize(offsets, sizes, rate, channels, firstAsc)
    }

    private fun finalize(
        offsets: ArrayList<Long>, sizes: ArrayList<Int>,
        rate: Int, channels: Int, asc: Int,
    ): AdtsIndex? {
        if (rate <= 0 || offsets.isEmpty()) return null
        val frames = offsets.size
        val payloadOffsets = offsets.toLongArray()
        val payloadSizes = sizes.toIntArray()
        val cum = LongArray(frames + 1)
        for (i in 0 until frames) cum[i + 1] = cum[i] + payloadSizes[i]
        val prefix = buildMp4Prefix(rate, channels, frames, payloadSizes, cum[frames], asc)
        return AdtsIndex(rate, channels, frames, payloadOffsets, payloadSizes, cum, prefix)
    }

    // --- MP4-синтез ---

    private fun buildMp4Prefix(
        rate: Int, channels: Int, frames: Int,
        payloadSizes: IntArray, totalPayload: Long, asc: Int,
    ): ByteArray {
        val ftyp = box("ftyp", ascii("isom"), u32(0x200), ascii("isom"), ascii("iso2"), ascii("mp41"))

        val matrix = byteArrayOf(
            0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0x40.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val durationMs = frames * 1024L * 1000L / rate

        val mvhd = fullBox("mvhd", 0, 0,
            u32(0), u32(0), u32(1000), u32(durationMs), u32(0x10000), u16(0x0100), u16(0),
            ByteArray(10), matrix.copyOf(), ByteArray(24), u32(2))
        val tkhd = fullBox("tkhd", 0, 7,
            u32(0), u32(0), u32(1), u32(0), u32(durationMs), ByteArray(8),
            u16(0), u16(0), u16(0x0100), u16(0), matrix.copyOf(), u32(0), u32(0))
        val mdhd = fullBox("mdhd", 0, 0, u32(0), u32(0), u32(rate), u32(frames * 1024L), u16(0x55C4), u16(0))
        val hdlr = fullBox("hdlr", 0, 0, u32(0), ascii("soun"), ByteArray(12), ascii("SoundHandler\u0000"))
        val smhd = box("smhd", u16(0), u16(0))
        val dinf = box("dinf", box("dref", u32(1), fullBox("url ", 0, 1)))

        val stsd = fullBox("stsd", 0, 0, u32(1), mp4aSampleEntry(rate, channels, asc))
        val stts = fullBox("stts", 0, 0, u32(1), u32(frames), u32(1024))
        val stsc = fullBox("stsc", 0, 0, u32(1), u32(1), u32(frames), u32(1))
        val stszBody = ByteArray(8 + 4 * frames)
        var p = 0
        putU32(stszBody, p, 0L); p += 4
        putU32(stszBody, p, frames.toLong()); p += 4
        for (i in 0 until frames) { putU32(stszBody, p, payloadSizes[i].toLong()); p += 4 }
        val stsz = fullBox("stsz", 0, 0, stszBody)

        // stco зависит от смещения mdat, которое зависит от размера moov —
        // но размер коробок фиксирован, поэтому считаем напрямую
        val stblWoStco = box("stbl", stsd, stts, stsc, stsz)
        val moovWoStco = box("moov", mvhd, box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", smhd, dinf, stblWoStco))))
        val mdatHeader = ByteArray(8)
        putU32(mdatHeader, 0, totalPayload + 8)
        System.arraycopy(ascii("mdat"), 0, mdatHeader, 4, 4)
        val payloadOffset = (ftyp.size + moovWoStco.size + fullBox("stco", 0, 0, u32(1), u32(0)).size + mdatHeader.size).toLong()
        val stco = fullBox("stco", 0, 0, u32(1), u32(payloadOffset))

        val stbl = box("stbl", stsd, stts, stsc, stsz, stco)
        val minf = box("minf", smhd, dinf, stbl)
        val mdia = box("mdia", mdhd, hdlr, minf)
        val trak = box("trak", tkhd, mdia)
        val moov = box("moov", mvhd, trak)
        return ftyp + moov + mdatHeader
    }

    /** AudioSampleEntry 'mp4a' с ESDescriptor (AAC) */
    private fun mp4aSampleEntry(rate: Int, channels: Int, asc: Int): ByteArray {
        val esds = fullBox("esds", 0, 0, esDescriptor(asc))
        return box("mp4a",
            ByteArray(6), u16(1), ByteArray(8), u16(channels), u16(16), u16(0), u16(0),
            u32(rate.toLong() shl 16), esds)
    }

    /** ES_Descriptor + DecoderConfigDescriptor + DecoderSpecificInfo (AudioSpecificConfig) */
    private fun esDescriptor(asc: Int): ByteArray {
        val dsi = descriptor(0x05, u16(asc))
        val dcd = descriptor(0x04, byteArrayOf(0x40, 0x15) + u24(0) + u32(0) + u32(0) + dsi)
        val slcd = descriptor(0x06, byteArrayOf(2))
        return descriptor(0x03, u16(1) + byteArrayOf(0) + dcd + slcd)
    }

    private fun descriptor(tag: Int, payload: ByteArray): ByteArray {
        require(payload.size < 0x80) { "descriptor too long" }
        return byteArrayOf(tag.toByte(), payload.size.toByte()) + payload
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        var size = 8
        for (part in parts) size += part.size
        return u32(size.toLong()) + ascii(type) + concat(parts)
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray {
        val head = byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte())
        return box(type, concat(arrayOf(head, *parts)))
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        var size = 0
        for (part in parts) size += part.size
        val out = ByteArray(size)
        var pos = 0
        for (part in parts) { System.arraycopy(part, 0, out, pos, part.size); pos += part.size }
        return out
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray = concat(arrayOf(this, other))

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    private fun u16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun u24(v: Int): ByteArray = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun u32(v: Long): ByteArray =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun u32(v: Int): ByteArray = u32(v.toLong() and 0xFFFFFFFFL)

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
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

    private fun skipCounted(input: InputStream, bytes: Long): Long {
        var remaining = bytes
        var done = 0L
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining--
                done++
            } else {
                remaining -= skipped
                done += skipped
            }
        }
        return done
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        skipCounted(input, bytes)
    }

    /** InputStream: прочитанный префикс + остаток, с отслеживанием позиции и unread */
    private class PushbackCountingStream(head: ByteArray, private val rest: InputStream) : InputStream() {
        private var buf: ByteArray? = if (head.isEmpty()) null else head
        private var pos = 0
        var position = 0L
            private set

        fun unread(bytes: ByteArray) {
            buf = bytes
            pos = 0
            position -= bytes.size
        }

        override fun read(): Int {
            val b = buf
            if (b != null) {
                if (pos < b.size) { val v = b[pos++].toInt() and 0xFF; position++; return v }
                buf = null
            }
            val v = rest.read()
            if (v >= 0) position++
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val buffered = buf
            if (buffered != null) {
                if (pos < buffered.size) {
                    val n = minOf(len, buffered.size - pos)
                    System.arraycopy(buffered, pos, b, off, n)
                    pos += n
                    position += n
                    return n
                }
                buf = null
            }
            val n = rest.read(b, off, len)
            if (n > 0) position += n
            return n
        }

        override fun skip(n: Long): Long {
            val buffered = buf
            if (buffered != null && pos < buffered.size) {
                val k = minOf(n, (buffered.size - pos).toLong())
                pos += k.toInt()
                position += k
                return k
            }
            buf = null
            val skipped = rest.skip(n)
            if (skipped > 0) position += skipped
            return skipped
        }
    }
}
