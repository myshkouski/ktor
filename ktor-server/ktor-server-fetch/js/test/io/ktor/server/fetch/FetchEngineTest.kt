/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.client.fetch.ReadableStream
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import kotlin.js.Promise
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
    fun testRespondsWithLargeBody() = runTest {
        val payload = "a".repeat(2 * 1024 * 1024)

        withServer({
            routing {
                get("/large") { call.respondText(payload) }
            }
        }) {
            val response = fetch("http://localhost/large")
            assertEquals(HttpStatusCode.OK.value, response.status.toInt())
            assertEquals(payload.length, response.text().await().length)
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

    @Test
    fun testResponseCookiesArePreserved() = runTest {
        withServer({
            routing {
                get("/cookies") {
                    call.response.cookies.append("first", "1")
                    call.response.cookies.append("second", "2")
                    call.respondText("ok")
                }
            }
        }) {
            val response = fetch("http://localhost/cookies")

            @Suppress("UnsafeCastFromDynamic")
            val cookies: Array<String> = response.headers.asDynamic().getSetCookie()
            assertEquals(2, cookies.size)
            assertTrue(cookies.any { it.startsWith("first=1") })
            assertTrue(cookies.any { it.startsWith("second=2") })
        }
    }

    @Test
    fun testDuplicateResponseHeadersArePreserved() = runTest {
        withServer({
            routing {
                get("/headers") {
                    call.response.header("X-Multi", "one")
                    call.response.headers.append("X-Multi", "two")
                    call.respondText("ok")
                }
            }
        }) {
            val response = fetch("http://localhost/headers")
            val value = response.headers.get("X-Multi").orEmpty()
            assertTrue(value.contains("one"))
            assertTrue(value.contains("two"))
        }
    }

    @Test
    fun testHopByHopResponseHeadersAreRemoved() = runTest {
        withServer({
            routing {
                get("/hop") {
                    call.response.header(HttpHeaders.Connection, "keep-alive")
                    call.response.header("Keep-Alive", "timeout=5")
                    call.respondText("ok")
                }
            }
        }) {
            val response = fetch("http://localhost/hop")
            assertNull(response.headers.get(HttpHeaders.Connection))
            assertNull(response.headers.get("Keep-Alive"))
        }
    }

    @Test
    fun testHeadResponseHasNoBody() = runTest {
        withServer({
            routing {
                get("/text") { call.respondText("hello") }
                head("/text") { call.respondText("hello") }
            }
        }) {
            val response = fetch("http://localhost/text", method = "HEAD")
            assertEquals(HttpStatusCode.OK.value, response.status.toInt())
            assertEquals("", response.text().await())
            assertEquals("5", response.headers.get(HttpHeaders.ContentLength))
        }
    }

    @Test
    fun testNoContentResponseHasNoBody() = runTest {
        withServer({
            routing {
                get("/empty") { call.respond(HttpStatusCode.NoContent) }
            }
        }) {
            val response = fetch("http://localhost/empty")
            assertEquals(HttpStatusCode.NoContent.value, response.status.toInt())
            assertEquals("", response.text().await())
        }
    }

    @Test
    fun testNotModifiedResponseHasNoBody() = runTest {
        withServer({
            routing {
                get("/cached") { call.respond(HttpStatusCode.NotModified) }
            }
        }) {
            val response = fetch("http://localhost/cached")
            assertEquals(HttpStatusCode.NotModified.value, response.status.toInt())
            assertEquals("", response.text().await())
        }
    }

    @Test
    fun testUsesRequestUrlForConnectionPoint() = runTest {
        withServer({
            routing {
                get("/point") {
                    val local = call.request.local
                    call.respondText("${local.scheme}|${local.serverHost}|${local.serverPort}|${local.remoteHost}")
                }
            }
        }) {
            val response = fetch("https://example.com:8443/point")
            assertEquals("https|example.com|8443|unknown", response.text().await())
        }
    }

    @Test
    fun testResolvesForwardedRemoteHost() = runTest {
        withServer({
            routing {
                get("/remote") { call.respondText(call.request.local.remoteHost) }
            }
        }) {
            val response = fetch(
                "http://localhost/remote",
                headers = mapOf(HttpHeaders.XForwardedFor to "203.0.113.7, 10.0.0.1")
            )
            assertEquals("203.0.113.7", response.text().await())
        }
    }

    @Test
    fun testProvidesConfiguration() = runTest {
        withServer(
            MapApplicationConfig("ktor.app.name" to "fetch"),
            block = {
                routing {
                    get("/config") {
                        call.respondText(call.application.environment.config.property("ktor.app.name").getString())
                    }
                }
            }
        ) {
            val response = fetch("http://localhost/config")
            assertEquals("fetch", response.text().await())
        }
    }

    @Test
    fun testInstallsPlugins() = runTest {
        withServer({
            install(
                createApplicationPlugin("TestHeader") {
                    onCall { call ->
                        call.response.header("X-Test-Plugin", "installed")
                    }
                }
            )
            routing {
                get("/plugin") { call.respondText("ok") }
            }
        }) {
            val response = fetch("http://localhost/plugin")
            assertEquals("installed", response.headers.get("X-Test-Plugin"))
        }
    }

    @Test
    fun testHandlesConcurrentRequests() = runTest {
        withServer({
            routing {
                get("/users/{id}") { call.respondText(call.parameters["id"]!!) }
            }
        }) {
            val responses = coroutineScope {
                (1..20).map { id ->
                    async { fetch("http://localhost/users/$id").text().await() }
                }.awaitAll()
            }

            assertEquals((1..20).map { it.toString() }, responses)
        }
    }

    @Test
    fun testResolvedConnectorsIsEmpty() = runTest {
        val server = fetchServer()
        server.startSuspend()
        try {
            assertTrue(server.engine.resolvedConnectors().isEmpty())
        } finally {
            server.stopSuspend()
        }
    }

    @Test
    fun testFetchApplicationLifecycle() = runTest {
        val application = fetchApplication {
            routing {
                get("/") { call.respondText("ok") }
            }
        }

        application.start().await()
        try {
            assertTrue(application.started)
            val response = application.handle(Request("http://localhost/")).await()
            assertEquals("ok", response.text().await())
        } finally {
            application.stop().await()
        }
    }

    @Test
    fun testHandleStartsApplicationLazily() = runTest {
        var moduleRuns = 0
        val application = fetchApplication {
            moduleRuns++
            routing {
                get("/") { call.respondText("ok") }
            }
        }

        try {
            assertFalse(application.started)
            assertEquals(0, moduleRuns)

            val responses = coroutineScope {
                (1..10).map {
                    async {
                        application.handle(Request("http://localhost/")).await().text().await()
                    }
                }.awaitAll()
            }

            assertTrue(application.started)
            assertEquals(1, moduleRuns)
            assertEquals(List(10) { "ok" }, responses)

            // Explicit start and subsequent handles must not run modules again.
            application.start().await()
            assertEquals("ok", application.handle(Request("http://localhost/")).await().text().await())
            assertEquals(1, moduleRuns)
        } finally {
            application.stop().await()
        }
    }

    @Test
    fun testStreamsRequestBody() = runTest {
        val firstChunkReceived = CompletableDeferred<String>()

        withServer({
            routing {
                post("/request-stream") {
                    val buffer = ByteArray(16)
                    val read = call.receiveChannel().readAvailable(buffer)
                    firstChunkReceived.complete(buffer.decodeToString(0, read))
                    call.respondText("received:$read")
                }
            }
        }) {
            val stream = controlledStream()
            val init = js("({})")
            init.method = "POST"
            init.body = stream.stream
            init.duplex = "half"
            val request = Request("http://localhost/request-stream", init)

            val response = async { handle(request) }
            enqueueText(stream.controller, "first")

            assertEquals("first", firstChunkReceived.await())
            assertEquals("received:5", response.await().text().await())

            stream.controller.close()
        }
    }

    @Test
    fun testStreamsResponseBody() = runTest {
        val firstChunkWritten = CompletableDeferred<Unit>()
        val releaseSecondChunk = CompletableDeferred<Unit>()

        withServer({
            routing {
                get("/stream") {
                    call.respondBytesWriter(contentType = ContentType.Text.Plain) {
                        writeString("first")
                        flush()
                        firstChunkWritten.complete(Unit)
                        releaseSecondChunk.await()
                        writeString("-second")
                    }
                }
            }
        }) {
            // handleFetch must return before the producer completes, otherwise it would never return here.
            val response = fetch("http://localhost/stream")
            firstChunkWritten.await()
            assertFalse(releaseSecondChunk.isCompleted)

            val reader = response.streamReader()
            val first = reader.read().await()
            assertFalse(first.done)
            assertEquals("first", first.value.toByteArray().decodeToString())

            releaseSecondChunk.complete(Unit)

            val second = reader.read().await()
            assertFalse(second.done)
            assertEquals("-second", second.value.toByteArray().decodeToString())

            assertTrue(reader.read().await().done)
        }
    }

    @Test
    fun testStreamsLargeResponseBody() = runTest {
        val chunk = "a".repeat(64 * 1024)
        val chunkCount = 64

        withServer({
            routing {
                get("/stream-large") {
                    call.respondBytesWriter(contentType = ContentType.Text.Plain) {
                        repeat(chunkCount) { writeString(chunk) }
                    }
                }
            }
        }) {
            val response = fetch("http://localhost/stream-large")
            assertEquals(chunk.length * chunkCount, response.text().await().length)
        }
    }

    @Test
    fun testCancellingResponseStopsProducer() = runTest {
        val producerCancelled = CompletableDeferred<Unit>()

        withServer({
            routing {
                get("/stream-cancel") {
                    call.respondBytesWriter(contentType = ContentType.Text.Plain) {
                        try {
                            writeString("started")
                            flush()
                            while (true) {
                                delay(10)
                                writeString("more")
                                flush()
                            }
                        } finally {
                            producerCancelled.complete(Unit)
                        }
                    }
                }
            }
        }) {
            val response = fetch("http://localhost/stream-cancel")
            val reader = response.streamReader()
            val first = reader.read().await()
            assertEquals("started", first.value.toByteArray().decodeToString())

            reader.cancel("stop").await()
            producerCancelled.await()
        }
    }

    @Test
    fun testStreamingErrorFailsResponseBody() = runTest {
        withServer({
            routing {
                get("/stream-fail") {
                    call.respondBytesWriter(contentType = ContentType.Text.Plain) {
                        writeString("partial")
                        flush()
                        throw IllegalStateException("boom")
                    }
                }
            }
        }) {
            val response = fetch("http://localhost/stream-fail")
            val reader = response.streamReader()
            assertFailsWith<Throwable> {
                do {
                    val chunk = reader.read().await()
                } while (!chunk.done)
            }
        }
    }
}

private class TestServer(
    private val server: EmbeddedServer<FetchApplicationEngine, FetchApplicationEngine.Configuration>
) {

    suspend fun handle(request: Request): Response = server.engine.handleFetch(request)

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
        val request = Request(url, init)
        return server.engine.handleFetch(request)
    }
}

