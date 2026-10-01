package com.retro99.sync.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EchoClassifierTest {

    @Test
    fun `storyteller - the same timestamp is an echo`() {
        assertTrue(EchoClassifier.isEcho("1700", 0.40, OwnWrite(marker = "1700", 0.40)))
    }

    @Test
    fun `storyteller - another timestamp is real reading even at the same progression`() {
        assertFalse(EchoClassifier.isEcho("1800", 0.40, OwnWrite(marker = "1700", 0.40)))
    }

    @Test
    fun `parrot - the same revision is an echo and a newer one is not`() {
        assertTrue(EchoClassifier.isEcho("12", 0.40, OwnWrite(marker = "12", 0.40)))
        assertFalse(EchoClassifier.isEcho("13", 0.40, OwnWrite(marker = "12", 0.40)))
    }

    @Test
    fun `audiobookshelf - within 1 percent is an echo, 5 percent further is not`() {
        assertTrue(EchoClassifier.isEcho(null, 0.405, OwnWrite(marker = null, 0.40)))
        assertFalse(EchoClassifier.isEcho(null, 0.45, OwnWrite(marker = null, 0.40)))
    }

    @Test
    fun `without a write log row nothing is an echo`() {
        assertFalse(EchoClassifier.isEcho("1700", 0.40, ownWrite = null))
    }

    @Test
    fun `a write not yet acknowledged falls back to the value`() {
        assertTrue(EchoClassifier.isEcho("12", 0.401, OwnWrite(marker = null, 0.40)))
    }
}
