package com.lucy.storybuilder.ai

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

/** Shared HTTP setup for AI providers: JDK client, bounded connect + read timeouts. */
internal object AiHttp {
    fun client(
        baseUrl: String,
        timeout: Duration,
    ): RestClient {
        val http =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(timeout) }
        return RestClient
            .builder()
            .requestFactory(factory)
            .baseUrl(baseUrl.trimEnd('/'))
            .build()
    }

    /** A short, credential-free description of an HTTP failure for job notes and logs. */
    fun describe(
        provider: String,
        e: Exception,
    ): String =
        when (e) {
            is RestClientResponseException ->
                "$provider returned HTTP ${e.statusCode.value()}: ${e.responseBodyAsString.take(200).replace('\n', ' ')}"
            else -> "$provider call failed: ${e.javaClass.simpleName}${e.message?.let { ": ${it.take(200)}" } ?: ""}"
        }
}
