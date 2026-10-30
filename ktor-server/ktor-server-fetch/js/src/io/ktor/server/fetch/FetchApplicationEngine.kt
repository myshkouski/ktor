/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.util.Attributes
import io.ktor.util.pipeline.*
import io.ktor.util.putAll
import kotlinx.coroutines.*
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import kotlin.coroutines.CoroutineContext

/**
 * A Ktor [ApplicationEngine] that turns an [Application] into a web-standard
 * [fetch](https://developer.mozilla.org/en-US/docs/Web/API/fetch) handler instead of listening on a socket.
 *
 * The engine itself does not start any server and does not manage the application lifecycle. It exposes
 * [handleFetch] so that a platform-specific wrapper can convert incoming web `Request`s into [Response]s:
 *
 * ```kotlin
 * val server = fetchServer {
 *     routing {
 *         get("/") { call.respondText("Hello") }
 *     }
 * }
 * server.startSuspend()
 * val response = server.engine.handleFetch(request)
 * ```
 *
 * On serverless runtimes that forbid asynchronous work outside the request handler, such as Cloudflare Workers, use
 * [FetchApplication] instead: [FetchApplication.handle] starts the application lazily from inside the handler.
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
     * Processes a web [request] through the prepared [Application] and returns the corresponding web [Response].
     *
     * The application must be started before this function is called. The function returns as soon as the response
     * status and headers are available: the body is exposed as a [org.w3c.fetch.Response] `ReadableStream` that is fed
     * from the application while the platform runtime consumes it. Use [FetchApplication.handle] from a generated
     * platform wrapper to manage the application lifecycle.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplicationEngine.handleFetch)
     */
    public suspend fun handleFetch(request: Request, attributes: Attributes? = null): Response {
        val requestJob = SupervisorJob(application.coroutineContext[Job])
        val requestScope = CoroutineScope(application.coroutineContext + requestJob)
        val call = request.toApplicationCall(application, requestScope.coroutineContext)
        attributes?.also { call.attributes.putAll(it) }
        val response = call.response

        // Start the pipeline in the current event-loop turn instead of waiting for a dispatch: on a fetch
        // runtime there is nothing to wait for, and an extra hop is visible as time to first byte.
        val pipelineJob = requestScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                pipeline.execute(call)
            } catch (cause: CancellationException) {
                response.fail(cause)
                throw cause
            } catch (cause: Throwable) {
                response.fail(cause)
            } finally {
                call.cancelRequestBody()
            }
            response.complete()
        }

        // Covers cancellation that happens before the pipeline body starts executing.
        pipelineJob.invokeOnCompletion { cause -> cause?.let { response.fail(it) } }

        try {
            response.awaitHeaders()
        } catch (cause: Throwable) {
            requestJob.cancel()
            throw cause
        }

        return call.toWebResponse(requestScope, response.bodyChannel, requestJob)
    }

    override suspend fun startSuspend(wait: Boolean): ApplicationEngine {
        resolvedConnectorsDeferred.complete(emptyList())
        return this
    }

    override fun start(wait: Boolean): ApplicationEngine {
        resolvedConnectorsDeferred.complete(emptyList())
        return this
    }

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

/**
 * Creates an [EmbeddedServer] that serves the application through [FetchApplicationEngine].
 *
 * This helper prepares the [ApplicationEnvironment] with the provided [config] and installs [module]. The caller is
 * responsible for the lifecycle of the returned server: start it once per isolate and stop it when the platform
 * allows graceful shutdown.
 *
 * On serverless runtimes that forbid asynchronous work outside the request handler, such as Cloudflare Workers, use
 * [fetchApplication] instead. Its [FetchApplication.handle] starts the application lazily from inside the handler.
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.fetchServer)
 *
 * @param config configuration available via `application.environment.config`.
 * @param developmentMode enables development mode for the application.
 * @param configure configures the fetch engine.
 * @param module installs application modules and plugins.
 */
public fun fetchServer(
    config: ApplicationConfig = MapApplicationConfig(),
    developmentMode: Boolean = false,
    configure: FetchApplicationEngine.Configuration.() -> Unit = {},
    module: suspend Application.() -> Unit = {}
): EmbeddedServer<FetchApplicationEngine, FetchApplicationEngine.Configuration> {
    val environment = applicationEnvironment { this.config = config }
    val rootConfig = serverConfig(environment) {
        this.developmentMode = developmentMode
        this.module(module)
    }

    return embeddedServer(Fetch, rootConfig, configure)
}
