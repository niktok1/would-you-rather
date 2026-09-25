package io.ntole.wyr.language

/**
 * The words of the Account screen and of the page opened from it to register or log in (CLAUDE.md
 * §8d, *The Account screen*; §8f), as [Strings.accountScreens]: made in each language as the rest of
 * [Strings] is, and checked by the same tests.
 *
 * Short on purpose, the user asking for less text. A text holding `{0}` is a template, which [fill]
 * fills in.
 */
data class AccountStrings(
    /** A guest's one way to register or log in, on the Account screen. */
    val openAuth: String,
    val username: String,
    val password: String,
    /** The password field's toggle, showing what is typed. */
    val show: String,
    /** The password field's toggle, hiding what is typed again. */
    val hide: String,
    /** The username rule: `{0}` to `{1}` characters, of those `{2}` names ([USERNAME_CHARACTERS]). */
    val usernameRule: String,
    /** The password rule: `{0}` to `{1}` characters, of any kind. */
    val passwordRule: String,
    val register: String,
    val logIn: String,
    /** The register form's link to the login form. */
    val toLogIn: String,
    /** The login form's link back to the register form. */
    val toRegister: String,
    /** The one warning before a login leaves a guest's points, `{0}`, behind. */
    val guestPointsWarning: String,
    val logInAnyway: String,
    val cancel: String,
    val tryAgain: String,
    val usernameTaken: String,
    val wrongLogin: String,
    val alreadyRegistered: String,
    val usernameRefused: String,
    val passwordRefused: String,
    /** A rate limit, and the seconds `{0}` the server said to wait. */
    val tooManyTries: String,
    /** A rate limit with no wait named. */
    val tooManyTriesNoWait: String,
    val offline: String,
    val somethingWrong: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): AccountStrings =
        AccountStrings(
            openAuth = transform(openAuth),
            username = transform(username),
            password = transform(password),
            show = transform(show),
            hide = transform(hide),
            usernameRule = transform(usernameRule),
            passwordRule = transform(passwordRule),
            register = transform(register),
            logIn = transform(logIn),
            toLogIn = transform(toLogIn),
            toRegister = transform(toRegister),
            guestPointsWarning = transform(guestPointsWarning),
            logInAnyway = transform(logInAnyway),
            cancel = transform(cancel),
            tryAgain = transform(tryAgain),
            usernameTaken = transform(usernameTaken),
            wrongLogin = transform(wrongLogin),
            alreadyRegistered = transform(alreadyRegistered),
            usernameRefused = transform(usernameRefused),
            passwordRefused = transform(passwordRefused),
            tooManyTries = transform(tooManyTries),
            tooManyTriesNoWait = transform(tooManyTriesNoWait),
            offline = transform(offline),
            somethingWrong = transform(somethingWrong),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicAccountStrings: AccountStrings =
    AccountStrings(
        openAuth = "Региструј се или се пријави",
        username = "Корисничко име",
        password = "Лозинка",
        show = "Прикажи",
        hide = "Сакриј",
        usernameRule = "{0}–{1} знакова: {2}",
        passwordRule = "{0}–{1} знакова",
        register = "Региструј се",
        logIn = "Пријави се",
        toLogIn = "Већ имаш налог? Пријави се",
        toRegister = "Немаш налог? Региструј се",
        guestPointsWarning = "Поени госта ({0}) неће прећи на налог.",
        logInAnyway = "Ипак се пријави",
        cancel = "Откажи",
        tryAgain = "Покушај поново",
        usernameTaken = "То име је заузето.",
        wrongLogin = "Погрешно име или лозинка.",
        alreadyRegistered = "Налог већ постоји.",
        usernameRefused = "Сервер не прима то име.",
        passwordRefused = "Сервер не прима ту лозинку.",
        tooManyTries = "Превише покушаја. Сачекај {0} сек.",
        tooManyTriesNoWait = "Превише покушаја. Сачекај мало.",
        offline = "Нема везе. Провери интернет.",
        somethingWrong = "Нешто није у реду. Покушај поново.",
    )

internal val EnglishAccountStrings: AccountStrings =
    AccountStrings(
        openAuth = "Register or log in",
        username = "Username",
        password = "Password",
        show = "Show",
        hide = "Hide",
        usernameRule = "{0}–{1} characters: {2}",
        passwordRule = "{0}–{1} characters",
        register = "Register",
        logIn = "Log in",
        toLogIn = "Have an account? Log in",
        toRegister = "No account? Register",
        guestPointsWarning = "Your guest points ({0}) won't carry over.",
        logInAnyway = "Log in anyway",
        cancel = "Cancel",
        tryAgain = "Try again",
        usernameTaken = "That name is taken.",
        wrongLogin = "Wrong username or password.",
        alreadyRegistered = "Already registered.",
        usernameRefused = "The server won't take that name.",
        passwordRefused = "The server won't take that password.",
        tooManyTries = "Too many tries. Wait {0} s.",
        tooManyTriesNoWait = "Too many tries. Wait a moment.",
        offline = "No connection. Check your internet.",
        somethingWrong = "Something went wrong. Try again.",
    )
