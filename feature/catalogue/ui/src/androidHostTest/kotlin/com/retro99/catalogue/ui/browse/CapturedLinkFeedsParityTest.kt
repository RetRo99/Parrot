package com.retro99.catalogue.ui.browse

import kotlin.test.*

class CapturedLinkFeedsParityTest {
    @Test fun embeddedIosFixturesAreIdenticalToCapturedBytes() {
        CapturedLinkFeeds.xml.forEach { (name, embedded) ->
            val raw = javaClass.classLoader!!.getResourceAsStream("linked-books/$name.xml")!!.use { it.readBytes().decodeToString() }
            assertEquals(raw, embedded, name)
        }
    }
}
