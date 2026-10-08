package com.retro99.server.parrotcloud

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.user.api.UserRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParrotCloudProgressAccountResolverTest {
    private var activeProfile = "profile-a"
    private val links = mutableMapOf(
        "profile-a" to CloudProfileLink("profile-a", "account-a", syncEnabled = true),
        "profile-b" to CloudProfileLink("profile-b", "account-b", syncEnabled = true),
    )
    private val lookups = mutableListOf<String>()
    private val resolver = ParrotCloudProgressAccountResolver(
        stub<CloudProfileLinkRepository> { name, args ->
            check(name == "getForLocalProfile")
            val profile = args!![0] as String
            lookups += profile
            links[profile]
        },
        stub<UserRegistry> { name, _ ->
            check(name == "getActiveProfileIdOrDefault")
            activeProfile
        },
    )

    @Test
    fun `library and cloud identifiers resolve to the linked account`() = runTest {
        assertEquals("account-a", resolver.accountId(LOCAL_SERVER_ID))
        assertEquals("account-a", resolver.accountId(PARROT_CLOUD_SERVER_ID))
        assertEquals(listOf("profile-a", "profile-a"), lookups)
    }

    @Test
    fun `external servers retain their own destination without consulting cloud links`() = runTest {
        assertEquals("storyteller", resolver.accountId("storyteller"))
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `switching profiles cannot reuse another profiles cloud account`() = runTest {
        assertEquals("account-a", resolver.accountId(LOCAL_SERVER_ID))
        activeProfile = "profile-b"
        assertEquals("account-b", resolver.accountId(LOCAL_SERVER_ID))
    }

    @Test
    fun `unlinked profile has no bound cloud account`() = runTest {
        activeProfile = "unlinked"
        assertNull(resolver.accountId(LOCAL_SERVER_ID))
        assertNull(resolver.accountId(PARROT_CLOUD_SERVER_ID))
    }

    private inline fun <reified T> stub(crossinline result: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> T::class.java.simpleName
                else -> result(method.name, args)
            }
        } as T
}
