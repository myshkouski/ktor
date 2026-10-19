/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.utils.io.*
import kotlin.coroutines.CoroutineContext

internal class FetchApplicationCall(
    application: Application,
    override val coroutineContext: CoroutineContext,
    method: HttpMethod,
    uri: String,
    version: String,
    scheme: String,
    headers: Headers,
    body: ByteReadChannel
) : BaseApplicationCall(application) {

    override val request: FetchApplicationRequest =
        FetchApplicationRequest(this, method, uri, version, scheme, headers, body)

    override val response: FetchApplicationResponse = FetchApplicationResponse(this)

    init {
        putResponseAttribute()
    }
}
