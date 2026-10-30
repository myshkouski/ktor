/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.utils.io.*

internal class FetchApplicationRequest(
    call: FetchApplicationCall,
    private val requestMethod: HttpMethod,
    private val requestUri: String,
    private val httpVersion: String,
    private val scheme: String,
    private val host: String,
    private val port: Int,
    private val requestHeaders: Headers,
    private val body: ByteReadChannel
) : BaseApplicationRequest(call) {

    override val engineHeaders: Headers get() = requestHeaders

    override val engineReceiveChannel: ByteReadChannel get() = body

    private val remoteHost: String
        get() = requestHeaders["CF-Connecting-IP"]
            ?: requestHeaders["X-Real-IP"]
            ?: requestHeaders[HttpHeaders.XForwardedFor]
                ?.substringBefore(',')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "unknown"

    override val local: RequestConnectionPoint = object : RequestConnectionPoint {
        override val uri: String get() = requestUri
        override val method: HttpMethod get() = requestMethod
        override val scheme: String get() = this@FetchApplicationRequest.scheme
        override val version: String get() = httpVersion

        @Deprecated(
            message = "Use localPort or serverPort instead",
            level = DeprecationLevel.ERROR,
            replaceWith = ReplaceWith("localPort")
        )
        override val port: Int get() = localPort

        @Deprecated(
            message = "Use localHost or serverHost instead",
            level = DeprecationLevel.ERROR,
            replaceWith = ReplaceWith("localHost")
        )
        override val host: String get() = localHost

        override val localPort: Int get() = this@FetchApplicationRequest.port
        override val serverPort: Int get() = this@FetchApplicationRequest.port
        override val localHost: String get() = serverHost
        override val serverHost: String get() = this@FetchApplicationRequest.host.ifEmpty { "localhost" }
        override val localAddress: String get() = localHost
        override val remoteHost: String get() = this@FetchApplicationRequest.remoteHost
        override val remotePort: Int get() = 0
        override val remoteAddress: String get() = remoteHost

        override fun toString(): String =
            "FetchConnectionPoint(uri=$uri, method=$method, version=$version, " +
                "localPort=$localPort, serverHost=$serverHost)"
    }

    @OptIn(InternalAPI::class)
    override val queryParameters: Parameters by lazy {
        encodeParameters(rawQueryParameters).withEmptyStringForValuelessKeys()
    }

    override val rawQueryParameters: Parameters by lazy {
        parseQueryString(queryString(), decode = false)
    }

    override val cookies: RequestCookies = RequestCookies(this)

    internal fun cancelBody() {
        body.cancel(null)
    }
}
