package com.retro99.server.api

import kotlin.test.Test
import kotlin.test.assertEquals

class InSentenceDeviceNameTest {
    private fun identity(name: () -> String) = object : InstallationDeviceIdentity {
        override fun getOrCreate() = SourceDeviceIdentity("id", null)
        override fun selfReferenceName() = name()
    }

    @Test fun the_shared_name_is_lower_cased_at_the_start_only() {
        assertEquals("this phone", identity { "This phone" }.inSentenceDeviceName())
        assertEquals("this tablet", identity { "This tablet" }.inSentenceDeviceName())
        assertEquals("this iPhone", identity { "This iPhone" }.inSentenceDeviceName())
        assertEquals("this iPad", identity { "This iPad" }.inSentenceDeviceName())
    }

    @Test fun without_a_name_it_is_this_device() {
        assertEquals("this device", identity { "" }.inSentenceDeviceName())
        assertEquals("this device", identity { " " }.inSentenceDeviceName())
        assertEquals("this device", identity { error("no platform") }.inSentenceDeviceName())
        assertEquals("this device", object : InstallationDeviceIdentity {
            override fun getOrCreate() = SourceDeviceIdentity("id", null)
        }.inSentenceDeviceName())
    }
}
