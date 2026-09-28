package io.ntole.wyr.language

/**
 * The words of the shop (CLAUDE.md §8d, *The shop*), as [Strings.shopScreen]: made in each language as
 * the rest of [Strings] is, and checked by the same tests. What the shop shares with the Account
 * screens, *Немаш довољно поена.*, being offline and the Auth page's button, is theirs
 * ([Strings.accountScreens]), so the game says it one way.
 */
data class ShopStrings(
    /** The shop, as its heading and as the Account bar's shop icon is named for a screen reader. */
    val title: String,
    /** The heading of the themes on sale. */
    val themes: String,
    /** The game's own theme, free, which every player has. */
    val classic: String,
    val neonNight: String,
    val ocean: String,
    val forest: String,
    val sunset: String,
    /** A theme's button while it is not the player's: *Купи ·*, then the coin and its price, `{0}`. */
    val buy: String,
    /** A theme's button once it is the player's, and not worn. */
    val apply: String,
    /** A theme's button while it is worn: off, since there is nothing to do. */
    val active: String,
    /** The question the dialog before a purchase asks, the theme's name, `{0}`, in it. */
    val confirmBuy: String,
    /** Why a guest cannot buy, over the Auth page's button. */
    val registerToBuy: String,
    /** The server's answer that the theme is the player's already. */
    val alreadyOwned: String,
    /** The one line under the themes, since there will be more to buy. */
    val comingSoon: String,
    /** Why the shop could not be read, for any reason but being offline. */
    val unread: String,
    /** A preview's two answers, a question as the Play screen would show it in the theme. */
    val previewOptionA: String,
    val previewOptionB: String,
    /** A preview, as a screen reader names it: the theme's name, `{0}`. */
    val preview: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): ShopStrings =
        ShopStrings(
            title = transform(title),
            themes = transform(themes),
            classic = transform(classic),
            neonNight = transform(neonNight),
            ocean = transform(ocean),
            forest = transform(forest),
            sunset = transform(sunset),
            buy = transform(buy),
            apply = transform(apply),
            active = transform(active),
            confirmBuy = transform(confirmBuy),
            registerToBuy = transform(registerToBuy),
            alreadyOwned = transform(alreadyOwned),
            comingSoon = transform(comingSoon),
            unread = transform(unread),
            previewOptionA = transform(previewOptionA),
            previewOptionB = transform(previewOptionB),
            preview = transform(preview),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicShopStrings: ShopStrings =
    ShopStrings(
        title = "Продавница",
        themes = "Теме",
        classic = "Класична",
        neonNight = "Неонска ноћ",
        ocean = "Океан",
        forest = "Шума",
        sunset = "Залазак",
        buy = "Купи · {0}",
        apply = "Примени",
        active = "Активна",
        confirmBuy = "Купи тему {0}?",
        registerToBuy = "Региструј се да купујеш у продавници.",
        alreadyOwned = "Ова тема је већ твоја.",
        comingSoon = "Ускоро још ствари у продавници.",
        unread = "Продавница није учитана.",
        previewOptionA = "Пица",
        previewOptionB = "Бурек",
        preview = "Преглед: {0}",
    )

internal val EnglishShopStrings: ShopStrings =
    ShopStrings(
        title = "Shop",
        themes = "Themes",
        classic = "Classic",
        neonNight = "Neon night",
        ocean = "Ocean",
        forest = "Forest",
        sunset = "Sunset",
        buy = "Buy · {0}",
        apply = "Apply",
        active = "Active",
        confirmBuy = "Buy the {0} theme?",
        registerToBuy = "Register to buy in the shop.",
        alreadyOwned = "This theme is yours already.",
        comingSoon = "More things are coming to the shop soon.",
        unread = "Couldn't load the shop.",
        previewOptionA = "Pizza",
        previewOptionB = "Burek",
        preview = "Preview: {0}",
    )
