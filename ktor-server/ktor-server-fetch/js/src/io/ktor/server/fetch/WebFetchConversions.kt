/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
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
import org.w3c.fetch.Headers as FetchHeaders

private val hopByHopHeaders = setOf(
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade"
)

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
        host = parsedUrl.host,
        port = parsedUrl.port,
        headers = headers,
        body = readBody()
    )
}

private val Request.body: ReadableStream<Uint8Array>?
    @Suppress("UnsafeCastFromDynamic")
    get() = asDynamic().body

private fun org.w3c.fetch.Headers.toKtorHeaders(): Headers = Headers.build {
    this@toKtorHeaders.asDynamic().forEach { value: String, key: String ->
        append(key, value)
    }
}

private fun ResponseHeaders.toFetchHeaders(stripContentLength: Boolean): FetchHeaders {
    val headers = FetchHeaders()
    allValues().forEach { name, values ->
        if (name.lowercase() in hopByHopHeaders) return@forEach
        if (stripContentLength && name.equals(HttpHeaders.ContentLength, ignoreCase = true)) return@forEach
        for (value in values) {
            headers.append(name, value)
        }
    }
    return headers
}

private fun Request.readBody(): ByteReadChannel {
    return channelFromStream(body)
}

private fun channelFromStream(stream: ReadableStream<Uint8Array>?): ByteReadChannel =
    stream?.let { ReadableStreamByteReadChannel(it) } ?: ByteReadChannel.Empty

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
        val cancelCause = cause ?: IOException("Channel was cancelled")
        this.cause = cancelCause
        closed = true
        val reader = reader ?: runCatching { stream.getReader() }.getOrNull() ?: return
        reader.cancel(cancelCause).catch { }
    }
}

@Suppress("UnsafeCastFromDynamic")
internal fun FetchApplicationCall.toWebResponse(
    scope: CoroutineScope,
    bodyChannel: ByteReadChannel,
    requestJob: CompletableJob
): Response {
    val status = response.status() ?: HttpStatusCode.OK
    val method = request.httpMethod

    val omitBody = method == HttpMethod.Head ||
        status == HttpStatusCode.NoContent ||
        status == HttpStatusCode.NotModified
    val stripContentLength = status == HttpStatusCode.NoContent || status == HttpStatusCode.NotModified

    val body: dynamic = if (omitBody) {
        // The response must not have a body, but the application may still be writing one.
        scope.launch {
            runCatching { bodyChannel.discard() }
            requestJob.complete()
        }
        null
    } else {
        bodyChannel.toReadableStream(scope, requestJob)
    }

    val headers = response.headers.toFetchHeaders(stripContentLength)

    return Response(body, ResponseInit(status.value.toShort(), status.description, headers))
}

/**
 * Converts a response body channel to a pull-based [ReadableStream].
 *
 * Each `pull` reads at most one chunk from [bodyChannel] and enqueues it. This provides backpressure: while the
 * consumer does not read, `pull` is not called and the application eventually suspends on the bounded channel.
 */
@Suppress("UnsafeCastFromDynamic")
private fun ByteReadChannel.toReadableStream(
    scope: CoroutineScope,
    requestJob: CompletableJob
): dynamic {
    val buffer = ByteArray(RESPONSE_BODY_CHUNK_SIZE)
    val source = js("({})")

    source.pull = { controller: dynamic ->
        // Resume and read without an extra dispatch when bytes are already buffered, so the first chunk
        // reaches the runtime as soon as the application flushes it.
        scope.promise(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            val read = readAvailable(buffer)
            when {
                read < 0 -> {
                    val cause = closedCause
                    if (cause != null) {
                        controller.error(cause)
                    } else {
                        controller.close()
                    }
                    requestJob.complete()
                }

                read > 0 -> {
                    controller.enqueue(buffer.copyOf(read).toUint8Array(read))
                }
            }
            Unit
        }
    }

    source.cancel = { reason: dynamic ->
        val cause = reason as? Throwable ?: CancellationException("Response body was cancelled")
        cancel(cause)
        requestJob.cancel()
    }

    return js("new ReadableStream(source)")
}

private const val RESPONSE_BODY_CHUNK_SIZE = 8 * 1024

@Suppress("UnsafeCastFromDynamic")
private fun ByteArray.toUint8Array(length: Int): Uint8Array {
    val bytes = this
    return js("new Uint8Array(bytes.buffer, bytes.byteOffset, length)")
}
