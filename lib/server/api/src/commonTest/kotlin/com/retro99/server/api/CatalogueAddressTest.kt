package com.retro99.server.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CATALOGUE_PROMPT §4 (settings address), §9 B6 (rows) and plan §10.6 (logs). */
class CatalogueAddressTest {
    private val m = "••••••••"
    private val uuid = "3f2a9c1e-7b4d-4e2a-9c1e-5d6f7a8b9c0d"
    private val base62 = "aB3dEfGh1jKlMn0pQrStUv"
    private val hex = "deadbeefcafebabefacefeed"

    @Test fun settings_address_masks_what_looks_like_a_key() {
        listOf(
            // Nothing to hide: unchanged, character for character.
            "https://books.home.lan/opds" to "https://books.home.lan/opds",
            "https://books.home.lan/opds/" to "https://books.home.lan/opds/",
            "https://gutenberg.org/ebooks.opds/" to "https://gutenberg.org/ebooks.opds/",
            "http://192.168.1.20:8080/opds/v1.2/catalog" to "http://192.168.1.20:8080/opds/v1.2/catalog",
            "https://books.home.lan/opds?page=2&sort=title" to "https://books.home.lan/opds?page=2&sort=title",
            "https://books.home.lan" to "https://books.home.lan",
            // Named query values.
            "https://books.home.lan/opds?apikey=abc" to "https://books.home.lan/opds?apikey=$m",
            "https://books.home.lan/opds?token=abc&page=2" to "https://books.home.lan/opds?token=$m&page=2",
            "https://books.home.lan/opds?page=2&key=abc" to "https://books.home.lan/opds?page=2&key=$m",
            "https://books.home.lan/opds?auth=abc" to "https://books.home.lan/opds?auth=$m",
            "https://books.home.lan/opds?password=hunter2" to "https://books.home.lan/opds?password=$m",
            "https://books.home.lan/opds?APIKEY=abc" to "https://books.home.lan/opds?APIKEY=$m",
            "https://books.home.lan/opds?api_key=abc" to "https://books.home.lan/opds?api_key=$m",
            "https://books.home.lan/opds?api-key=abc" to "https://books.home.lan/opds?api-key=$m",
            "https://books.home.lan/opds?access_token=abc" to "https://books.home.lan/opds?access_token=$m",
            "https://books.home.lan/opds?token=abc#top" to "https://books.home.lan/opds?token=$m#top",
            // A name alone does not make a neighbour secret, and an empty value has nothing to hide.
            "https://books.home.lan/opds?keyword=dune" to "https://books.home.lan/opds?keyword=dune",
            "https://books.home.lan/opds?author=herbert" to "https://books.home.lan/opds?author=herbert",
            "https://books.home.lan/opds?token=" to "https://books.home.lan/opds?token=",
            "https://books.home.lan/opds?token" to "https://books.home.lan/opds?token",
            // A long random-looking query value under any name.
            "https://books.home.lan/opds?u=$uuid" to "https://books.home.lan/opds?u=$m",
            "https://books.home.lan/opds?sig=$base62&page=1" to "https://books.home.lan/opds?sig=$m&page=1",
            // Path parts: Kavita-style keys.
            "https://kavita.home.lan/api/opds/$uuid" to "https://kavita.home.lan/api/opds/$m",
            "https://kavita.home.lan/api/opds/$uuid/" to "https://kavita.home.lan/api/opds/$m/",
            "https://kavita.home.lan/api/opds/$uuid/series/12" to "https://kavita.home.lan/api/opds/$m/series/12",
            "https://books.home.lan/$hex/opds" to "https://books.home.lan/$m/opds",
            "https://books.home.lan/$base62/opds" to "https://books.home.lan/$m/opds",
            "https://books.home.lan/opds/key/abc123" to "https://books.home.lan/opds/key/$m",
            "https://books.home.lan/apikey/abc123/opds" to "https://books.home.lan/apikey/$m/opds",
            "https://books.home.lan/token/abc123/opds?token=x" to "https://books.home.lan/token/$m/opds?token=$m",
            // Long, but words: not a key.
            "https://books.home.lan/the-adventures-of-sherlock-holmes" to "https://books.home.lan/the-adventures-of-sherlock-holmes",
            "https://books.home.lan/pride-and-prejudice-1813-illustrated" to "https://books.home.lan/pride-and-prejudice-1813-illustrated",
            "https://books.home.lan/TheAdventuresOfSherlockHolmes" to "https://books.home.lan/TheAdventuresOfSherlockHolmes",
            "https://books.home.lan/opds?q=the+adventures+of+sherlock+holmes" to "https://books.home.lan/opds?q=the+adventures+of+sherlock+holmes",
            // Short and random is under the 20-character line.
            "https://books.home.lan/a1b2c3d4e5/opds" to "https://books.home.lan/a1b2c3d4e5/opds",
            // The host is never a key, however it looks.
            "https://$hex.example/opds" to "https://$hex.example/opds",
            // A password typed into the address.
            "https://reader:hunter2@books.home.lan/opds" to "https://reader:$m@books.home.lan/opds",
            // Typed without a scheme.
            "books.home.lan/opds?apikey=abc" to "books.home.lan/opds?apikey=$m",
            "" to "",
        ).forEach { (address, shown) -> assertEquals(shown, maskAddress(address), address) }
    }

