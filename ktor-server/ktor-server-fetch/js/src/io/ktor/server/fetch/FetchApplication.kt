/*
 * Copyright 2014-2026 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.fetch

import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.Promise

/**
 * A lifecycle handle around an [EmbeddedServer] with [FetchApplicationEngine].
 *
 * This class is exported to JavaScript so that a generated platform wrapper can process incoming web `Request`s and
 * control the application lifecycle. It is a helper, not a ready-to-use fetch handler: the wrapper decides how
 * platform-specific arguments such as Cloudflare's `env` and `ctx` are handled.
 *
 * [handle] starts the application lazily on the first request, so a generated wrapper does not need to perform any
 * asynchronous call at module scope. This matters on runtimes such as Cloudflare Workers, where asynchronous
 * operations are only allowed inside the `fetch()` handler:
 *
 * ```kotlin
 * @JsExport
 * val application: FetchApplication = fetchApplication {
 *     module {
 *         routing {
 *             get("/") { call.respondText("Hello") }
 *         }
 *     }
 * }
 * ```
 *
 * ```javascript
 * export default {
 *   fetch(request, env, ctx) {
 *     return application.handle(request)
 *   }
 * }
 * ```
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplication)
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class FetchApplication internal constructor(
    private val server: EmbeddedServer<FetchApplicationEngine, FetchApplicationEngine.Configuration>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()
    private var startedState = false

    /**
     * Whether the application has been started, either explicitly via [start] or lazily by [handle].
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplication.started)
     */
    @JsName("started")
    public val started: Boolean get() = startedState

    /**
     * Starts the application exactly as [EmbeddedServer.startSuspend] does.
     *
     * The call is idempotent and safe to invoke concurrently with [handle]. On platforms that forbid asynchronous
     * operations outside the request handler, such as Cloudflare Workers, prefer [handle], which starts the
     * application lazily, and avoid calling this function at module scope.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplication.start)
     */
    @JsName("start")
    public fun start(): Promise<Unit> = scope.promise {
        startApplication()
    }

    /**
     * Processes a web [request] through the application, starting it first if necessary.
     *
     * This function is safe to call directly from a platform `fetch()` handler. No asynchronous initialization is
     * required at module scope.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplication.handle)
     */
    @JsName("handle")
    public fun handle(request: Request): Promise<Response> = scope.promise {
        startApplication()
        server.engine.handleFetch(request)
    }

    /**
     * Stops the application and disposes its plugins.
     *
     * Call this only on runtimes that provide a shutdown hook. Serverless runtimes without a shutdown phase, such as
     * Cloudflare Workers, do not need to call it.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.FetchApplication.stop)
     */
    @JsName("stop")
    public fun stop(): Promise<Unit> {
        val result = scope.promise {
            server.stopSuspend()
        }

        return result.then(
            {
                scope.cancel()
                Unit
            },
            { cause ->
                scope.cancel()
                throw cause
            }
        )
    }

    private suspend fun startApplication() {
        startMutex.withLock {
            if (startedState) return
            server.startSuspend()
            startedState = true
        }
    }
}

/**
 * Creates a [FetchApplication] from an application [config] and [module].
 *
 * Creating the handle is synchronous. The application starts lazily on the first [FetchApplication.handle] call, or
 * explicitly when [FetchApplication.start] is called from a request handler. This keeps generated wrappers free of
 * asynchronous initialization at module scope.
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.fetch.fetchApplication)
 *
 * @param config configuration available via `application.environment.config`.
 * @param developmentMode enables development mode for the application.
 * @param configure configures the fetch engine.
 * @param module installs application modules and plugins.
 */
public fun fetchApplication(
    config: ApplicationConfig = MapApplicationConfig(),
    developmentMode: Boolean = false,
    configure: FetchApplicationEngine.Configuration.() -> Unit = {},
    module: suspend Application.() -> Unit = {}
): FetchApplication = FetchApplication(fetchServer(config, developmentMode, configure, module))
