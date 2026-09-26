package io.ntole.wyr.server.push

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/** A key pair made for one test run, never any real account's. */
internal val TEST_KEY_PAIR: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

/** [TEST_KEY_PAIR]'s private key as a key file holds it: PEM, PKCS #8, in lines of 64. */
internal val TEST_PRIVATE_KEY_PEM: String =
    "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(TEST_KEY_PAIR.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"

internal const val TEST_PROJECT = "wyr-test-project"
internal const val TEST_CLIENT_EMAIL = "pushes@wyr-test-project.iam.gserviceaccount.com"
internal const val TEST_KEY_ID = "0123456789abcdef"

/** Where the tests' key file says to exchange an assertion: Google's, which only a MockEngine answers. */
internal const val TEST_TOKEN_URI = "https://oauth2.googleapis.com/token"

/** A service account key file as Google Cloud's console downloads one, for [TEST_KEY_PAIR]. */
internal fun testServiceAccountJson(privateKeyPem: String = TEST_PRIVATE_KEY_PEM): String =
    buildJsonObject {
        put("type", "service_account")
        put("project_id", TEST_PROJECT)
        put("private_key_id", TEST_KEY_ID)
        put("private_key", privateKeyPem)
        put("client_email", TEST_CLIENT_EMAIL)
        put("client_id", "123456789012345678901")
        put("auth_uri", "https://accounts.google.com/o/oauth2/auth")
        put("token_uri", TEST_TOKEN_URI)
    }.toString()

internal val TEST_SERVICE_ACCOUNT: FcmServiceAccount = FcmServiceAccount.parse(testServiceAccountJson())
