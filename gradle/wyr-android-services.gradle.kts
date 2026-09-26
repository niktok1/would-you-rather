/*
 * The ids of the Google services an Android build of the game uses (CLAUDE.md §8a, *Play Games
 * sign-in*, *Push tokens*; §8b): Google Play Games Services' project and the game server's OAuth
 * client. Each is the Gradle property of its name (`-Pwyr.playgames.appId=...`, or
 * `~/.gradle/gradle.properties`), or else the same name in the repository's `local.properties`, which
 * git ignores, as the analytics key is (gradle/wyr-analytics.gradle.kts). None is a secret, the game
 * server's client secret staying on the server, but none is committed either: they are the user's.
 *
 * - `wyr.playgames.appId`: the Play Games Services project id, the digits the Play Console shows, which
 *   the manifest's `com.google.android.gms.games.APP_ID` names.
 * - `wyr.playgames.serverClientId`: the game server credential's OAuth client id, the server's
 *   `PLAY_GAMES_CLIENT_ID`, which the app asks Play Games for a server auth code for.
 * - `wyr.firebase.projectId`, `wyr.firebase.apiKey` and `wyr.firebase.senderId`: the Firebase
 *   project's, as its Android app's `google-services.json` names them (`project_id`, the
 *   `current_key` of `api_key`, `project_number`), one project for every flavor.
 * - `wyr.firebase.appId.local`, `.dev` and `.prod`: each flavor's Firebase app id (`mobilesdk_app_id`),
 *   since Firebase has one Android app per package.
 *
 * A feature with a missing id is off on that build, and the app says so in one log line at launch;
 * every test and CI build runs with it off. An id holding what it cannot hold fails the build here,
 * since each is written into generated code.
 *
 * Applied by `:app:androidApp`, which reads `extra["wyrPlayGamesAppId"]`,
 * `extra["wyrPlayGamesServerClientId"]`, `extra["wyrFirebaseProjectId"]`, `extra["wyrFirebaseApiKey"]`,
 * `extra["wyrFirebaseSenderId"]` and `extra["wyrFirebaseAppIds"]`, a map from each flavor's name to its
 * app id: every one empty for none.
 */
import java.io.StringReader
import java.util.Properties

val localProperties =
    Properties().apply {
        providers
            .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
            .asText
            .orNull
            ?.let { text -> load(StringReader(text)) }
    }

fun serviceSetting(name: String): String =
    (providers.gradleProperty(name).orNull ?: localProperties.getProperty(name)).orEmpty().trim()

fun checked(
    name: String,
    allowed: Regex,
    what: String,
): String =
    serviceSetting(name).also { value ->
        require(value.isEmpty() || allowed.matches(value)) { "$name must be $what" }
    }

extra["wyrPlayGamesAppId"] = checked("wyr.playgames.appId", Regex("[0-9]+"), "digits: the Play Games project id")
extra["wyrPlayGamesServerClientId"] =
    checked(
        "wyr.playgames.serverClientId",
        Regex("[A-Za-z0-9._-]+"),
        "an OAuth client id: letters, digits, '.', '_' and '-'",
    )

extra["wyrFirebaseProjectId"] =
    checked("wyr.firebase.projectId", Regex("[a-z0-9-]+"), "a project id: lower-case letters, digits and '-'")
extra["wyrFirebaseApiKey"] =
    checked("wyr.firebase.apiKey", Regex("[A-Za-z0-9_-]+"), "an API key: letters, digits, '_' and '-'")
extra["wyrFirebaseSenderId"] = checked("wyr.firebase.senderId", Regex("[0-9]+"), "digits: the project number")
extra["wyrFirebaseAppIds"] =
    listOf("local", "dev", "prod").associateWith { flavor ->
        checked(
            "wyr.firebase.appId.$flavor",
            Regex("[A-Za-z0-9:_-]+"),
            "a Firebase app id, as 1:123456789:android:abc123",
        )
    }