    @Test fun show_is_offered_only_when_something_is_masked() {
        assertTrue(addressHasKey("https://books.home.lan/opds?apikey=abc"))
        assertTrue(addressHasKey("https://kavita.home.lan/api/opds/$uuid"))
        assertFalse(addressHasKey("https://books.home.lan/opds"))
        assertFalse(addressHasKey("https://books.home.lan/opds?page=2"))
    }

    private fun catalogue(id: String, address: String) = ServerConfig(id, id, ServerType.Opds, address, 0)
    private fun rows(vararg addresses: String): List<String> {
        val sources = addresses.mapIndexed { index, address -> catalogue("c$index", address) }
        return sources.map { rowAddress(it, sources) }
    }

    @Test fun a_row_shows_the_host_only() {
        listOf(
            "https://books.home.lan/opds" to "books.home.lan",
            "https://gutenberg.org/ebooks.opds/" to "gutenberg.org",
            "https://Books.Home.LAN:8443/opds?apikey=abc" to "books.home.lan",
            "https://kavita.home.lan/api/opds/$uuid" to "kavita.home.lan",
            "http://192.168.1.20:8080/opds" to "192.168.1.20",
            "http://[fd00::1]:8080/opds" to "[fd00::1]",
            "https://reader:hunter2@books.home.lan/opds" to "books.home.lan",
            "books.home.lan/opds" to "books.home.lan",
            "not an address" to "",
            "" to "",
        ).forEach { (address, shown) -> assertEquals(shown, rows(address).single(), address) }
    }

    @Test fun different_hosts_stay_host_only() {
        assertEquals(listOf("books.home.lan", "gutenberg.org"), rows("https://books.home.lan/opds", "https://gutenberg.org/ebooks.opds/"))
    }

    @Test fun two_catalogues_on_one_host_show_host_and_path() {
        assertEquals(
            listOf("books.home.lan/opds/fiction", "books.home.lan/opds/comics"),
            rows("https://books.home.lan/opds/fiction", "https://books.home.lan/opds/comics"),
        )
        // Trailing slashes, the scheme, the port and the query string are not part of a row.
        assertEquals(
            listOf("books.home.lan/opds/fiction", "books.home.lan/opds/comics"),
            rows("https://books.home.lan/opds/fiction/?apikey=abc&page=2", "http://BOOKS.home.lan:8080/opds/comics#top"),
        )
        // A third catalogue elsewhere is not affected.
        assertEquals(
            listOf("books.home.lan/a", "books.home.lan/b", "gutenberg.org"),
            rows("https://books.home.lan/a", "https://books.home.lan/b", "https://gutenberg.org/ebooks.opds/"),
        )
        // One of them at the top of the host.
        assertEquals(listOf("books.home.lan", "books.home.lan/opds"), rows("https://books.home.lan/", "https://books.home.lan/opds"))
    }

    @Test fun a_key_in_a_row_path_is_masked_with_four_dots() {
        assertEquals(
            listOf("kavita.home.lan/api/opds/••••", "kavita.home.lan/opds"),
            rows("https://kavita.home.lan/api/opds/$uuid", "https://kavita.home.lan/opds"),
        )
        assertEquals(
            listOf("books.home.lan/key/••••/fiction", "books.home.lan/key/••••/comics"),
            rows("https://books.home.lan/key/abc123/fiction", "https://books.home.lan/key/abc123/comics"),
        )
    }

