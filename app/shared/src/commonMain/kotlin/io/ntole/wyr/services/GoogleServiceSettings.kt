package io.ntole.wyr.services

/**
 * The ids of the Google services a build uses, from its build config (CLAUDE.md §8a, §8b;
 * gradle/wyr-android-services.gradle.kts): Google Play Games Services' project and the game server's
 * OAuth client, and the Firebase project and this build's app in it. None is a secret, and none is
 * committed. A service missing any of its ids is off on the build, which [offLines] says, one line
 * each, and which is how every test and CI build runs.
 *
 * Common code, though only Android has these services yet, so what turns each on is held to one rule
 * and tested once.
 */
data class GoogleServiceSettings(
    /** The Play Games Services project id, which the Android manifest's `APP_ID` names too. */
    val playGamesAppId: String = "",
    /** The game server credential's OAuth client id, the server's `PLAY_GAMES_CLIENT_ID`. */
    val playGamesServerClientId: String = "",
    /** The Firebase project's id (`project_id`). */
    val firebaseProjectId: String = "",
    /** The Firebase project's Android API key (`current_key`). */
    val firebaseApiKey: String = "",
    /** The Firebase project's number, its Cloud Messaging sender id (`project_number`). */
    val firebaseSenderId: String = "",
    /** This build's app in the Firebase project, one per flavor (`mobilesdk_app_id`). */
    val firebaseAppId: String = "",
) {
    /** Whether Play Games is on: both its ids are there. */
    val playGamesOn: Boolean
        get() = playGamesMissing.isEmpty()

    /** Whether pushes are on: all four of Firebase's ids are there. */
    val pushOn: Boolean
        get() = firebaseMissing.isEmpty()

    private val playGamesMissing: List<String>
        get() =
            listOfNotNull(
                "wyr.playgames.appId".takeIf { playGamesAppId.isBlank() },
                "wyr.playgames.serverClientId".takeIf { playGamesServerClientId.isBlank() },
            )

    private val firebaseMissing: List<String>
        get() =
            listOfNotNull(
                "wyr.firebase.projectId".takeIf { firebaseProjectId.isBlank() },
                "wyr.firebase.apiKey".takeIf { firebaseApiKey.isBlank() },
                "wyr.firebase.senderId".takeIf { firebaseSenderId.isBlank() },
                "this flavor's wyr.firebase.appId".takeIf { firebaseAppId.isBlank() },
            )

    /** One line for each service that is off on this build, naming the ids it lacks; none when all are on. */
    fun offLines(): List<String> =
        listOfNotNull(
            playGamesMissing
                .takeIf {
                    it.isNotEmpty()
                }?.let { "Play Games is off on this build: no ${it.joinToString()}" },
            firebaseMissing.takeIf { it.isNotEmpty() }?.let { "Pushes are off on this build: no ${it.joinToString()}" },
        )
}
