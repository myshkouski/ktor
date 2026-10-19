/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.await
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit
import kotlin.coroutines.CoroutineContext
import kotlin.js.Promise

internal fun Request.toApplicationCall(
    application: Application,
    context: CoroutineContext
): FetchApplicationCall {
    val parsedUrl = Url(this.url)
    val httpMethod = HttpMethod(this.method)
    val headers = this.headers.toKtorHeaders()

    return FetchApplicationCall(
        application = application,
        coroutineContext = context,
        method = httpMethod,
        uri = parsedUrl.encodedPathAndQuery,
        version = HttpProtocolVersion.HTTP_1_1.toString(),
        scheme = parsedUrl.protocol.name,
        headers = headers,
        body = readBody()
    )
}

private fun org.w3c.fetch.Headers.toKtorHeaders(): Headers = Headers.build {
    this@toKtorHeaders.asDynamic().forEach { value: String, key: String ->
        append(key, value)
    }
}

private fun ResponseHeaders.toJsHeaders(): dynamic {
    val jsHeaders = js("({})")
    allValues().forEach { name, values ->
        jsHeaders[name] = values.joinToString(",")
    }
    return jsHeaders
}

private fun Request.readBody(): ByteReadChannel {
    @Suppress("UnsafeCastFromDynamic")
    val stream: ReadableStream<Uint8Array> = asDynamic().body ?: return ByteReadChannel.Empty
    return channelFromStream(stream)
}

private fun channelFromStream(stream: ReadableStream<Uint8Array>): ByteReadChannel =
    ReadableStreamByteReadChannel(stream)

@Suppress("UnsafeCastFromDynamic")
private fun Uint8Array.asByteArray(): ByteArray = Int8Array(buffer, byteOffset, length).asDynamic()

private external interface ReadableStream<R> {
    fun getReader(): ReadableStreamDefaultReader<R>
}

private external interface ReadableStreamDefaultReader<R> {
    fun read(): Promise<ReadableStreamReadResult<R>>
    fun cancel(reason: dynamic = definedExternally): Promise<Unit>
}

private external interface ReadableStreamReadResult<T> {
    val done: Boolean
    val value: T
}

private suspend fun ReadableStreamDefaultReader<Uint8Array>.readChunk(): Uint8Array? {
    val result = read().await()
    return if (result.done) null else result.value
}

private class ReadableStreamByteReadChannel(private val stream: ReadableStream<Uint8Array>) : ByteReadChannel {
    private val buffer = Buffer()
    private var reader: ReadableStreamDefaultReader<Uint8Array>? = null
    private var closed = false
    private var cause: Throwable? = null

    override val closedCause: Throwable? get() = cause

    override val isClosedForRead: Boolean get() = closed && buffer.exhausted()

    @InternalAPI
    override val readBuffer: Source get() = buffer

    override suspend fun awaitContent(min: Int): Boolean {
        if (closed) {
            cause?.let { throw it }
            return buffer.size >= min
        }

        val reader = reader ?: stream.getReader().also { reader = it }
        while (buffer.size < min) {
            val chunk = try {
                reader.readChunk()
            } catch (cause: Throwable) {
                reader.cancel(cause).catch { }
                throw cause
            }
            if (chunk == null) {
                closed = true
                break
            }
            buffer.write(chunk.asByteArray())
        }

        cause?.let { throw it }
        return buffer.size >= min
    }

    override fun cancel(cause: Throwable?) {
        if (closed) return
        this.cause = cause ?: IOException("Channel was cancelled")
        closed = true
        reader?.cancel(this.cause)?.catch { }
    }
}

@Suppress("UnsafeCastFromDynamic")
internal suspend fun FetchApplicationCall.toWebResponse(): Response {
    val status = response.status() ?: HttpStatusCode.OK
    val bytes = response.bodyBytes()

    val webHeaders = response.headers.toJsHeaders()

    val method = request.httpMethod
    val body: dynamic = if (
        bytes.isEmpty() ||
        method == HttpMethod.Head ||
        status == HttpStatusCode.NoContent ||
        status == HttpStatusCode.NotModified
    ) {
        null
    } else {
        Uint8Array(bytes.toTypedArray())
    }

    return Response(body, ResponseInit(status.value.toShort(), status.description, webHeaders))
}
