package com.badmanners.murglar.lib.local.http

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.local.fs.FileScanner
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap


/**
 * Embedded single-file HTTP server on `127.0.0.1` with a random free port.
 *
 * Streams local audio files to the host player through `http://` urls
 * (fallback for hosts that can't play `file://`/`content://` urls directly).
 * Supports `Range` requests for seeking.
 *
 * Сырой AAC (ADTS) — в т.ч. «.m4a» от Яндекс.Музыки — трансмуксируется в MP4
 * на лету ([AdtsMp4Transmuxer]): ExoPlayer иначе берёт AdtsExtractor, который
 * без флага FLAG_ENABLE_CONSTANT_BITRATE_SEEKING не даёт перематывать вообще.
 */
class StreamServer(
    private val logger: LoggerMiddleware,
    private val scanner: FileScanner
) {

    companion object {
        private const val TAG = "StreamServer"
        private const val ADTS_CACHE_MAX = 64

        /** Временная диагностика: последние запросы к серверу (файл reqlog.txt в files/) */
        val requestLog = java.util.concurrent.ConcurrentLinkedQueue<String>()

        private fun logRequest(line: String) {
            requestLog.add(line)
            try {
                val logClass = Class.forName("android.util.Log")
                logClass.getMethod("i", String::class.java, String::class.java).invoke(null, "StreamServer", line)
            } catch (_: Throwable) {
            }
        }
    }

    @Volatile
    private var server: NanoHTTPD? = null

    /** id -> индекс ADTS-трансмуксации; не-ADTS файлы — в adtsNegatives */
    private val adtsCache = ConcurrentHashMap<String, AdtsIndex>()
    private val adtsNegatives = ConcurrentHashMap.newKeySet<String>()

    val isStarted: Boolean
        get() = server?.isAlive == true

    /**
     * Starts the server if it isn't already running, returns the actual port.
     */
    @Synchronized
    fun ensureStarted(): Int {
        server?.takeIf { it.isAlive }?.let { return it.listeningPort }

        val nano = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = serveFile(session)
        }

        try {
            nano.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            throw IllegalStateException("Can't start stream server!", e)
        }

        server = nano
        val port = nano.listeningPort
        logger.i(TAG, "Stream server started on http://127.0.0.1:$port")
        return port
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            logger.e(TAG, "Can't stop stream server!", e)
        }
        server = null
    }

    private fun serveFile(session: IHTTPSession): Response {
        val id = session.parms["id"]
            ?: return fixed(Response.Status.BAD_REQUEST, "No id!")

        logRequest("${session.method} id=${id.substringAfterLast('/')} range=${session.headers["range"]} ua=${session.headers["user-agent"]?.take(40)}")

        val head = readHead(id) ?: return fixed(Response.Status.NOT_FOUND, "Not found: $id")

        // ADTS-индекс: из кэша или строим sniff-ом первых байт
        var index = adtsCache[id]
        if (index == null && !adtsNegatives.contains(id)) {
            index = resolveAdtsIndex(id, head)
        }

        return if (index != null) {
            serveRemuxed(session, id, index)
        } else {
            serveOriginal(session, id, head)
        }
    }

    /** Открывает файл и читает первые 12 байт (повторно открывает, т.к. вызывающий код сам решит путь) */
    private fun readHead(id: String): ByteArray? {
        val opened = scanner.open(id) ?: return null
        return try {
            read(opened.first, 12)
        } finally {
            runCatching { opened.first.close() }
        }
    }

    private fun resolveAdtsIndex(id: String, head: ByteArray): AdtsIndex? {
        if (!AdtsMp4Transmuxer.isAdts(head)) {
            adtsNegatives.add(id)
            return null
        }
        val opened = scanner.open(id) ?: return null
        val built = try {
            // контракт AdtsMp4Transmuxer.build: поток должен начинаться СРАЗУ ПОСЛЕ head
            skipFully(opened.first, head.size.toLong())
            AdtsMp4Transmuxer.build(head, opened.first)
        } finally {
            runCatching { opened.first.close() }
        }
        if (built != null) {
            if (adtsCache.size >= ADTS_CACHE_MAX) adtsCache.keys.firstOrNull()?.let { adtsCache.remove(it) }
            adtsCache[id] = built
            logger.i(TAG, "ADTS->MP4 remux index built for '$id': ${built.frames} frames, ${built.totalSize} bytes")
        } else {
            adtsNegatives.add(id)
        }
        return built
    }

    /** Отдача исходного файла (не-ADTS) */
    private fun serveOriginal(session: IHTTPSession, id: String, head: ByteArray): Response {
        val opened = scanner.open(id) ?: return fixed(Response.Status.NOT_FOUND, "Not found: $id")
        val (stream, total) = opened
        val mime = mimeFor(id)
        val source = SequenceInputStream(head, stream)
        val range = session.headers["range"]

        if (range == null || total < 0) {
            val response = when {
                total >= 0 -> NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, source, total)
                else -> NanoHTTPD.newChunkedResponse(Response.Status.OK, mime, source)
            }
            response.addHeader("Accept-Ranges", "bytes")
            return response
        }

        val parsedRange = parseRange(range, total)
        if (parsedRange == null) {
            val response = fixed(Response.Status.RANGE_NOT_SATISFIABLE, "Bad range!")
            response.addHeader("Content-Range", "bytes */$total")
            return response
        }
        val (start, end) = parsedRange
        skipFully(source, start)

        val length = end - start + 1
        val response = NanoHTTPD.newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, source, length)
        response.addHeader("Content-Range", "bytes $start-$end/$total")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    /** Отдача синтезированного MP4 из ADTS */
    private fun serveRemuxed(session: IHTTPSession, id: String, index: AdtsIndex): Response {
        val total = index.totalSize
        val mime = "audio/mp4"
        val range = session.headers["range"]

        if (range == null) {
            val response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, mime, RemuxedInputStream(id, index, 0, total), total)
            response.addHeader("Accept-Ranges", "bytes")
            return response
        }

        val parsedRange = parseRange(range, total)
        if (parsedRange == null) {
            val response = fixed(Response.Status.RANGE_NOT_SATISFIABLE, "Bad range!")
            response.addHeader("Content-Range", "bytes */$total")
            return response
        }
        val (start, end) = parsedRange
        val length = end - start + 1
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mime, RemuxedInputStream(id, index, start, end + 1), length)
        response.addHeader("Content-Range", "bytes $start-$end/$total")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    /**
     * Читает байты [from, to) синтезированного MP4: сначала префикс (ftyp+moov+mdat header),
     * затем полезную нагрузку, маппя смещения на ADTS-фреймы исходного файла.
     */
    private inner class RemuxedInputStream(
        private val id: String,
        private val index: AdtsIndex,
        private val from: Long,
        private val to: Long,
    ) : InputStream() {

        private var position = from
        private var source: InputStream? = null
        private var frameIdx = -1
        private var oneByte = ByteArray(1)

        override fun read(): Int = if (read(oneByte, 0, 1) == 1) oneByte[0].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= to || len == 0) return if (position >= to) -1 else 0
            val want = minOf(len.toLong(), to - position).toInt()

            // префикс
            val prefixEnd = index.prefix.size.toLong()
            if (position < prefixEnd) {
                val n = minOf(want, (prefixEnd - position).toInt())
                System.arraycopy(index.prefix, position.toInt(), b, off, n)
                position += n
                return n
            }

            // полезная нагрузка
            val payloadTo = to - index.payloadFileOffset
            var cur = position - index.payloadFileOffset
            var written = 0
            while (written < want && cur < payloadTo) {
                if (source == null) {
                    val opened = scanner.open(id) ?: throw java.io.IOException("Can't reopen '$id'")
                    source = opened.first
                    frameIdx = findFrame(cur)
                    skipFully(source!!, index.payloadOffsets[frameIdx] + (cur - index.cumPayload[frameIdx]))
                }
                // продвигаемся к фрейму, содержащему cur (прошлый read мог
                // остановиться ровно на границе фрейма)
                while (index.cumPayload[frameIdx + 1] <= cur) {
                    frameIdx++
                    skipFully(source!!, index.payloadOffsets[frameIdx] - (index.payloadOffsets[frameIdx - 1] + index.payloadSizes[frameIdx - 1]))
                }
                val frameEnd = index.cumPayload[frameIdx + 1]
                val chunk = minOf((want - written).toLong(), minOf(frameEnd, payloadTo) - cur).toInt()
                var got = 0
                while (got < chunk) {
                    val n = source!!.read(b, off + written + got, chunk - got)
                    if (n < 0) throw java.io.EOFException(
                        "Source stream ended unexpectedly: pos=$position cur=$cur frame=$frameIdx " +
                        "srcOff=${index.payloadOffsets[frameIdx]} srcSize=${index.payloadSizes[frameIdx]}")
                    got += n
                }
                written += chunk
                cur += chunk
                position += chunk
            }
            return if (written == 0) -1 else written
        }

        /** индекс фрейма, содержащего payload-смещение [at] */
        private fun findFrame(at: Long): Int {
            var lo = 0
            var hi = index.frames - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (index.cumPayload[mid] <= at) lo = mid else hi = mid - 1
            }
            return lo
        }

        override fun close() {
            runCatching { source?.close() }
        }
    }

    private class SequenceInputStream(private val head: ByteArray, private val rest: InputStream) : InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos < head.size) head[pos++].toInt() and 0xFF else rest.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos < head.size) {
                val n = minOf(len, head.size - pos)
                System.arraycopy(head, pos, b, off, n)
                pos += n
                return n
            }
            return rest.read(b, off, len)
        }
    }

    private fun fixed(status: Response.Status, message: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain", message)

    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        // Supported form: "bytes=<start>-<end>" (end is optional).
        if (!header.startsWith("bytes="))
            return null

        val value = header.removePrefix("bytes=")
        val startString = value.substringBefore('-')
        val endString = value.substringAfter('-', "")

        val start = startString.toLongOrNull() ?: return null
        if (start < 0 || start >= total)
            return null

        val end = endString.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (end < start)
            return null

        return start to end
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() == -1)
                    break
                remaining--
            } else {
                remaining -= skipped
            }
        }
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

    private fun mimeFor(id: String) = when (id.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        else -> "application/octet-stream"
    }
}