    @Test fun the_same_host_and_path_adds_the_port() {
        assertEquals(
            listOf("books.home.lan:8080/opds", "books.home.lan:8081/opds"),
            rows("http://books.home.lan:8080/opds", "http://books.home.lan:8081/opds"),
        )
        // No port in the address means the scheme's own.
        assertEquals(
            listOf("books.home.lan:443/opds", "books.home.lan:80/opds"),
            rows("https://books.home.lan/opds", "http://books.home.lan/opds"),
        )
        // Only the pair that collides gets ports.
        assertEquals(
            listOf("books.home.lan:8080/opds", "books.home.lan:8081/opds", "books.home.lan/comics"),
            rows("http://books.home.lan:8080/opds", "http://books.home.lan:8081/opds", "http://books.home.lan:8080/comics"),
        )
    }

    @Test fun still_the_same_goes_back_to_the_host() {
        // Two keys on one server: the masked paths and the ports are equal.
        assertEquals(
            listOf("kavita.home.lan", "kavita.home.lan"),
            rows("https://kavita.home.lan/api/opds/$uuid", "https://kavita.home.lan/api/opds/$hex"),
        )
        // The very same address twice, or differing in the query string only.
        assertEquals(listOf("books.home.lan", "books.home.lan"), rows("https://books.home.lan/opds", "https://books.home.lan/opds/"))
        assertEquals(listOf("books.home.lan", "books.home.lan"), rows("https://books.home.lan/opds?lib=1", "https://books.home.lan/opds?lib=2"))
    }

    @Test fun only_other_catalogues_count() {
        val catalogue = catalogue("c", "https://books.home.lan/opds")
        val library = ServerConfig("s", "Library", ServerType.Storyteller, "https://books.home.lan", 0)
        assertEquals("books.home.lan", rowAddress(catalogue, listOf(catalogue, library)))
        // The catalogue itself in the list, or twice, is not "another catalogue".
        assertEquals("books.home.lan", rowAddress(catalogue, listOf(catalogue, catalogue.copy())))
        // A turned-off catalogue still has a row, so it still counts.
        val off = catalogue("off", "https://books.home.lan/comics").copy(enabled = false)
        assertEquals("books.home.lan/opds", rowAddress(catalogue, listOf(catalogue, off)))
        assertEquals("books.home.lan/opds", rowAddress(catalogue, listOf(off)))
    }

    @Test fun a_row_never_shows_a_query_string_or_a_key() {
        val secretive = listOf(
            "https://books.home.lan/opds?apikey=SECRETSECRET",
            "https://books.home.lan/opds/$uuid?token=SECRETSECRET",
            "https://books.home.lan:8443/key/SECRETSECRET/opds",
            "https://reader:SECRETSECRET@books.home.lan/other",
        )
        rows(*secretive.toTypedArray()).forEach { row ->
            assertFalse("?" in row || "SECRET" in row || uuid in row || "@" in row, row)
        }
    }

    @Test fun logs_get_the_address_without_anything_that_could_be_a_key() {
        val r = "[redacted]"
        listOf(
            "https://books.home.lan/opds" to "https://books.home.lan/opds",
            "https://books.home.lan:8443/opds/" to "https://books.home.lan:8443/opds/",
            // Every query value goes, named or not: a log line is not worth the guess.
            "https://books.home.lan/opds?apikey=abc&page=2" to "https://books.home.lan/opds?apikey=$r&page=$r",
            "https://books.home.lan/opds?q=the+name+of+a+book" to "https://books.home.lan/opds?q=$r",
            "https://kavita.home.lan/api/opds/$uuid/series/12" to "https://kavita.home.lan/api/opds/$r/series/12",
            "https://books.home.lan/key/abc123/opds" to "https://books.home.lan/key/$r/opds",
            "https://reader:hunter2@books.home.lan/opds#frag" to "https://books.home.lan/opds",
            "" to "",
        ).forEach { (address, logged) -> assertEquals(logged, redactAddress(address), address) }
    }
}
