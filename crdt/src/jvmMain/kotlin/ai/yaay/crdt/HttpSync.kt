package ai.yaay.crdt

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream

/** One reachable endpoint suffices: an outbound client performs both exchange directions. */
public class HttpSyncServer(endpoint: SyncEndpoint, address: InetSocketAddress = InetSocketAddress("127.0.0.1", 0)) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(4)
    private val server = HttpServer.create(address, 32)
    public val address: InetSocketAddress get() = server.address
    init {
        require(!endpoint.replica.privateRoot)
        server.executor = executor
        server.createContext("/sync") { exchange ->
            exchange.use {
                try {
                    require(exchange.requestMethod == "POST" && exchange.requestURI.path == "/sync" && exchange.requestURI.rawQuery == null)
                    val body = exchange.requestBody.readNBytes(Wire.LIMIT + 1)
                    require(body.size <= Wire.LIMIT)
                    val response = endpoint.respond(body)
                    exchange.responseHeaders.set("Content-Type", "application/vnd.yaay.sync-v1")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.write(response)
                } catch (_: IllegalArgumentException) {
                    exchange.sendResponseHeaders(403, -1)
                } catch (_: Exception) {
                    exchange.sendResponseHeaders(503, -1)
                }
            }
        }
        server.start()
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
}

public class HttpExchangeConnection(
    private val uri: URI,
    private val timeout: Duration = Duration.ofSeconds(20),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(),
) : ExchangeConnection {
    init { require(uri.scheme in setOf("http", "https") && uri.userInfo == null) }
    override fun request(bytes: ByteArray): ByteArray {
        require(bytes.size <= Wire.LIMIT)
        val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/vnd.yaay.sync-v1").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        val future = client.sendAsync(request, HttpResponse.BodyHandler { BoundedBody() })
        try {
            val response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            require(response.statusCode() == 200) { "HTTP synchronization failed: ${response.statusCode()}" }
            return response.body()
        } finally { if (!future.isDone) future.cancel(true) }
    }
    private class BoundedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(Long.MAX_VALUE) }
        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (item.remaining() > Wire.LIMIT - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(ProtocolFault("Oversized HTTP body")); return
                }
                val data = ByteArray(item.remaining()); item.get(data); bytes.write(data)
            }
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }
}
