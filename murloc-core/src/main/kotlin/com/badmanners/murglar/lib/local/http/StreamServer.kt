package com.badmanners.murglar.lib.local.http

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.local.fs.FileScanner
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.InputStream


/**
 * Embedded single-file HTTP server on `127.0.0.1` with a random free port.
 *
 * Streams local audio files to the host player through `http://` urls
 * (fallback for hosts that can't play `file://`/`content://` urls directly).
 * Supports `Range` requests for seeking.
 */
class StreamServer(
    private val logger: LoggerMiddleware,
    private val scanner: FileScanner
) {

    companion object {
        private const val TAG = "StreamServer"
    }

    @Volatile
    private var server: NanoHTTPD? = null

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

        val opened = scanner.open(id)
            ?: return fixed(Response.Status.NOT_FOUND, "Not found: $id")

        val (stream, total) = opened
        val mime = mimeFor(id)
        val range = session.headers["range"]

        if (range == null || total < 0) {
            val response = when {
                total >= 0 -> NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, stream, total)
                else -> NanoHTTPD.newChunkedResponse(Response.Status.OK, mime, stream)
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

        skipFully(stream, start)

        val length = end - start + 1
        val response = NanoHTTPD.newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, length)
        response.addHeader("Content-Range", "bytes $start-$end/$total")
        response.addHeader("Accept-Ranges", "bytes")
        return response
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
