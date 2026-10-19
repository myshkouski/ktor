/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import kotlin.coroutines.CoroutineContext
import kotlin.js.Promise

/**
 * A Ktor [ApplicationEngine] that turns an [Application] into a web-standard
 * [fetch](https://developer.mozilla.org/en-US/docs/Web/API/fetch) handler instead of listening on a socket.
 *
 * The engine itself does not start any server. Prepare an application with [embeddedServer] and start it, then use
 * [fetch] to handle incoming web `Request`s:
 *
 * ```kotlin
 * val server = embeddedServer(Fetch) {
 *     routing {
 *         get("/") { call.respondText("Hello") }
 *     }
 * }
 * server.startSuspend()
 * val handler = server.engine.fetch
 * ```
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplicationEngine)
 */
public open class FetchApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    public val configuration: Configuration = Configuration(),
    private val applicationProvider: () -> Application
) : BaseApplicationEngine(environment, monitor, developmentMode), CoroutineScope {

    /**
     * Configuration for the [FetchApplicationEngine].
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplicationEngine.Configuration)
     */
    public class Configuration : BaseApplicationEngine.Configuration()

    /**
     * The application served by this engine.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplicationEngine.application)
     */
    public val application: Application get() = applicationProvider()

    override val coroutineContext: CoroutineContext get() = applicationProvider().coroutineContext

    /**
     * The handler that processes web `Request`s through the prepared [Application].
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplicationEngine.fetch)
     */
    internal suspend fun handleFetch(request: Request): Response {
        val application = application
        val call = request.toApplicationCall(application, currentCoroutineContext())
        pipeline.execute(call)
        return call.toWebResponse()
    }

    override suspend fun startSuspend(wait: Boolean): ApplicationEngine = this

    override fun start(wait: Boolean): ApplicationEngine = this

    override suspend fun stopSuspend(gracePeriodMillis: Long, timeoutMillis: Long) {}

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {}
}

/**
 * An engine factory for [FetchApplicationEngine].
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.Fetch)
 */
public object Fetch : ApplicationEngineFactory<FetchApplicationEngine, FetchApplicationEngine.Configuration> {
    override fun configuration(
        configure: FetchApplicationEngine.Configuration.() -> Unit
    ): FetchApplicationEngine.Configuration = FetchApplicationEngine.Configuration().apply(configure)

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: FetchApplicationEngine.Configuration,
        applicationProvider: () -> Application
    ): FetchApplicationEngine =
        FetchApplicationEngine(environment, monitor, developmentMode, configuration, applicationProvider)
}

public typealias FetchHandler = (request: Request) -> Promise<Response>

public fun <
    TEngine: FetchApplicationEngine,
    TConfiguration: ApplicationEngine.Configuration
> EmbeddedServer<
    TEngine,
    TConfiguration
>.toFetchHandler(): FetchHandler {
    return { request ->
        engine.promise {
            engine.handleFetch(request)
        }
    }
}
