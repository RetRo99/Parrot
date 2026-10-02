package com.retro99.parrot.initializer

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverAuthOriginTest {

    @Test
    fun sameSchemeHostAndPortMatches() {
        assertTrue(isSameOrigin("https://books.example.com", Url("https://books.example.com/api/cover")))
        assertTrue(isSameOrigin("https://Books.Example.com/", Url("https://books.example.com:443/c")))
        assertTrue(isSameOrigin("http://192.168.1.20:8001", Url("http://192.168.1.20:8001/c")))
    }

    @Test
    fun httpsServerTokenIsNeverSentOverHttp() {
        assertFalse(isSameOrigin("https://books.example.com", Url("http://books.example.com/c")))
    }

    @Test
    fun differentPortOrHostDoesNotMatch() {
        assertFalse(isSameOrigin("https://books.example.com", Url("https://books.example.com:8443/c")))
        assertFalse(isSameOrigin("http://10.0.0.2:8001", Url("http://10.0.0.2:9000/c")))
        assertFalse(isSameOrigin("https://books.example.com", Url("https://evil.example.com/c")))
    }
}
