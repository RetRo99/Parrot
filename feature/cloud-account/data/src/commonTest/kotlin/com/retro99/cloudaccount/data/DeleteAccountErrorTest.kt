package com.retro99.cloudaccount.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeleteAccountErrorTest {
    @Test
    fun `403 reauthentication_required asks for a fresh sign-in`() {
        val body = """{"error":"Sign in again to delete this account","code":"reauthentication_required"}"""
        assertTrue(isDeleteReauthenticationRequired(403, body))
    }

    @Test
    fun `other codes statuses and bodies are not reauthentication`() {
        val anonymous = """{"error":"x","code":"anonymous_not_allowed"}"""
        val reauth = """{"error":"x","code":"reauthentication_required"}"""
        assertFalse(isDeleteReauthenticationRequired(403, anonymous))
        assertFalse(isDeleteReauthenticationRequired(401, reauth))
        assertFalse(isDeleteReauthenticationRequired(403, "not json"))
        assertFalse(isDeleteReauthenticationRequired(403, """{"code":403}"""))
        assertFalse(isDeleteReauthenticationRequired(403, "[]"))
    }
}
