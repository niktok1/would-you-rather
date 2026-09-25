package io.ntole.wyr.language

/**
 * The words of the Account screen and of the pages opened from it, the Auth page to register or log
 * in and the Submit screen's form (CLAUDE.md §8d, *The Account screen*, *Submitting*; §8f), as
 * [Strings.accountScreens]: made in each language as the rest of [Strings] is, and checked by the
 * same tests.
 *
 * Short on purpose, the user asking for less text. A text holding `{0}` is a template, which [fill]
 * fills in.
 */
data class AccountStrings(
    /** Who is playing, when they have no username. */
    val guest: String,
    /** The stat of the answers given, re-answers included. */
    val answers: String,
    /** The stat of the questions those answers went to. */
    val questions: String,
    /** The stat of the player's current cycle. */
    val cycle: String,
    /** Under the cycle: the `{0}` questions still due in it. */
    val cycleLeft: String,
    /** The stat of the likes the player's questions hold. */
    val likes: String,
    val logOut: String,
    /** A LOCAL or DEV build's server: its name, `{0}`, and its URL, `{1}`. */
    val serverLine: String,
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
    /** Anything else, asking to try again in the words of [Strings.tryAgain], the button's. */
    val somethingWrong: String,
    /** The heading of the player's own submissions on the Account screen. */
    val myQuestions: String,
    /** My questions' way to the Submit screen's form. */
    val newQuestion: String,
    /** My questions with none sent yet. */
    val noQuestions: String,
    /** Between a question's two options. */
    val or: String,
    val pending: String,
    val approved: String,
    /** A rejection with no reason given. */
    val rejected: String,
    /** A rejection and the moderator's reason, `{0}`, as they wrote it. */
    val rejectedBecause: String,
    val retired: String,
    /** A status this build cannot name. */
    val unknownStatus: String,
    /** The Submit form's heading, the question the two options answer. */
    val wouldYouRather: String,
    val optionA: String,
    val optionB: String,
    /** The option rule: one line, up to `{0}` characters. */
    val optionRule: String,
    val optionBlank: String,
    /** An option longer than `{0}` characters. */
    val optionTooLong: String,
    val optionNotOneLine: String,
    val optionsSame: String,
    val categories: String,
    val pickCategories: String,
    /** The Submit form's button, and what submitting costs, `{0}`, in points: *Пошаљи · 1 П*. */
    val send: String,
    /** Under Send, while the player has fewer points than submitting costs. */
    val notEnoughPoints: String,
    val invalidSubmission: String,
    /** The pending limit, `{0}` questions waiting for review. */
    val submissionLimit: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): AccountStrings =
        AccountStrings(
            guest = transform(guest),
            answers = transform(answers),
            questions = transform(questions),
            cycle = transform(cycle),
            cycleLeft = transform(cycleLeft),
            likes = transform(likes),
            logOut = transform(logOut),
            serverLine = transform(serverLine),
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
            usernameTaken = transform(usernameTaken),
            wrongLogin = transform(wrongLogin),
            alreadyRegistered = transform(alreadyRegistered),
            usernameRefused = transform(usernameRefused),
            passwordRefused = transform(passwordRefused),
            tooManyTries = transform(tooManyTries),
            tooManyTriesNoWait = transform(tooManyTriesNoWait),
            offline = transform(offline),
            somethingWrong = transform(somethingWrong),
            myQuestions = transform(myQuestions),
            newQuestion = transform(newQuestion),
            noQuestions = transform(noQuestions),
            or = transform(or),
            pending = transform(pending),
            approved = transform(approved),
            rejected = transform(rejected),
            rejectedBecause = transform(rejectedBecause),
            retired = transform(retired),
            unknownStatus = transform(unknownStatus),
            wouldYouRather = transform(wouldYouRather),
            optionA = transform(optionA),
            optionB = transform(optionB),
            optionRule = transform(optionRule),
            optionBlank = transform(optionBlank),
            optionTooLong = transform(optionTooLong),
            optionNotOneLine = transform(optionNotOneLine),
            optionsSame = transform(optionsSame),
            categories = transform(categories),
            pickCategories = transform(pickCategories),
            send = transform(send),
            notEnoughPoints = transform(notEnoughPoints),
            invalidSubmission = transform(invalidSubmission),
            submissionLimit = transform(submissionLimit),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicAccountStrings: AccountStrings =
    AccountStrings(
        guest = "Гост",
        answers = "Одговори",
        questions = "Питања",
        cycle = "Циклус",
        cycleLeft = "још {0}",
        likes = "Лајкови",
        logOut = "Одјави се",
        serverLine = "Сервер: {0} ({1})",
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
        usernameTaken = "То име је заузето.",
        wrongLogin = "Погрешно име или лозинка.",
        alreadyRegistered = "Већ имаш налог.",
        usernameRefused = "Сервер не прима то име.",
        passwordRefused = "Сервер не прима ту лозинку.",
        tooManyTries = "Превише покушаја. Сачекај {0} сек.",
        tooManyTriesNoWait = "Превише покушаја. Сачекај мало.",
        offline = "Нема интернет везе.",
        somethingWrong = "Нешто није у реду. Покушај поново.",
        myQuestions = "Моја питања",
        newQuestion = "Ново питање",
        noQuestions = "Још ниједно.",
        or = "или",
        pending = "На чекању",
        approved = "Одобрено",
        rejected = "Одбијено",
        rejectedBecause = "Одбијено: {0}",
        retired = "Повучено",
        unknownStatus = "Непознато",
        wouldYouRather = "Шта би радије…",
        optionA = "Опција А",
        optionB = "Опција Б",
        optionRule = "Један ред, до {0} знакова.",
        optionBlank = "Напиши нешто.",
        optionTooLong = "Највише {0} знакова.",
        optionNotOneLine = "Један ред, без прелома.",
        optionsSame = "Опције морају да се разликују.",
        categories = "Категорије",
        pickCategories = "Изабери једну или више.",
        send = "Пошаљи · {0}",
        notEnoughPoints = "Немаш довољно поена.",
        invalidSubmission = "Питање није прихваћено. Провери опције.",
        submissionLimit = "Већ имаш {0} питања на чекању.",
    )

internal val EnglishAccountStrings: AccountStrings =
    AccountStrings(
        guest = "Guest",
        answers = "Answers",
        questions = "Questions",
        cycle = "Cycle",
        cycleLeft = "{0} left",
        likes = "Likes",
        logOut = "Log out",
        serverLine = "Server: {0} ({1})",
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
        usernameTaken = "That name is taken.",
        wrongLogin = "Wrong username or password.",
        alreadyRegistered = "Already registered.",
        usernameRefused = "The server won't take that name.",
        passwordRefused = "The server won't take that password.",
        tooManyTries = "Too many tries. Wait {0} s.",
        tooManyTriesNoWait = "Too many tries. Wait a moment.",
        offline = "No connection. Check your internet.",
        somethingWrong = "Something went wrong. Try again.",
        myQuestions = "My questions",
        newQuestion = "New question",
        noQuestions = "None yet.",
        or = "or",
        pending = "Pending",
        approved = "Approved",
        rejected = "Rejected",
        rejectedBecause = "Rejected: {0}",
        retired = "Retired",
        unknownStatus = "Unknown",
        wouldYouRather = "Would you rather…",
        optionA = "Option A",
        optionB = "Option B",
        optionRule = "One line, up to {0} characters.",
        optionBlank = "Write something.",
        optionTooLong = "At most {0} characters.",
        optionNotOneLine = "One line, no line breaks.",
        optionsSame = "The two options must differ.",
        categories = "Categories",
        pickCategories = "Pick one or more.",
        send = "Send · {0}",
        notEnoughPoints = "Not enough points.",
        invalidSubmission = "Not accepted. Check both options.",
        submissionLimit = "You have {0} waiting already.",
    )
