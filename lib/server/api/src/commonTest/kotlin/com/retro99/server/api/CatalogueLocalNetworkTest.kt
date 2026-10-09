package com.retro99.server.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogueLocalNetworkTest {
    private val gutenberg = "https://www.gutenberg.org/ebooks/search.opds/"

    @Test fun a_link_from_a_public_catalogue_to_a_local_device_names_its_host() {
        mapOf(
            "http://192.168.1.20/opds" to "192.168.1.20",
            "https://10.0.0.7:8443/feed?x=1" to "10.0.0.7",
            "http://172.16.4.4/" to "172.16.4.4",
            "http://169.254.1.1/" to "169.254.1.1",
            "http://127.0.0.1:8080/" to "127.0.0.1",
            "http://localhost/opds" to "localhost",
            "http://nas.local/opds" to "nas.local",
            "http://Books.Home.LAN/opds" to "books.home.lan",
            "http://[::1]/opds" to "[::1]",
            "http://[fd12:3456::1]:8080/opds" to "[fd12:3456::1]",
            "http://[fe80::1]/opds" to "[fe80::1]",
            "http://[::ffff:192.168.1.20]/opds" to "[::ffff:192.168.1.20]",
            "http://reader:secret@192.168.1.20/opds" to "192.168.1.20",
        ).forEach { (link, host) -> assertEquals(host, localNetworkHostLeaving(gutenberg, link), link) }
    }

    @Test fun public_addresses_and_other_schemes_are_not_asked_about() {
        listOf(
            "https://www.gutenberg.org/ebooks/1342.opds",
            "https://cdn.example.org/feed",
            "http://172.32.0.1/",
            "http://192.169.1.1/",
            "http://[2001:db8::1]/",
            "http://[::ffff:8.8.8.8]/",
            "file:///etc/hosts",
            "data:image/png;base64,AAAA",
            "/ebooks/1342.opds",
            "",
        ).forEach { link -> assertNull(localNetworkHostLeaving(gutenberg, link), link) }
    }

    @Test fun a_local_catalogue_links_to_itself_freely_but_not_to_another_device_or_port() {
        val home = "http://books.home.lan:8080/opds"
        assertNull(localNetworkHostLeaving(home, "http://books.home.lan:8080/opds/fiction"))
        assertNull(localNetworkHostLeaving(home, "http://BOOKS.home.lan:8080/opds/fiction"))
        assertEquals("books.home.lan", localNetworkHostLeaving(home, "http://books.home.lan/opds"))
        assertEquals("books.home.lan", localNetworkHostLeaving(home, "https://books.home.lan:8080/opds"))
        assertEquals("192.168.1.20", localNetworkHostLeaving(home, "http://192.168.1.20:8080/opds"))
        assertNull(localNetworkHostLeaving("https://192.168.1.20/opds", "https://192.168.1.20:443/opds/all"))
    }
}
