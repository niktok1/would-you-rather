package io.ntole.wyr.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.home.HOME_COUNT_UP_MILLIS
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.optionText
import io.ntole.wyr.play.CountedUpText
import io.ntole.wyr.play.RevealBar
import io.ntole.wyr.play.rememberCountUp
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import io.ntole.wyr.theme.contentWidth
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A question of the player's own, whole (CLAUDE.md §8d, *Question details*), opened from its row in My
 * questions: each option on a card of its colour, in full, and once the question has been served, how
 * the players who answered it split, each side's share counted up with its bar as the Play screen's
 * reveal does and how many picked it; where it stands, a rejection's reason whole; when it was sent; the
 * categories it is filed under, named from [categories] in the language shown; and, once served, its
 * likes, dislikes and answers. It scrolls. Every colour, space and size from the theme (§5b), every
 * word from [LocalStrings] (§8f).
 */
@Composable
fun QuestionDetailsScreen(
    submission: Submission,
    categories: List<Category>,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.accountScreens
    val language = LocalLanguage.current
    val counts = countsOf(submission)
    val tally = submission.tally
    // Counted up together, as a race, as the Play screen's reveal is, and as quick as the Home screen's.
    val countedA = rememberCountUp(tally.percentA, rival = tally.percentB, durationMillis = HOME_COUNT_UP_MILLIS)
    val countedB = rememberCountUp(tally.percentB, rival = tally.percentA, durationMillis = HOME_COUNT_UP_MILLIS)

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding)
                    .contentWidth(dimens.contentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            OptionCard(
                option = optionText(submission.optionA, language),
                share = if (counts != null) Share(tally.percentA, tally.votesA, countedA) else null,
                fill = colors.optionA,
                onFill = colors.onOptionA,
                track = colors.revealTrackOnA,
            )
            OptionCard(
                option = optionText(submission.optionB, language),
                share = if (counts != null) Share(tally.percentB, tally.votesB, countedB) else null,
                fill = colors.optionB,
                onFill = colors.onOptionB,
                track = colors.revealTrackOnB,
            )

            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                Text(
                    text = statusText(submission, strings),
                    color = colors.headingAccent,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = strings.sentOn.fill(dateText(submission.submittedAt, language)),
                    color = colors.muted,
                    fontSize = WyrTypeScale.statLabel,
                )
            }

            if (submission.categories.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                    Text(
                        text = strings.categories,
                        color = colors.muted,
                        fontSize = WyrTypeScale.statLabel,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = submission.categories.joinToString { id -> categoryName(id, categories, language) },
                        color = colors.primaryText,
                    )
                }
            }

            if (counts != null) Counts(counts, strings)
        }
    }
}

/** One side's share of the players who answered: its whole percent, how many picked it, and the count up. */
private class Share(
    val percent: Int,
    val votes: Long,
    val counted: State<Float>,
)

/**
 * An option on a card of its colour, [fill], its text in [onFill]: the option whole, and for a question
 * served its [share], counted up, how many picked it after two players, and its bar along the card's
 * bottom, filling in [onFill] on a groove of [track].
 */
@Composable
private fun OptionCard(
    option: String,
    share: Share?,
    fill: Color,
    onFill: Color,
    track: Color,
) {
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current

    Surface(color = fill, shape = RoundedCornerShape(dimens.radiusCard), modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(
                modifier = Modifier.padding(dimens.spaceMd),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
            ) {
                Text(
                    text = option,
                    color = onFill,
                    fontSize = WyrTypeScale.optionText,
                    lineHeight = WyrTypeScale.optionLineHeight,
                    fontWeight = FontWeight.Bold,
                )
                if (share != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                    ) {
                        CountedUpText(
                            counted = share.counted,
                            target = share.percent,
                            text = strings.playScreen::percent,
                            style =
                                TextStyle(
                                    color = onFill,
                                    fontSize = WyrTypeScale.percentage,
                                    fontWeight = FontWeight.ExtraBold,
                                ),
                        )
                        Counted(WyrIcons.Players, share.votes, strings.accountScreens.answers, tint = onFill)
                    }
                }
            }
            if (share != null) RevealBar(counted = share.counted, fill = onFill, track = track)
        }
    }
}

/** A served question's likes, dislikes and answers, side by side on a card, each after its icon. */
@Composable
private fun Counts(
    counts: QuestionCounts,
    strings: AccountStrings,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(color = colors.surface, shape = RoundedCornerShape(dimens.radiusCard), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.padding(dimens.spaceMd),
        ) {
            Counted(WyrIcons.ThumbUp, counts.likes.toLong(), strings.likes, tint = colors.primaryText)
            Counted(WyrIcons.ThumbDown, counts.dislikes.toLong(), strings.dislikes, tint = colors.primaryText)
            Counted(WyrIcons.Players, counts.answers.toLong(), strings.answers, tint = colors.primaryText)
        }
    }
}

/** A number after its [icon], in [tint], which a screen reader hears after [name], as the table's are. */
@Composable
private fun Counted(
    icon: ImageVector,
    value: Long,
    name: String,
    tint: Color,
) {
    val dimens = WyrThemeAccessors.dimens

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceXs),
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$name: $value" },
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(dimens.tableIconSize))
        Text(text = value.toString(), color = tint, fontSize = WyrTypeScale.sectionTitle, fontWeight = FontWeight.Bold)
    }
}

/**
 * [instant]'s day on this device, in numbers, as [language] writes a date: *25. 9. 2026.* in Serbian,
 * either script, and *25/9/2026* in English. No letter, so no text of [io.ntole.wyr.language.Strings].
 * UTC's day where the platform cannot name its own time zone, a browser's without the zones' rules.
 */
internal fun dateText(
    instant: Instant,
    language: Language,
    zone: TimeZone = deviceZone(),
): String {
    val date = instant.toLocalDateTime(zone).date
    val (day, month, year) = Triple(date.day, date.month.ordinal + 1, date.year)
    return when (language) {
        Language.SERBIAN_CYRILLIC, Language.SERBIAN_LATIN -> "$day. $month. $year."
        Language.ENGLISH -> "$day/$month/$year"
    }
}

private fun deviceZone(): TimeZone =
    try {
        TimeZone.currentSystemDefault()
    } catch (_: Exception) {
        TimeZone.UTC
    }
