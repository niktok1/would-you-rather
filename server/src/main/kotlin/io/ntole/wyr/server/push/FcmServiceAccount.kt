package io.ntole.wyr.server.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The Google service account the server sends pushes as (CLAUDE.md §8b, *Push notifications*): the key
 * file Firebase gives for the project, from `FCM_SERVICE_ACCOUNT_JSON`. Only what signing in to Google
 * and naming the project need is kept.
 *
 * [toString] shows none of the private key, so nothing that prints the server's configuration can
 * log it.
 */
class FcmServiceAccount(
    /** The Firebase project the pushes are sent in, and whose apps' tokens they reach. */
    val projectId: String,
    /** The service account's own address, which signs in as it. */
    val clientEmail: String,
    /** Which of the account's keys [privateKey] is, named in the sign-in's header, or null when the file names none. */
    val privateKeyId: String?,
    val privateKey: RSAPrivateKey,
    /** Where the signed sign-in is exchanged for an access token: Google's OAuth token endpoint. */
    val tokenUri: String,
) {
    override fun toString(): String =
        "FcmServiceAccount(projectId=$projectId, clientEmail=$clientEmail, privateKey=***)"

    companion object {
        /** Google's token endpoint, which every service account key names. */
        const val GOOGLE_TOKEN_URI: String = "https://oauth2.googleapis.com/token"

        /**
         * The service account the key file [raw] describes, or an [IllegalArgumentException] naming the
         * variable and what is wrong, never any of the file: it holds a private key. Nothing it throws
         * carries a cause, whose message might quote the file.
         */
        fun parse(raw: String): FcmServiceAccount {
            fun refuse(what: String): Nothing =
                throw IllegalArgumentException(
                    "FCM_SERVICE_ACCOUNT_JSON $what; expected the JSON key file of a Firebase service account, " +
                        "whole, as Google Cloud's console downloads it.",
                )

            val key =
                try {
                    KeyFileJson.decodeFromString<KeyFile>(raw)
                } catch (_: SerializationException) {
                    refuse("is not a JSON object")
                } catch (_: IllegalArgumentException) {
                    refuse("is not a JSON object")
                }
            if (key.type != null && key.type != SERVICE_ACCOUNT) refuse("is not a service account's key")
            val projectId = key.projectId?.takeIf { it.isNotBlank() } ?: refuse("names no project_id")
            val clientEmail = key.clientEmail?.takeIf { it.isNotBlank() } ?: refuse("names no client_email")
            val pem = key.privateKey?.takeIf { it.isNotBlank() } ?: refuse("holds no private_key")
            val privateKey = rsaPrivateKeyOf(pem) ?: refuse("holds a private_key that is no RSA key in PKCS #8")

            return FcmServiceAccount(
                projectId = projectId,
                clientEmail = clientEmail,
                privateKeyId = key.privateKeyId?.takeIf { it.isNotBlank() },
                privateKey = privateKey,
                tokenUri = key.tokenUri?.takeIf { it.isNotBlank() } ?: GOOGLE_TOKEN_URI,
            )
        }

        /**
         * The RSA key a PEM `PRIVATE KEY` block holds, or null. A key pasted with its line breaks written
         * out as `\n`, as some dashboards turn them, reads the same.
         */
        private fun rsaPrivateKeyOf(pem: String): RSAPrivateKey? {
            val base64 =
                pem
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot(Char::isWhitespace)
            return try {
                val der = Base64.getDecoder().decode(base64)
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as? RSAPrivateKey
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: InvalidKeySpecException) {
                null
            }
        }

        private const val SERVICE_ACCOUNT = "service_account"

        private val KeyFileJson = Json { ignoreUnknownKeys = true }
    }

    /** The fields of a service account's key file the server reads. */
    @Serializable
    private class KeyFile(
        val type: String? = null,
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("private_key_id") val privateKeyId: String? = null,
        @SerialName("private_key") val privateKey: String? = null,
        @SerialName("client_email") val clientEmail: String? = null,
        @SerialName("token_uri") val tokenUri: String? = null,
    )
}
