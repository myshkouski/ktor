/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import kotlin.test.*

class FetchEngineTest {

    @Test
    fun testRespondsWithText() = runTest {
        withServer({
            routing {
                get("/") { call.respondText("Hello, fetch!") }
            }
        }) {
            val response = fetch("http://localhost/")
            assertEquals(HttpStatusCode.OK.value, response.status.toInt())
            assertEquals("Hello, fetch!", response.text().await())
            assertEquals("text/plain; charset=UTF-8", response.headers.get(HttpHeaders.ContentType))
        }
    }

    @Test
    fun testRoutesByPath() = runTest {
        withServer({
            routing {
                get("/users/{id}") { call.respondText(call.parameters["id"]!!) }
            }
        }) {
            val response = fetch("http://localhost/users/42")
            assertEquals(HttpStatusCode.OK.value, response.status.toInt())
            assertEquals("42", response.text().await())
        }
    }

    @Test
    fun testParsesQueryParameters() = runTest {
        withServer({
            routing {
                get("/search") { call.respondText(call.request.queryParameters["q"] ?: "none") }
            }
        }) {
            val response = fetch("http://localhost/search?q=ktor")
            assertEquals("ktor", response.text().await())
        }
    }

    @Test
    fun testReceivesRequestBody() = runTest {
        withServer({
            routing {
                post("/echo") { call.respondText(call.receiveText()) }
            }
        }) {
            val response = fetch("http://localhost/echo", method = "POST", body = "payload")
            assertEquals(HttpStatusCode.OK.value, response.status.toInt())
            assertEquals("payload", response.text().await())
        }
    }

    @Test
    fun testStreamsLargeRequestBody() = runTest {
        withServer({
            routing {
                post("/length") { call.respondText(call.receiveText().length.toString()) }
            }
        }) {
            val response = fetch("http://localhost/length", method = "POST", body = "a".repeat(2 * 1024 * 1024))
            assertEquals(HttpStatusCode.OK.value, response.status.toInt())
            assertEquals((2 * 1024 * 1024).toString(), response.text().await())
        }
    }

    @Test
    fun testIgnoresUnconsumedRequestBody() = runTest {
        withServer({
            routing {
                post("/ignore") { call.respondText("ok") }
            }
        }) {
            val response = fetch("http://localhost/ignore", method = "POST", body = "a".repeat(1024 * 1024))
            assertEquals(HttpStatusCode.OK.value, response.status.toInt())
            assertEquals("ok", response.text().await())
        }
    }

    @Test
    fun testRespondsNotFoundForUnknownRoute() = runTest {
        withServer({
            routing {
                get("/known") { call.respondText("known") }
            }
        }) {
            val response = fetch("http://localhost/unknown")
            assertEquals(HttpStatusCode.NotFound.value, response.status.toInt())
        }
    }

    @Test
    fun testPassesRequestHeaders() = runTest {
        withServer({
            routing {
                get("/header") { call.respondText(call.request.headers["X-Test"] ?: "missing") }
            }
        }) {
            val response = fetch("http://localhost/header", headers = mapOf("X-Test" to "value"))
            assertEquals("value", response.text().await())
        }
    }
}

private class TestServer(
    private val server: EmbeddedServer<FetchApplicationEngine, FetchApplicationEngine.Configuration>
) {

    @Suppress("UnsafeCastFromDynamic")
    suspend fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val init = js("({})")
        init.method = method
        if (body != null) init.body = body
        if (headers.isNotEmpty()) {
            val jsHeaders = js("({})")
            for ((name, value) in headers) jsHeaders[name] = value
            init.headers = jsHeaders
        }
        val request: Request = js("new Request(url, init)")
        return server.engine.fetch.fetch(request).await()
    }
}

private suspend fun withServer(
    block: Application.() -> Unit,
    test: suspend TestServer.() -> Unit
) {
    val server = embeddedServer(Fetch, module = block)
    server.startSuspend()
    try {
        TestServer(server).test()
    } finally {
        server.stopSuspend()
    }
}
