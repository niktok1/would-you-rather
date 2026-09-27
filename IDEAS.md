# Ideas — not scheduled

The user's backlog of feature ideas, kept for after the launch. Nothing here is decided or
built: an idea moves into CLAUDE.md (§8b, then §8d) when it is designed, and out of this file
when it is built or dropped. Each notes what to settle before building it.

## More game modes

For example *Would you X if Y?*, answered yes or no, beside the two-option question. Per §8d's
principle, a new mode is opt-in and never replaces the default. To settle: whether a mode is a
field on a question or a pool of its own, how its tally and reveal look, and how it counts in
cycles and scoring (§8c).

## Comments on questions

Players commenting on a question (§8d, *Reports*, says "later, if at all"). To settle: the
moderator's load, and reporting and blocking for comments as Google Play asks of user content,
as questions have now.

## Advanced filtering of questions

More than the category filter: to settle what by (answered or not, newest, most liked, and the
like) and how it meets the cycle rules (§8d, *Endless feed*).

## Richer stats than the split

A question's split broken down by region, age, gender, occupation and the like. To settle: where
the demographics come from, which is personal data (GDPR, ZZPL, Play's Data safety form); §8f
rejected knowing a player's gender. Region ties to §8b, *Local questions*. See *Inferring
demographics* below.

### Inferring demographics from answers

The user's idea: rather than asking, guess a player's age (say) from how they answer, maybe
with control questions that reveal it. What to know first:

- An inference about a person is personal data as much as a stated fact, so it needs the same
  lawful basis, the privacy policy saying so, and the right to object (GDPR art. 21, since it
  rests on legitimate interest).
- Inferring any special category (religion, politics, health, sexuality) is processing special
  category data, whatever was asked; §8b, *Personalization*, already keeps those out.
- Inferring age has a catch for a 16+ game: a model that concludes a player is under 16 is
  knowledge the game then has to act on.
- A control question aimed at one trait is asking in disguise, which is less transparent than
  asking, not more.
- Guesses are noisy, so splits built on them would show the model's error as if it were a
  difference between groups.

Recommended alternative: an **optional, skippable self-report** (age range, region, and the
like), asked once in the feed or on the Account screen, and each split shown only above a
minimum number of players, so no answer points to one person. Inference stays for
personalization only (§8b), never shown as a label.

## Shop

Buying themes and decorations. Themes are already one `WyrColors` value each (§5b). To settle:
coins only, or real money too, which needs Google Play Billing (a new dependency, §2 and §4)
and Apple's in-app purchase on iOS.

## Rewarded ads for coins

Watch an ad, earn coins. To settle: an ad SDK (AdMob, say: an Android-only exception like
§2's, and something else on iOS and the web), the Data safety form and privacy policy, and how
ad coins meet the 50-point submission cost (§8c) and farming limits (§8b, *Rate limiting*).
