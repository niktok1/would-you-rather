package io.ntole.wyr.server.push

import io.ntole.wyr.server.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The key file the server sends pushes with (CLAUDE.md §8b, *Push notifications*). */
class FcmServiceAccountTest {
    @Test
    fun `a key file names its project and account and holds its private key`() {
        val account = FcmServiceAccount.parse(testServiceAccountJson())

        assertEquals(TEST_PROJECT, account.projectId)
        assertEquals(TEST_CLIENT_EMAIL, account.clientEmail)
        assertEquals(TEST_KEY_ID, account.privateKeyId)
        assertEquals(TEST_TOKEN_URI, account.tokenUri)
        assertEquals(TEST_KEY_PAIR.private, account.privateKey)
    }

    @Test
    fun `a private key whose line breaks were written out still reads`() {
        val flattened = TEST_PRIVATE_KEY_PEM.replace("\n", "\\n")

        assertEquals(TEST_KEY_PAIR.private, FcmServiceAccount.parse(testServiceAccountJson(flattened)).privateKey)
    }

    @Test
    fun `printing the account shows none of its private key`() {
        val printed = TEST_SERVICE_ACCOUNT.toString() + ServerConfig.fromEnvironment(ENV::get).toString()

        assertFalse(printed.contains(TEST_PRIVATE_KEY_PEM.lines()[1]), printed)
        assertFalse(printed.contains("BEGIN PRIVATE KEY"), printed)
    }

    @Test
    fun `the server reads FCM_SERVICE_ACCOUNT_JSON and is off without it`() {
        assertEquals(TEST_PROJECT, ServerConfig.fromEnvironment(ENV::get).fcmServiceAccount?.projectId)
        assertNull(ServerConfig.fromEnvironment { null }.fcmServiceAccount)
        assertNull(ServerConfig.fromEnvironment(mapOf("FCM_SERVICE_ACCOUNT_JSON" to "  \n")::get).fcmServiceAccount)
    }

    /** The boot fails on a key file it cannot use, naming the variable and quoting none of it. */
    @Test
    fun `a value that is no service account's key fails the boot and gives none of it away`() {
        val secretish = "-----BEGIN PRIVATE KEY-----\nnotakey\n-----END PRIVATE KEY-----"
        val refused =
            listOf(
                "not json at all, $secretish",
                "[1, 2, 3]",
                """{"type":"authorized_user","private_key":"$secretish"}""",
                testServiceAccountJson().replace(TEST_PROJECT, ""),
                testServiceAccountJson(privateKeyPem = secretish),
                testServiceAccountJson(privateKeyPem = ""),
            )

        refused.forEach { raw ->
            val failure =
                assertFailsWith<IllegalArgumentException>(raw.take(40)) {
                    ServerConfig.fromEnvironment(mapOf("FCM_SERVICE_ACCOUNT_JSON" to raw)::get)
                }

            assertContains(failure.message.orEmpty(), "FCM_SERVICE_ACCOUNT_JSON")
            assertFalse(failure.message.orEmpty().contains("notakey"), "the message quotes none of the value")
            assertNull(failure.cause, "no cause, whose message might quote it")
        }
    }

    private companion object {
        val ENV = mapOf("FCM_SERVICE_ACCOUNT_JSON" to testServiceAccountJson())
    }
}
