package io.ntole.wyr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ntole.wyr.account.AccountScreen
import io.ntole.wyr.account.AccountState
import io.ntole.wyr.categories.CategoriesScreen
import io.ntole.wyr.categories.CategoriesState
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopTheme
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.home.HomeScreen
import io.ntole.wyr.language.GOOGLE_PLAY
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.navigation.AccountTopBar
import io.ntole.wyr.navigation.BackTopBar
import io.ntole.wyr.navigation.PlayTopBar
import io.ntole.wyr.play.CategoriesPlayed
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayUiState
import io.ntole.wyr.play.percentStyle
import io.ntole.wyr.shop.ShopScreen
import io.ntole.wyr.shop.ShopState
import io.ntole.wyr.submit.SubmitScreen
import io.ntole.wyr.submit.SubmitState
import io.ntole.wyr.theme.GameThemes
import io.ntole.wyr.theme.WyrTheme
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import org.jetbrains.compose.resources.decodeToImageVector
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Google Play listing's graphics (store/android/README.md), drawn with the game's own theme, strings
 * and screens: the 512 by 512 icon, drawn from the launcher icon's own vector drawables in
 * `:app:androidApp` so the two can never differ (CLAUDE.md §8, *Release builds*); the 1024 by 500
 * feature graphic; and a phone's screenshots, 1080 by 1920, in Serbian Cyrillic. Each is drawn and its
 * size and opacity checked in every run; with `WYR_STORE_DIR` set, each is also written there, the icon
 * as a 32-bit PNG and the rest as 24-bit PNGs with no alpha, as Play asks:
 * `WYR_STORE_DIR=$PWD/store/android ./gradlew :app:shared:jvmTest --tests io.ntole.wyr.StoreGraphicsTest --rerun`.
 *
 * The launcher icon's own checks are here too, since only this module draws off screen: its foreground
 * and monochrome layers stay inside the 66dp circle every launcher's mask keeps, and the monochrome is
 * one colour.
 */
class StoreGraphicsTest {
    private val directory: File? =
        System
            .getenv(DIRECTORY)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.also { File(it, SCREENSHOTS).mkdirs() }

    @Test
    fun `the Play icon is the launcher icon, square, opaque and under a megabyte`() {
        val image = draw(ICON_SIZE, ICON_SIZE, Density(1f)) { LauncherIcon() }
        val png = checkNotNull(image.encodeToData()) { "the icon encodes to nothing" }.bytes
        assertTrue(png.size < MAX_ICON_BYTES, "${png.size} bytes")
        val pixels = image.toComposeImageBitmap().toAwtImage()
        assertEquals(ICON_SIZE, pixels.width)
        assertEquals(ICON_SIZE, pixels.height)
        assertTrue(pixels.isOpaque(), "the icon has transparent pixels")
        directory?.let { File(it, "icon-512.png").writeBytes(png) }
    }

    @Test
    fun `the launcher icon's shapes stay inside the circle every mask keeps`() {
        listOf(FOREGROUND, MONOCHROME).forEach { layer ->
            val vector = vectorOf(layer)
            val image =
                draw(LAYER_SIZE, LAYER_SIZE, Density(1f)) {
                    val painter = rememberVectorPainter(vector)
                    Canvas(Modifier.fillMaxSize()) { with(painter) { draw(size) } }
                }.toComposeImageBitmap().toAwtImage()
            val middle = LAYER_SIZE / 2.0
            val safe = SAFE_RADIUS_DP * LAYER_SIZE / CANVAS_DP
            for (y in 0 until LAYER_SIZE) {
                for (x in 0 until LAYER_SIZE) {
                    val argb = image.getRGB(x, y)
                    val alpha = argb ushr 24
                    if (hypot(x + 0.5 - middle, y + 0.5 - middle) > safe + 1) {
                        assertEquals(0, alpha, "$layer draws at ($x,$y), outside the safe circle")
                    }
                    if (layer == MONOCHROME && alpha > OPAQUE_ENOUGH) {
                        assertTrue(argb and 0xFFFFFF == 0xFFFFFF, "$layer is not one colour at ($x,$y)")
                    }
                }
            }
        }
    }

