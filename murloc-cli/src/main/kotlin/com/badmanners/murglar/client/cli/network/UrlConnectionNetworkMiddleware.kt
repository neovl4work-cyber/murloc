package com.badmanners.murglar.client.cli.network

import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.NetworkResponse
import com.badmanners.murglar.lib.core.network.ResponseConversionException
import com.badmanners.murglar.lib.core.network.ResponseConverter
import java.io.InputStream
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets


/**
 * Minimal [NetworkMiddleware] impl for CLI based on `java.net.HttpURLConnection`.
 *
 * No real network IO is required for a local-files plugin - this implementation exists only
 * to satisfy the [LocalMurglar] constructor and to download first bytes of resolved urls
 * (`file://` and `http://127.0.0.1:...` of the embedded stream server) as a smoke test.
 */
class UrlConnectionNetworkMiddleware(
    private val logger: LoggerMiddleware
) : NetworkMiddleware {

    override fun getCookies(domain: String): List<HttpCookie> = emptyList()

    override fun getCookie(domain: String, name: String): HttpCookie? = null

    override fun addCookie(cookie: HttpCookie) = Unit

    override fun clearCookiesForDomains(vararg domains: String) = Unit

    override fun clearAllCookies() = Unit

    override suspend fun <T> execute(request: NetworkRequest, converter: ResponseConverter<T>): NetworkResponse<T> {
        val url = URL(request.url)

        if (url.protocol == "file") {
            val result = url.openStream().use { converter.convert(it, StandardCharsets.UTF_8) }
            return NetworkResponse(request, result, emptyList(), 200, "OK")
        }

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = request.followRedirects
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.userAgent?.let { connection.setRequestProperty("User-Agent", it) }

            if (request.hasBody)
                error("Request bodies aren't supported by UrlConnectionNetworkMiddleware!")

            val statusCode = connection.responseCode
            val message = connection.responseMessage ?: ""
            val headers = connection.headerFields
                .filterKeys { it != null }
                .flatMap { (name, values) -> values.map { name to it } }

            val result = if (converter.dropBody) {
                converter.emptyResult
            } else {
                val stream: InputStream = when {
                    statusCode in 200 until 300 -> connection.inputStream
                    else -> connection.errorStream ?: connection.inputStream
                }
                stream.use {
                    try {
                        converter.convert(it, charsetOf(connection, request))
                    } catch (e: Exception) {
                        throw ResponseConversionException(request, e, headers, statusCode, message)
                    }
                }
            }

            return NetworkResponse(request, result, headers, statusCode, message)
        } finally {
            connection.disconnect()
        }
    }

    private fun charsetOf(connection: HttpURLConnection, request: NetworkRequest): Charset =
        request.contentType?.charset ?: runCatching {
            connection.contentType?.let { ContentTypeCharsetRegex.find(it)?.groupValues?.get(1) }
        }.getOrNull()?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8

    private companion object {
        val ContentTypeCharsetRegex = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
    }
}
