package com.retro99.login.ui.login

import com.retro99.base.server.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerAddressTest {

    @Test
    fun `normalize adds https when no scheme was typed`() {
        assertEquals("https://books.example.com", ServerAddress.normalize("books.example.com"))
        assertEquals("https://192.168.1.20:8001", ServerAddress.normalize("  192.168.1.20:8001 "))
    }

    @Test
    fun `normalize strips trailing slashes`() {
        assertEquals("https://books.retar.si", ServerAddress.normalize("https://books.retar.si/"))
        assertEquals("https://books.retar.si", ServerAddress.normalize("books.retar.si//"))
    }

    @Test
    fun `normalize keeps a typed scheme`() {
        assertEquals("http://192.168.1.20:8001", ServerAddress.normalize("http://192.168.1.20:8001"))
        assertEquals("https://books.example.com", ServerAddress.normalize("https://books.example.com"))
    }

    @Test
    fun `normalize returns empty when there is no address yet`() {
        assertEquals("", ServerAddress.normalize(""))
        assertEquals("", ServerAddress.normalize("   "))
        assertEquals("", ServerAddress.normalize("https://"))
        assertEquals("", ServerAddress.normalize("http://"))
    }

    @Test
    fun `displayHost strips scheme and path`() {
        assertEquals(
            "books.example.com:8001",
            ServerAddress.displayHost("https://books.example.com:8001/library?x=1"),
        )
    }

    @Test
    fun `displayHost keeps http scheme so plain servers stand out`() {
        assertEquals(
            "http://192.168.1.20:8001",
            ServerAddress.displayHost("http://192.168.1.20:8001/library"),
        )
        assertTrue(ServerAddress.isInsecure("http://192.168.1.20:8001"))
        assertFalse(ServerAddress.isInsecure("https://books.example.com"))
        assertTrue(ServerAddress.isValid("http://192.168.1.20:8001"))
    }

    @Test
    fun `isValid requires http or https and a host`() {
        assertTrue(ServerAddress.isValid("https://books.example.com"))
        assertTrue(ServerAddress.isValid("http://[::1]:8001"))
        assertFalse(ServerAddress.isValid("ftp://books.example.com"))
        assertFalse(ServerAddress.isValid("https://:8001"))
        assertFalse(ServerAddress.isValid("books.example.com"))
    }

    @Test
    fun `only unreachable and unsupported checks are failures`() {
        assertFalse(AddressCheck.Idle.isFailure)
        assertFalse(AddressCheck.Checking.isFailure)
        assertFalse(
            AddressCheck.Found(
                serverType = ServerType.Storyteller,
                host = "host",
                switched = false,
                supportsBrowserSignIn = false,
            ).isFailure,
        )
        assertTrue(AddressCheck.Unreachable("host").isFailure)
        assertTrue(AddressCheck.NotSupported.isFailure)
    }
}