    @Test
    fun `the feature graphic is 1024 by 500 and opaque`() {
        val image = draw(FEATURE_WIDTH, FEATURE_HEIGHT, Density(FEATURE_DENSITY)) { FeatureGraphic() }
        writeOpaque(image, "feature-graphic.png", FEATURE_WIDTH, FEATURE_HEIGHT)
    }

    @Test
    fun `the phone screenshots are 1080 by 1920 and opaque`() {
        SHOTS.forEach { shot ->
            val image =
                draw(PHONE_WIDTH, PHONE_HEIGHT, Density(PHONE_DENSITY), dark = shot.dark) {
                    OnWholePage { Column(Modifier.fillMaxSize()) { shot.content(this) } }
                }
            writeOpaque(image, "$SCREENSHOTS/${shot.name}.png", PHONE_WIDTH, PHONE_HEIGHT)
        }
    }

    /** [content] drawn [width] by [height] in the game's own theme and Serbian Cyrillic, once it has settled. */
    private fun draw(
        width: Int,
        height: Int,
        density: Density,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ): Image {
        val scene =
            ImageComposeScene(width = width, height = height, density = density) {
                WyrTheme(theme = GameThemes.Default, darkTheme = dark) {
                    WyrStrings(Language.DEFAULT) { content() }
                }
            }
        return try {
            scene.renderSettled()
        } finally {
            scene.close()
        }
    }

    /** Checks [image] is [width] by [height] with no transparency, and writes it as a PNG with no alpha. */
    private fun writeOpaque(
        image: Image,
        name: String,
        width: Int,
        height: Int,
    ) {
        val pixels = image.toComposeImageBitmap().toAwtImage()
        assertEquals(width, pixels.width, name)
        assertEquals(height, pixels.height, name)
        assertTrue(pixels.isOpaque(), "$name has transparent pixels")
        directory?.let {
            val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().apply {
                drawImage(pixels, 0, 0, null)
                dispose()
            }
            assertTrue(ImageIO.write(rgb, "png", File(it, name)), name)
        }
    }

    private fun BufferedImage.isOpaque(): Boolean =
        (0 until height).all { y -> (0 until width).all { x -> getRGB(x, y) ushr 24 == 0xFF } }

    /** A phone screenshot: its file's name, and whether it is drawn in the dark theme. */
    private class Shot(
        val name: String,
        val dark: Boolean = false,
        val content: @Composable ColumnScope.() -> Unit,
    )