private suspend fun withServer(
    block: suspend Application.() -> Unit,
    test: suspend TestServer.() -> Unit
) {
    withServer(MapApplicationConfig(), block, test)
}

private suspend fun withServer(
    config: ApplicationConfig,
    block: suspend Application.() -> Unit,
    test: suspend TestServer.() -> Unit
) {
    val server = fetchServer(config, module = block)
    server.startSuspend()
    try {
        TestServer(server).test()
    } finally {
        server.stopSuspend()
    }
}

private external interface TestReadableStream {
    fun getReader(): TestReadableStreamReader
}

private external interface TestReadableStreamReader {
    fun read(): Promise<TestReadResult>
    fun cancel(reason: Any? = definedExternally): Promise<Unit>
}

private external interface TestReadResult {
    val done: Boolean
    val value: Uint8Array
}

@Suppress("UnsafeCastFromDynamic")
private fun Response.streamReader(): TestReadableStreamReader =
    asDynamic().body.unsafeCast<TestReadableStream>().getReader()

@Suppress("UnsafeCastFromDynamic")
private fun Uint8Array.toByteArray(): ByteArray = Int8Array(buffer, byteOffset, length).asDynamic()

private class ControlledStream(val stream: dynamic, val controller: dynamic)

@Suppress("UnsafeCastFromDynamic")
private fun controlledStream(): ControlledStream {
    val holder = js("({})")
    val source = js("({})")
    source.start = { controller: dynamic -> holder.controller = controller }
    return ControlledStream(js("new ReadableStream(source)"), holder.controller)
}

@Suppress("UnsafeCastFromDynamic")
private fun enqueueText(controller: dynamic, text: String) {
    controller.enqueue(Uint8Array(text.encodeToByteArray().toTypedArray()))
}
