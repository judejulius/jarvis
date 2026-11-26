package com.jarvis.assistant.agent

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.jarvis.assistant.ai.Endpoints
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** SDK transport for a single session. The messages API is the only endpoint Jarvis uses. */
internal class AnthropicTransport(private val key: String, client: OkHttpClient = defaultClient) : HttpClient {
    private val http = client.newBuilder().followRedirects(false).build()
    private val cancelled = AtomicBoolean()
    private val active = AtomicReference<Call?>()

    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        val url = (request.baseUrl ?: ORIGIN).toHttpUrl().newBuilder()
        request.pathSegments.forEach(url::addPathSegment)
        request.queryParams.keys().forEach { name ->
            request.queryParams.values(name).forEach { url.addQueryParameter(name, it) }
        }
        val address = url.build()
        require(Endpoints.sameOrigin(address.toString(), ORIGIN)) { "Unexpected Claude endpoint." }
        val builder = Request.Builder().url(address)
        request.headers.names().forEach { name -> request.headers.values(name).forEach { builder.addHeader(name, it) } }
        builder.header("x-api-key", key)
        if (request.headers.values("anthropic-version").isEmpty()) builder.header("anthropic-version", "2023-06-01")
        val body = request.body?.let { source ->
            object : RequestBody() {
                override fun contentType() = source.contentType()?.toMediaTypeOrNull()
                override fun contentLength() = source.contentLength()
                override fun writeTo(sink: BufferedSink) = source.writeTo(sink.outputStream())
            }
        }
        val call = http.newCall(builder.method(request.method.name, body).build())
        active.set(call)
        if (cancelled.get()) call.cancel()
        val response = try { call.execute() } finally { request.body?.close() }
        return object : HttpResponse {
            override fun statusCode() = response.code
            override fun headers() = Headers.builder().apply {
                for ((name, value) in response.headers) put(name, value)
            }.build()
            override fun body() = response.body?.byteStream() ?: throw IOException("Empty Claude response")
            override fun close() { response.close(); active.compareAndSet(call, null) }
        }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> =
        CompletableFuture.supplyAsync { execute(request, requestOptions) }

    override fun close() {
        cancelled.set(true)
        active.getAndSet(null)?.cancel()
    }

    companion object {
        const val ORIGIN = "https://api.anthropic.com"
        private val defaultClient = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    }
}