    private companion object {
        const val DIRECTORY = "WYR_STORE_DIR"
        const val SCREENSHOTS = "screenshots"

        // The launcher icon's drawables, in the app module beside this one, where a unit test runs.
        val DRAWABLES = File("../androidApp/src/main/res/drawable")
        const val BACKGROUND = "launcher_background.xml"
        const val FOREGROUND = "launcher_foreground.xml"
        const val MONOCHROME = "launcher_monochrome.xml"

        /** An adaptive icon's canvas, the circle every launcher's mask keeps, and the 72 a launcher shows. */
        const val CANVAS_DP = 108.0
        const val SAFE_RADIUS_DP = 33.0
        const val SHOWN_DP = 72f
        const val LAYER_SIZE = 432
        const val OPAQUE_ENOUGH = 64

        // What Google Play asks of each graphic.
        const val ICON_SIZE = 512
        const val MAX_ICON_BYTES = 1024 * 1024
        const val FEATURE_WIDTH = 1024
        const val FEATURE_HEIGHT = 500

        /** 640 by 312 dp: wider than `wideLayoutMinWidth`, so the Classic art's wash runs left to right. */
        const val FEATURE_DENSITY = 1.6f

        /** 9 to 16, as Play takes a phone's, at three pixels a dp: a phone 360 by 640 dp. */
        const val PHONE_WIDTH = 1080
        const val PHONE_HEIGHT = 1920
        const val PHONE_DENSITY = 3f

        const val POINTS = 312

        fun vectorOf(name: String): ImageVector = File(DRAWABLES, name).readBytes().decodeToImageVector(Density(1f))

        /**
         * The launcher icon, background and foreground, full bleed: the middle 72 of its 108dp canvas, which a
         * launcher shows before its mask, filling the square. Google Play puts its own rounded mask on it.
         */
        @Composable
        fun LauncherIcon() {
            val layers = listOf(BACKGROUND, FOREGROUND).map { rememberVectorPainter(vectorOf(it)) }
            Canvas(Modifier.fillMaxSize()) {
                val full = size.width * CANVAS_DP.toFloat() / SHOWN_DP
                val margin = (full - size.width) / 2
                translate(-margin, -margin) { layers.forEach { with(it) { draw(Size(full, full)) } } }
            }
        }

        /**
         * The feature graphic: the game's name, where to play, and a question revealed, its two answers on
         * cards of the brand's colours (CLAUDE.md §5b) with their shares and bars, card A picked and lifted,
         * on the Classic theme's page and art. Nothing that matters within 15% of an edge, which Play may cover.
         */
        @Composable
        fun FeatureGraphic() {
            val colors = WyrThemeAccessors.colors
            val dimens = WyrThemeAccessors.dimens
            val strings = LocalStrings.current
            OnWholePage {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceSm, Alignment.CenterVertically),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp),
                ) {
                    Text(
                        text = strings.gameName,
                        color = colors.headingAccent,
                        fontSize = WyrTypeScale.gameName,
                        lineHeight = WyrTypeScale.gameNameLineHeight,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = dimens.spaceXs),
                    ) {
                        FeatureCard(FEATURE_QUESTION.optionA, FEATURE_TALLY.percentA, Side.A, picked = true)
                        FeatureCard(FEATURE_QUESTION.optionB, FEATURE_TALLY.percentB, Side.B, picked = false)
                    }
                    Text(
                        text =
                            strings.playScreen.share.invite
                                .fill(GOOGLE_PLAY),
                        color = colors.muted,
                        fontSize = WyrTypeScale.sectionTitle,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }

        /** One answer revealed, as the Play screen's card shows it once its count up has ended. */
        @Composable
        fun RowScope.FeatureCard(
            text: String,
            percent: Int,
            side: Side,
            picked: Boolean,
        ) {
            val colors = WyrThemeAccessors.colors
            val dimens = WyrThemeAccessors.dimens
            val (background, content, track) =
                when (side) {
                    Side.A -> Triple(colors.optionA, colors.onOptionA, colors.revealTrackOnA)
                    Side.B -> Triple(colors.optionB, colors.onOptionB, colors.revealTrackOnB)
                }
            Surface(
                shape = RoundedCornerShape(dimens.radiusCard),
                color = background,
                contentColor = content,
                shadowElevation = if (picked) dimens.pickElevation else 0.dp,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(dimens.spaceSm, Alignment.CenterVertically),
                        modifier = Modifier.fillMaxSize().padding(dimens.spaceMd),
                    ) {
                        Text(
                            text = text,
                            fontSize = WyrTypeScale.optionText,
                            lineHeight = WyrTypeScale.optionLineHeight,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            autoSize =
                                TextAutoSize.StepBased(
                                    minFontSize = WyrTypeScale.optionTextMin,
                                    maxFontSize = WyrTypeScale.optionText,
                                    stepSize = WyrTypeScale.optionTextStep,
                                ),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = LocalStrings.current.playScreen.percent(percent),
                            style = percentStyle(),
                            maxLines = 1,
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(dimens.revealBarHeight)
                            .background(track),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(content))
                    }
                }
            }
        }

        // Seed questions (the server's Seed.kt), their made-up votes, and the player's own vote beside them.
        val FEATURE_QUESTION =
            Question(
                id = "seed-11",
                optionA = "Имати моћ летења",
                optionB = "Имати моћ невидљивости",
                categories = setOf("SUPERPOWERS"),
            )
        val FEATURE_TALLY = Tally(votesA = 319, votesB = 205)

        val ASKED =
            Question(
                id = "seed-15",
                optionA = "Борити се са једном патком величине коња",
                optionB = "Борити се са сто коња величине патке",
                categories = setOf("ABSURD"),
                likeCount = 41,
                dislikeCount = 3,
            )
        val REVEALED =
            Question(
                id = "seed-1",
                optionA = "До краја живота јести само пицу",
                optionB = "До краја живота јести само суши",
                categories = setOf("FOOD"),
                likeCount = 27,
                dislikeCount = 4,
                myReaction = Reaction.LIKE,
            )
        val REVEALED_OUTCOME =
            VoteOutcome(
                yourSide = Side.A,
                tally = Tally(votesA = 213, votesB = 158),
                pointsAwarded = 1,
                totalPoints = POINTS,
            )
        val REVEALED_DARK =
            Question(
                id = "seed-12",
                optionA = "Читати туђе мисли",
                optionB = "Видети недељу дана унапред",
                categories = setOf("SUPERPOWERS"),
                likeCount = 35,
                dislikeCount = 2,
            )
        val REVEALED_DARK_OUTCOME =
            VoteOutcome(
                yourSide = Side.B,
                tally = Tally(votesA = 143, votesB = 232),
                pointsAwarded = 1,
                totalPoints = POINTS,
            )

        /** The server's categories (Seed.ALL_CATEGORIES), in its order. */
        val CATEGORIES =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle"),
                Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                Category(id = "SUPERPOWERS", nameSr = "Супермоћи", nameEn = "Superpowers"),
                Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
                Category(id = "TRAVEL", nameSr = "Путовања", nameEn = "Travel"),
                Category(id = "WORK", nameSr = "Посао", nameEn = "Work"),
                Category(id = "MONEY", nameSr = "Новац", nameEn = "Money"),
                Category(id = "LOVE", nameSr = "Љубав", nameEn = "Love"),
                Category(id = "TECHNOLOGY", nameSr = "Технологија", nameEn = "Technology"),
                Category(id = "SPORTS", nameSr = "Спорт", nameEn = "Sports"),
                Category(id = "ANIMALS", nameSr = "Животиње", nameEn = "Animals"),
                Category(id = "GROSS", nameSr = "Бљак", nameEn = "Yuck"),
            )

        val SUBMISSIONS =
            listOf(
                Submission(
                    id = "s4",
                    optionA = "Имати пса који уме да прича",
                    optionB = "Имати мачку која пише песме",
                    categories = setOf("ANIMALS", "ABSURD"),
                    status = SubmissionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = Instant.parse("2026-09-28T18:10:00Z"),
                ),
                Submission(
                    id = "s3",
                    optionA = "Живети поред мора",
                    optionB = "Живети у планини",
                    categories = setOf("TRAVEL", "LIFESTYLE"),
                    status = SubmissionStatus.APPROVED,
                    rejectionReason = null,
                    submittedAt = Instant.parse("2026-09-26T09:30:00Z"),
                    likeCount = 24,
                    dislikeCount = 3,
                    answerCount = 187,
                ),
                Submission(
                    id = "s2",
                    optionA = "Радити од куће до краја живота",
                    optionB = "Радити у канцеларији са најбољим друштвом",
                    categories = setOf("WORK"),
                    status = SubmissionStatus.APPROVED,
                    rejectionReason = null,
                    submittedAt = Instant.parse("2026-09-24T20:05:00Z"),
                    likeCount = 9,
                    dislikeCount = 1,
                    answerCount = 64,
                ),
            )

        @Composable
        fun ColumnScope.Play(
            state: PlayUiState,
            categories: String,
        ) {
            PlayTopBar(onHome = {}, onAccount = {}) {
                CategoriesPlayed(text = categories, enabled = true, onClick = {})
            }
            Box(Modifier.weight(1f)) {
                PlayScreen(
                    state = state,
                    points = POINTS,
                    onChoose = {},
                    onSkip = {},
                    onNext = {},
                    onReact = {},
                    onRetry = {},
                )
            }
        }

        val SHOTS =
            listOf(
                Shot("01-home") {
                    HomeScreen(
                        picks = Tally(votesA = 1843, votesB = 1291),
                        onPick = {},
                        onPlay = {},
                        onAccount = {},
                        categories = LocalStrings.current.allCategories,
                        onCategories = {},
                    )
                },
                Shot("02-play-asked") { Play(PlayUiState.Asking(ASKED), LocalStrings.current.allCategories) },
                Shot("03-play-revealed") { Play(PlayUiState.Revealed(REVEALED, REVEALED_OUTCOME), "Храна") },
                Shot("04-categories") {
                    BackTopBar(onBack = {})
                    Box(Modifier.weight(1f)) {
                        CategoriesScreen(
                            state =
                                CategoriesState(
                                    ticked = setOf("FOOD", "SUPERPOWERS", "TRAVEL"),
                                    categories = CATEGORIES,
                                    found = CATEGORIES,
                                ),
                            actions = NoCategoriesActions,
                        )
                    }
                },
                Shot("05-account") {
                    AccountTopBar(onBack = {}, onAbout = {})
                    Box(Modifier.weight(1f)) {
                        AccountScreen(
                            state =
                                AccountState(
                                    stats =
                                        PlayerStats(
                                            totalPoints = POINTS,
                                            questionsAnswered = 286,
                                            playGamesLinked = true,
                                        ),
                                    // Signed in with Play Games, as most players on Android are: named by it, no Log out.
                                    playGamesName = "Милица",
                                    submissions = SUBMISSIONS,
                                ),
                            actions = NoAccountActions,
                            environment = WyrEnvironment.PROD,
                            onOpenAuth = {},
                            onNewQuestion = {},
                        )
                    }
                },
                Shot("06-shop") {
                    BackTopBar(onBack = {})
                    Box(Modifier.weight(1f)) {
                        ShopScreen(
                            state =
                                ShopState(
                                    shop =
                                        Shop(
                                            themes =
                                                listOf(
                                                    ShopTheme("NEON_NIGHT", THEME_PRICE, owned = false),
                                                    ShopTheme("OCEAN", THEME_PRICE, owned = true),
                                                    ShopTheme("FOREST", THEME_PRICE, owned = false),
                                                    ShopTheme("SUNSET", THEME_PRICE, owned = false),
                                                ),
                                            points = POINTS,
                                            registered = true,
                                        ),
                                ),
                            actions = NoShopActions,
                            worn = GameThemes.Default,
                            onWear = {},
                            onOpenAuth = {},
                        )
                    }
                },
                Shot("07-play-revealed-dark", dark = true) {
                    Play(PlayUiState.Revealed(REVEALED_DARK, REVEALED_DARK_OUTCOME), "Супермоћи")
                },
                Shot("08-submit") {
                    BackTopBar(onBack = {})
                    Box(Modifier.weight(1f)) {
                        SubmitScreen(
                            state =
                                SubmitState(
                                    optionA = "Цело лето провести на мору",
                                    optionB = "Цело лето провести на планини",
                                    categories = setOf("TRAVEL"),
                                    // The first eight, so the whole form, Send and the rules line under it, fits the phone.
                                    categoryOptions = CATEGORIES.take(8),
                                    points = POINTS,
                                    cost = SUBMISSION_COST,
                                    registered = true,
                                ),
                            actions = NoSubmitActions,
                        )
                    }
                },
            )

        /** What the release charges (CLAUDE.md §8, `THEME_PRICE`, `SUBMISSION_COST`). */
        const val THEME_PRICE = 220
        const val SUBMISSION_COST = 50
    }
}
