/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred

internal class FetchApplicationResponse(
    call: FetchApplicationCall
) : BaseApplicationResponse(call) {

    private val channel = ByteChannel(autoFlush = true)
    private val headersCommitted = CompletableDeferred<Unit>()

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        private val builder = HeadersBuilder()

        override fun engineAppendHeader(name: String, value: String) {
            builder.append(name, value)
        }

        override fun getEngineHeaderNames(): List<String> = builder.names().toList()
        override fun getEngineHeaderValues(name: String): List<String> = builder.getAll(name).orEmpty()
    }

    override fun setStatus(statusCode: HttpStatusCode) {
        // The status code is retained by [BaseApplicationResponse].
    }

    /**
     * The response body stream. It is fed by [BaseApplicationResponse] while the response is being produced.
     */
    internal val bodyChannel: ByteReadChannel get() = channel

    /**
     * Returns the channel used to write the response body.
     *
     * [BaseApplicationResponse] calls this function only after the status and headers have been committed, so it is
     * also used as the signal that a web `Response` can be created.
     */
    override suspend fun responseChannel(): ByteWriteChannel {
        headersCommitted.complete(Unit)
        return channel
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        headersCommitted.complete(Unit)
        channel.flushAndClose()
    }

    /**
     * Writes [content] to the response channel and only closes the channel successfully when the producer completes.
     *
     * The default implementation closes the channel in a `finally` block, which would make a failed streaming
     * response look like a normally finished one.
     */
    override suspend fun respondWriteChannelContent(content: OutgoingContent.WriteChannelContent) {
        headersCommitted.complete(Unit)
        try {
            content.writeTo(channel)
            channel.flushAndClose()
        } catch (cause: ClosedWriteChannelException) {
            val writeException = ChannelWriteException(exception = cause)
            channel.cancel(writeException)
            throw writeException
        } catch (cause: Throwable) {
            channel.cancel(cause)
            throw cause
        }
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        val cause = UnsupportedOperationException("Protocol upgrades are not supported by the fetch engine")
        fail(cause)
        throw cause
    }

    /**
     * Suspends until the response status and headers have been committed.
     */
    internal suspend fun awaitHeaders() {
        headersCommitted.await()
    }

    /**
     * Signals that producing the response failed.
     *
     * If the response has already started, the body stream fails with [cause]. Otherwise waiting for headers is
     * cancelled with [cause] as well.
     */
    internal fun fail(cause: Throwable) {
        headersCommitted.completeExceptionally(cause)
        channel.cancel(cause)
    }

    /**
     * Signals that the response has been produced without committing headers. This should normally not happen because
     * of the fallback response, but it prevents [awaitHeaders] from hanging.
     */
    internal fun complete() {
        if (headersCommitted.complete(Unit)) {
            channel.close()
        }
    }
}
