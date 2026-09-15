package com.lucy.storybuilder.security

import com.lucy.storybuilder.config.StoryBuilderProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Requires a valid `X-API-Key` header on every request under `/api/` (each one can spend money at the
 * image provider). Keys come from `storybuilder.security.api-keys`, e.g. the env var
 * `STORYBUILDER_SECURITY_API_KEYS=key1,key2`, and are compared in constant time. Health checks
 * (`/actuator/health`) stay open. With no keys configured, auth is off — for local development only.
 */
@Component
class ApiKeyFilter(
    props: StoryBuilderProperties,
) : OncePerRequestFilter() {
    private val keys =
        props.security.apiKeys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toByteArray() }

    init {
        if (keys.isEmpty()) {
            LoggerFactory
                .getLogger(javaClass)
                .warn("API authentication is DISABLED: set storybuilder.security.api-keys (STORYBUILDER_SECURITY_API_KEYS)")
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        keys.isEmpty() || !request.requestURI.removePrefix(request.contextPath).startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val presented = request.getHeader(HEADER)?.trim()?.toByteArray()
        if (presented != null && keys.any { MessageDigest.isEqual(it, presented) }) {
            chain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"$HEADER\"")
        response.contentType = "application/problem+json"
        val detail = if (presented == null) "Missing $HEADER header" else "Invalid API key"
        val instance = request.requestURI.replace("\\", "\\\\").replace("\"", "\\\"")
        response.writer.write(
            """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"$detail","instance":"$instance"}""",
        )
    }

    companion object {
        const val HEADER = "X-API-Key"
    }
}
