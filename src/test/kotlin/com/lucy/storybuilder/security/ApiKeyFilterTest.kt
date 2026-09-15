package com.lucy.storybuilder.security

import com.lucy.storybuilder.config.StoryBuilderProperties
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyFilterTest {
    private val filter = ApiKeyFilter(StoryBuilderProperties(security = StoryBuilderProperties.Security(listOf("alpha", " beta "))))

    private fun call(
        path: String,
        key: String? = null,
        f: ApiKeyFilter = filter,
    ): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("GET", path).apply { key?.let { addHeader("X-API-Key", it) } }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        f.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `api requests need a valid key`() {
        val (missing, missingChain) = call("/api/v1/skills")
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, missing.status)
        assertNull(missingChain.request, "request must not reach the controller")
        assertEquals("application/problem+json", missing.contentType)
        assertTrue("Missing X-API-Key" in missing.contentAsString)
        assertNotNull(missing.getHeader("WWW-Authenticate"))

        val (wrong, _) = call("/api/v1/skills", "gamma")
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, wrong.status)
        assertTrue("Invalid API key" in wrong.contentAsString)
    }

    @Test
    fun `any configured key is accepted, trimmed`() {
        listOf("alpha", "beta").forEach { key ->
            val (response, chain) = call("/api/v1/stories", key)
            assertEquals(200, response.status)
            assertNotNull(chain.request, "key $key should pass")
        }
    }

    @Test
    fun `health and non-api paths stay open`() {
        assertNotNull(call("/actuator/health").second.request)
        assertNotNull(call("/").second.request)
    }

    @Test
    fun `no configured keys disables auth`() {
        val open = ApiKeyFilter(StoryBuilderProperties())
        assertNotNull(call("/api/v1/skills", f = open).second.request)
    }
}
