/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.utils.io.*

internal class FetchApplicationResponse(
    call: FetchApplicationCall
) : BaseApplicationResponse(call) {

    private val channel = ByteChannel(autoFlush = true)

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

    override suspend fun responseChannel(): ByteWriteChannel = channel

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        channel.flushAndClose()
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        throw UnsupportedOperationException("Protocol upgrades are not supported by the fetch engine")
    }

    internal suspend fun bodyBytes(): ByteArray = channel.toByteArray()
}
