# Google Play listing graphics

Drawn by `StoreGraphicsTest` (`app/shared/src/jvmTest/kotlin/io/ntole/wyr/StoreGraphicsTest.kt`) with the
game's own theme, strings and screens, in Serbian Cyrillic, from the questions the server seeds. Never
edit a file here by hand: change the test, or the screen or drawable it draws, and regenerate.

| File | Size | What it shows |
|------|------|---------------|
| `icon-512.png` | 512 × 512, 32-bit PNG (RGBA, opaque), about 24 KB | The launcher icon, full bleed: drawn from `app/androidApp/src/main/res/drawable/launcher_background.xml` and `launcher_foreground.xml` themselves, the middle 72 dp of their 108 dp canvas. Play puts its own rounded mask on it. |
| `feature-graphic.png` | 1024 × 500, 24-bit PNG (no alpha) | *Шта би радије?*, a seed question revealed (*Имати моћ летења* 61% / *Имати моћ невидљивости* 39%) on cards in the brand's colours with their reveal bars, the pick lifted, and *Играј и ти на Google Play*, on the Classic theme's page and art. Nothing within 15% of the left and right edges or 13% of the top and bottom. |
| `screenshots/01-home.png` | 1080 × 1920, 24-bit PNG | Home: the name, the two Play buttons, the categories played. |
| `screenshots/02-play-asked.png` | 1080 × 1920 | Play, a question asked (*Борити се са једном патком величине коња* / *… сто коња величине патке*). |
| `screenshots/03-play-revealed.png` | 1080 × 1920 | Play revealed: 57% / 43%, the pick lifted, liked. |
| `screenshots/04-categories.png` | 1080 × 1920 | The Categories screen, the server's 13 categories, three ticked. |
| `screenshots/05-account.png` | 1080 × 1920 | The Account screen of a player signed in with Play Games, My questions filled (pending, two approved, the total). |
| `screenshots/06-shop.png` | 1080 × 1920 | The shop: the Classic theme worn and Ocean owned, Neon night on sale for 220. |
| `screenshots/07-play-revealed-dark.png` | 1080 × 1920 | Play revealed in the dark theme: 38% / 62%, card B picked. |
| `screenshots/08-submit.png` | 1080 × 1920 | The Submit form, a question written and filed, Send at 50 points (the first eight categories, so the whole form fits). |

The screenshots are 9 : 16 at three pixels a dp, a phone of 360 × 640 dp, since Play refuses a screenshot
whose long side is more than twice its short side. Play takes a JPEG or a 24-bit PNG for every graphic but
the icon, which is a 32-bit PNG under 1 MB.

## Regenerating

From the repository's root:

```sh
WYR_STORE_DIR=$PWD/store/android ./gradlew :app:shared:jvmTest --tests io.ntole.wyr.StoreGraphicsTest --rerun
```

Without `WYR_STORE_DIR` the test draws everything and checks each size and that nothing is transparent,
writing nothing, as every CI run does. Fonts differ between machines (CI's Linux wraps a little wider), so
regenerate on the Mac these were made on, and look at every file before committing it.
