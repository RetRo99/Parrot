# Parrot Ink Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Parrot's current Material 3 green look with the "Parrot Ink" design system: grayscale-first, outline-based, no motion on e-ink, a green accent on color screens, and Literata + Atkinson Hyperlegible type.

**Architecture:** All tokens (colors, type, shapes, e-ink flag, motion) live in `base-ui` and are exposed through `ParrotTheme` and `MaterialTheme`. Feature screens only read `MaterialTheme.colorScheme`, `MaterialTheme.typography`, `MaterialTheme.shapes`, `LocalEink` and a few new shared components. Feature screens never hard-code colors. A new "E-ink mode" app setting forces the e-ink theme so it can be tested on a normal emulator.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.11 (`org.jetbrains.compose.material3` 1.11.0-alpha07), Compose resources (`composeResources/font`), Koin, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-09-29-parrot-ink-design.html` (open it in a browser). The "On a regular Android phone" section shows color screens. The "Screens" section shows e-ink. The "Compose mapping" table gives the role → hex values. This plan copies every value you need, so you don't have to read the spec to implement it.

## Global Constraints

Every task must follow these, in addition to its own steps.

**Project rules (from the user's CLAUDE.md):**
- Max line length 100. 4-space indent. No wildcard imports. Trailing commas everywhere.
- Lambdas use named parameters, never `it` (e.g. `.onEach { enabled -> … }`).
- Imports sorted: `android.*`, `androidx.*`, third-party (`com.*`, `io.*`, `org.*`), project (`com.retro99.*`, `resources.*`), `java.*`, `kotlin.*`/`kotlinx.*`.
- All user-facing strings go in `translations/src/commonMain/composeResources/values/strings.xml`, **added at the bottom**, each with `tools:ignore="MissingTranslation"`.
- **Never commit without asking.** Before the first commit, ask the user: "OK to commit after each task of the Parrot Ink plan?" If they say yes, commit at the end of each task as written. If they say no, skip every commit step.
- Commit messages end with a blank line and then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Design tokens (exact values):**

| Token | E-ink | Color light | Color dark |
|---|---|---|---|
| paper (background, surface, all surfaceContainer*) | `#FFFFFF` | `#FFFFFF` | `#111111` |
| wash (surfaceVariant) | `#EEEEEE` | `#EEEEEE` | `#222222` |
| rule (outlineVariant) | `#BBBBBB` | `#BBBBBB` | `#555555` |
| ink-2 (onSurfaceVariant) | `#555555` | `#555555` | `#AAAAAA` |
| ink (onBackground, onSurface, outline) | `#111111` | `#111111` | `#EEEEEE` |
| primary / onPrimary | `#111111` / `#FFFFFF` | `#2E6B52` / `#FFFFFF` | `#8FD6B4` / `#073826` |
| secondaryContainer / onSecondaryContainer (selected states) | `#111111` / `#FFFFFF` | `#DCEFE5` / `#111111` | `#1E3A2E` / `#EEEEEE` |
| error / onError | `#111111` / `#FFFFFF` | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` |

- Every e-ink color must be a pure gray whose channel value is a multiple of `0x11` (one of the 16 levels on a 4-bit panel).
- No shadows or tonal elevation: `surfaceTint` = paper, and every `tonalElevation`/`shadowElevation` in restyled code is `0.dp`.
- Stroke widths: component outline `2.dp`, divider `1.dp`.
- Shapes: extraSmall 4dp, small 6dp, medium 12dp, large 16dp, extraLarge 28dp.
- Touch targets ≥ 48dp. The audiobook play/pause button is 72dp.

**Typography:**

| Role | Face | Weight | Size / line height (sp) |
|---|---|---|---|
| displayLarge / displayMedium / displaySmall | Literata | Bold | 48/56, 40/48, 32/38 |
| headlineLarge / headlineMedium / headlineSmall | Literata | SemiBold | 30/36, 27/34, 24/30 |
| titleLarge | Literata | SemiBold | 22/28 |
| titleMedium / titleSmall | Atkinson Hyperlegible | Bold | 17/24, 15/20 |
| bodyLarge / bodyMedium / bodySmall | Atkinson Hyperlegible | Normal | 17/26, 15/22, 13/18 |
| labelLarge / labelMedium / labelSmall | Atkinson Hyperlegible | Bold | 15/20, 12/16, 11/14 |
| Numeric (custom, `ParrotTextStyles.Numeric`) | `FontFamily.Monospace` | Medium | 13/16, `fontFeatureSettings = "tnum"` |

Only Normal (400), Medium (500), SemiBold (600) and Bold (700) weights are allowed. Never use Light or Thin.

**Restyle rules (R1–R9).** Screen tasks 6–11 apply these to the files they list.

- **R1 Tinted fills.** Replace any `SomeColor.copy(alpha = …)` used as a container or background color with a solid token: `MaterialTheme.colorScheme.surface` for cards (and add a 2dp `outline` border), or `MaterialTheme.colorScheme.surfaceVariant` for a filled area. Replace alpha-tinted **text/icon** colors with `MaterialTheme.colorScheme.onSurfaceVariant`. **Allowed exceptions:** scrims drawn over cover images, and `Color.Transparent`/`alpha = 0f` fades. Leave those alone.
- **R2 Cards.** Replace `Card(…)`/`ElevatedCard(…)` with `OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline), shape = MaterialTheme.shapes.medium)`. Remove `elevation = …` arguments.
- **R3 Motion.** Every `AnimatedVisibility` gets `enter = InkMotion.enter(<existing or default enter>)` and `exit = InkMotion.exit(<existing or default exit>)`. Every `animate*AsState` gets `animationSpec = InkMotion.spec(<existing spec or tween()>)`. Every `Crossfade`/`AnimatedContent` uses `InkMotion.spec(...)` for its `animationSpec`/`transitionSpec` duration where it has one. These helpers return a no-op on e-ink (see Task 3).
- **R4 Progress.** Book reading/listening progress bars (`LinearProgressIndicator` with a `progress` value) become `SegmentedProgress(progress = …)`. Indeterminate spinners (`CircularProgressIndicator()` with no progress) become `ParrotLoading()`. Keep determinate download progress as `LinearProgressIndicator`, but pass `gapSize = 0.dp` and `drawStopIndicator = {}` if those parameters exist, and set `trackColor = MaterialTheme.colorScheme.surfaceVariant`.
- **R5 Selected state.** Selected chips, tabs and nav items use `secondaryContainer`/`onSecondaryContainer`. Unselected chips use `surface` with a 2dp `outline` border. Delete any other selected-state colors.
- **R6 Numbers.** Timecodes, percentages, page counts, durations and stats values use `ParrotTextStyles.Numeric` (merge size: `ParrotTextStyles.Numeric.copy(fontSize = …)` if a bigger size is needed).
- **R7 Icon tint.** Icons inside rows, top bars and list items use `onSurface` (or leave default `LocalContentColor`). Only the primary action on a screen uses `primary`.
- **R8 Shapes.** Replace `RoundedCornerShape(12.dp)`/`(16.dp)` on cards with `MaterialTheme.shapes.medium`, and on buttons/chips/small boxes with `MaterialTheme.shapes.small`. `CircleShape` and pill shapes (`RoundedCornerShape(50)`/`percent = 50`) stay.
- **R9 Dominant color backdrops.** Cover-derived colored backgrounds (`rememberDominantColorState` + `backdropColorScheme`) are **off on e-ink**. On color screens they stay.

**Verification commands** (every task):
- Unit tests: `./gradlew :base-ui:testAndroidHostTest` (added in Task 1), plus the module's own host tests if the task says so.
- Build: `./gradlew :androidApp:assembleDebug` (must end in `BUILD SUCCESSFUL`).
- Visual check (screen tasks): install and screenshot on an emulator in four modes: color light, color dark, e-ink (Settings → E-ink mode, added in Task 4). Commands:
  ```bash
  ./gradlew :androidApp:installDebug
  adb shell cmd uimode night no
  adb exec-out screencap -p > /tmp/parrot-light.png
  adb shell cmd uimode night yes
  adb exec-out screencap -p > /tmp/parrot-dark.png
  ```
  Look at every screenshot. Fail the task if you see: tinted/translucent cards, a shadow, purple or pink (Material default) colors, clipped text, or anything green in e-ink mode.

**Out of scope** (do not implement; list them in the final report): volume-key page turning for lists, forced full e-ink refresh every N page turns (needs vendor SDKs), bundling IBM Plex Mono (we use system monospace), iOS visual verification (it must compile, but nobody screenshots it).

---

## File Map

| File | Responsibility |
|---|---|
| `base-ui/build.gradle.kts` | Add host tests + commonTest deps |
| `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkPalette.kt` (new) | Raw color constants |
| `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkColorSchemes.kt` (new) | The three `ColorScheme`s + `parrotColorScheme()` |
| `base-ui/src/commonMain/composeResources/font/*.ttf` (new) | Literata (variable), Atkinson Hyperlegible Regular + Bold |
| `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkTypography.kt` (new) | `inkTypography()` + `ParrotTextStyles` |
| `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkMotion.kt` (new) | `LocalEink`, `InkMotion` helpers |
| `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/ParrotTheme.kt` | Wire everything; delete old schemes |
| `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkComponents.kt` (new) | `SegmentedProgress`, `MediaBadge`, `ParrotLoading`, `Modifier.inkOutline` |
| `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/ParrotComponents.kt` | Restyle `SettingsSection`/`SettingsRow` |
| `base-ui/src/commonTest/kotlin/com/retro99/base/ui/compose/*Test.kt` (new) | Unit tests |
| `lib/preferences/api/.../Preferences.kt` | New `ForceEinkMode` key |
| `feature/home/ui/.../appsettings/*` | E-ink mode toggle |
| `composeApp/src/commonMain/kotlin/com/retro99/parrot/App.kt` | Read the toggle, pass to `ParrotTheme` |
| Feature screen files (Tasks 6–11) | Apply R1–R9 |

---

### Task 1: Test setup and color schemes

**Files:**
- Modify: `base-ui/build.gradle.kts`
- Create: `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkPalette.kt`
- Create: `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkColorSchemes.kt`
- Test: `base-ui/src/commonTest/kotlin/com/retro99/base/ui/compose/InkColorSchemesTest.kt`

**Interfaces:**
- Produces: `object InkPalette` (constants below); `val InkEinkColorScheme: ColorScheme`; `val InkLightColorScheme: ColorScheme`; `val InkDarkColorScheme: ColorScheme`; `fun parrotColorScheme(darkTheme: Boolean, eink: Boolean): ColorScheme`.

- [ ] **Step 1: Enable host tests in base-ui**

In `base-ui/build.gradle.kts`, inside `androidLibrary { … }` add `withHostTest {}` after the `androidResources { … }` block. Inside `sourceSets { … }` after `commonMain.dependencies { … }` add:

```kotlin
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
```

(This mirrors `composeApp/build.gradle.kts` lines 21 and 112–119.)

- [ ] **Step 2: Write the failing test**

Create `base-ui/src/commonTest/kotlin/com/retro99/base/ui/compose/InkColorSchemesTest.kt`:

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkColorSchemesTest {

    @Test
    fun `e-ink scheme uses only the 16 panel gray levels`() {
        // Given
        val colors = InkEinkColorScheme.allRoles()

        // Then
        colors.forEach { (role, color) ->
            val red = (color.red * 255).roundToInt()
            val green = (color.green * 255).roundToInt()
            val blue = (color.blue * 255).roundToInt()
            assertTrue(red == green && green == blue, "$role is not gray: $color")
            assertEquals(0, red % 0x11, "$role is not a 4-bit gray level: $color")
        }
    }

    @Test
    fun `e-ink selected state is inverted ink`() {
        assertEquals(InkPalette.Ink, InkEinkColorScheme.secondaryContainer)
        assertEquals(InkPalette.Paper, InkEinkColorScheme.onSecondaryContainer)
        assertEquals(InkPalette.Ink, InkEinkColorScheme.primary)
    }

    @Test
    fun `color schemes use parrot accent and neutral surfaces`() {
        assertEquals(InkPalette.Parrot, InkLightColorScheme.primary)
        assertEquals(InkPalette.Paper, InkLightColorScheme.surface)
        assertEquals(InkPalette.Paper, InkLightColorScheme.surfaceContainer)
        assertEquals(InkPalette.ParrotNight, InkDarkColorScheme.primary)
        assertEquals(InkPalette.NightPaper, InkDarkColorScheme.surface)
        assertEquals(InkPalette.NightPaper, InkDarkColorScheme.surfaceContainer)
    }

    @Test
    fun `e-ink wins over dark theme`() {
        assertSame(InkEinkColorScheme, parrotColorScheme(darkTheme = true, eink = true))
        assertSame(InkEinkColorScheme, parrotColorScheme(darkTheme = false, eink = true))
        assertSame(InkDarkColorScheme, parrotColorScheme(darkTheme = true, eink = false))
        assertSame(InkLightColorScheme, parrotColorScheme(darkTheme = false, eink = false))
    }

    private fun ColorScheme.allRoles(): List<Pair<String, Color>> = listOf(
        "primary" to primary,
        "onPrimary" to onPrimary,
        "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer,
        "secondary" to secondary,
        "onSecondary" to onSecondary,
        "secondaryContainer" to secondaryContainer,
        "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary,
        "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer,
        "background" to background,
        "onBackground" to onBackground,
        "surface" to surface,
        "onSurface" to onSurface,
        "surfaceVariant" to surfaceVariant,
        "onSurfaceVariant" to onSurfaceVariant,
        "surfaceTint" to surfaceTint,
        "inverseSurface" to inverseSurface,
        "inverseOnSurface" to inverseOnSurface,
        "inversePrimary" to inversePrimary,
        "error" to error,
        "onError" to onError,
        "errorContainer" to errorContainer,
        "onErrorContainer" to onErrorContainer,
        "outline" to outline,
        "outlineVariant" to outlineVariant,
        "surfaceBright" to surfaceBright,
        "surfaceDim" to surfaceDim,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainerLowest" to surfaceContainerLowest,
    )
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :base-ui:testAndroidHostTest`
Expected: compilation FAILS with `Unresolved reference 'InkEinkColorScheme'` (and similar). If Gradle says the task doesn't exist, run `./gradlew :base-ui:tasks --all | grep -i hosttest` and use the task name it prints for the rest of this plan.

- [ ] **Step 4: Create `InkPalette.kt`**

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.ui.graphics.Color

/**
 * Raw Parrot Ink colors. Light and e-ink neutrals are 4-bit gray levels (multiples of 0x11)
 * so e-ink panels never dither them. Screens should use MaterialTheme.colorScheme, not these.
 */
object InkPalette {
    val Paper = Color(0xFFFFFFFF)
    val Wash = Color(0xFFEEEEEE)
    val Rule = Color(0xFFBBBBBB)
    val Ink2 = Color(0xFF555555)
    val Ink = Color(0xFF111111)

    val NightPaper = Color(0xFF111111)
    val NightWash = Color(0xFF222222)
    val NightRule = Color(0xFF555555)
    val NightInk2 = Color(0xFFAAAAAA)
    val NightInk = Color(0xFFEEEEEE)

    val Parrot = Color(0xFF2E6B52)
    val ParrotSoft = Color(0xFFDCEFE5)
    val ParrotNight = Color(0xFF8FD6B4)
    val OnParrotNight = Color(0xFF073826)
    val ParrotSoftNight = Color(0xFF1E3A2E)

    val ErrorLight = Color(0xFFBA1A1A)
    val ErrorContainerLight = Color(0xFFFFDAD6)
    val OnErrorContainerLight = Color(0xFF410002)
    val ErrorDark = Color(0xFFFFB4AB)
    val OnErrorDark = Color(0xFF690005)
    val ErrorContainerDark = Color(0xFF93000A)
    val OnErrorContainerDark = Color(0xFFFFDAD6)
}
```

- [ ] **Step 5: Create `InkColorSchemes.kt`**

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

/** Grayscale-only scheme for e-ink panels. Selected states invert to solid ink. */
val InkEinkColorScheme: ColorScheme = lightColorScheme(
    primary = InkPalette.Ink,
    onPrimary = InkPalette.Paper,
    primaryContainer = InkPalette.Wash,
    onPrimaryContainer = InkPalette.Ink,
    secondary = InkPalette.Ink,
    onSecondary = InkPalette.Paper,
    secondaryContainer = InkPalette.Ink,
    onSecondaryContainer = InkPalette.Paper,
    tertiary = InkPalette.Ink,
    onTertiary = InkPalette.Paper,
    tertiaryContainer = InkPalette.Wash,
    onTertiaryContainer = InkPalette.Ink,
    background = InkPalette.Paper,
    onBackground = InkPalette.Ink,
    surface = InkPalette.Paper,
    onSurface = InkPalette.Ink,
    surfaceVariant = InkPalette.Wash,
    onSurfaceVariant = InkPalette.Ink2,
    surfaceTint = InkPalette.Paper,
    inverseSurface = InkPalette.Ink,
    inverseOnSurface = InkPalette.Paper,
    inversePrimary = InkPalette.Paper,
    error = InkPalette.Ink,
    onError = InkPalette.Paper,
    errorContainer = InkPalette.Wash,
    onErrorContainer = InkPalette.Ink,
    outline = InkPalette.Ink,
    outlineVariant = InkPalette.Rule,
    scrim = InkPalette.Ink,
    surfaceBright = InkPalette.Paper,
    surfaceDim = InkPalette.Wash,
    surfaceContainer = InkPalette.Paper,
    surfaceContainerHigh = InkPalette.Paper,
    surfaceContainerHighest = InkPalette.Wash,
    surfaceContainerLow = InkPalette.Paper,
    surfaceContainerLowest = InkPalette.Paper,
)

/** Color-screen light scheme: same neutrals as e-ink, Parrot green accent. */
val InkLightColorScheme: ColorScheme = lightColorScheme(
    primary = InkPalette.Parrot,
    onPrimary = InkPalette.Paper,
    primaryContainer = InkPalette.ParrotSoft,
    onPrimaryContainer = InkPalette.Ink,
    secondary = InkPalette.Parrot,
    onSecondary = InkPalette.Paper,
    secondaryContainer = InkPalette.ParrotSoft,
    onSecondaryContainer = InkPalette.Ink,
    tertiary = InkPalette.Parrot,
    onTertiary = InkPalette.Paper,
    tertiaryContainer = InkPalette.ParrotSoft,
    onTertiaryContainer = InkPalette.Ink,
    background = InkPalette.Paper,
    onBackground = InkPalette.Ink,
    surface = InkPalette.Paper,
    onSurface = InkPalette.Ink,
    surfaceVariant = InkPalette.Wash,
    onSurfaceVariant = InkPalette.Ink2,
    surfaceTint = InkPalette.Paper,
    inverseSurface = InkPalette.Ink,
    inverseOnSurface = InkPalette.Paper,
    inversePrimary = InkPalette.ParrotNight,
    error = InkPalette.ErrorLight,
    onError = InkPalette.Paper,
    errorContainer = InkPalette.ErrorContainerLight,
    onErrorContainer = InkPalette.OnErrorContainerLight,
    outline = InkPalette.Ink,
    outlineVariant = InkPalette.Rule,
    scrim = InkPalette.Ink,
    surfaceBright = InkPalette.Paper,
    surfaceDim = InkPalette.Wash,
    surfaceContainer = InkPalette.Paper,
    surfaceContainerHigh = InkPalette.Paper,
    surfaceContainerHighest = InkPalette.Wash,
    surfaceContainerLow = InkPalette.Paper,
    surfaceContainerLowest = InkPalette.Paper,
)

/** Color-screen dark scheme: near-black paper (avoids OLED smear), mint accent. */
val InkDarkColorScheme: ColorScheme = darkColorScheme(
    primary = InkPalette.ParrotNight,
    onPrimary = InkPalette.OnParrotNight,
    primaryContainer = InkPalette.ParrotSoftNight,
    onPrimaryContainer = InkPalette.NightInk,
    secondary = InkPalette.ParrotNight,
    onSecondary = InkPalette.OnParrotNight,
    secondaryContainer = InkPalette.ParrotSoftNight,
    onSecondaryContainer = InkPalette.NightInk,
    tertiary = InkPalette.ParrotNight,
    onTertiary = InkPalette.OnParrotNight,
    tertiaryContainer = InkPalette.ParrotSoftNight,
    onTertiaryContainer = InkPalette.NightInk,
    background = InkPalette.NightPaper,
    onBackground = InkPalette.NightInk,
    surface = InkPalette.NightPaper,
    onSurface = InkPalette.NightInk,
    surfaceVariant = InkPalette.NightWash,
    onSurfaceVariant = InkPalette.NightInk2,
    surfaceTint = InkPalette.NightPaper,
    inverseSurface = InkPalette.NightInk,
    inverseOnSurface = InkPalette.NightPaper,
    inversePrimary = InkPalette.Parrot,
    error = InkPalette.ErrorDark,
    onError = InkPalette.OnErrorDark,
    errorContainer = InkPalette.ErrorContainerDark,
    onErrorContainer = InkPalette.OnErrorContainerDark,
    outline = InkPalette.NightInk,
    outlineVariant = InkPalette.NightRule,
    scrim = InkPalette.NightPaper,
    surfaceBright = InkPalette.NightWash,
    surfaceDim = InkPalette.NightPaper,
    surfaceContainer = InkPalette.NightPaper,
    surfaceContainerHigh = InkPalette.NightPaper,
    surfaceContainerHighest = InkPalette.NightWash,
    surfaceContainerLow = InkPalette.NightPaper,
    surfaceContainerLowest = InkPalette.NightPaper,
)

/** Picks the scheme for the current device. E-ink always wins over dark theme. */
fun parrotColorScheme(darkTheme: Boolean, eink: Boolean): ColorScheme = when {
    eink -> InkEinkColorScheme
    darkTheme -> InkDarkColorScheme
    else -> InkLightColorScheme
}
```

If `lightColorScheme`/`darkColorScheme` reject a parameter name (e.g. `surfaceBright` missing in this Material version), remove that argument **and** the matching line in the test's `allRoles()`. Don't rename anything else.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :base-ui:testAndroidHostTest`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 7: Commit** (only if the user approved commits)

```bash
git add base-ui/build.gradle.kts base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkPalette.kt base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkColorSchemes.kt base-ui/src/commonTest
git commit -m "Add Parrot Ink color schemes"
```

---

### Task 2: Fonts and typography

**Files:**
- Create: `base-ui/src/commonMain/composeResources/font/literata.ttf`
- Create: `base-ui/src/commonMain/composeResources/font/atkinson_hyperlegible_regular.ttf`
- Create: `base-ui/src/commonMain/composeResources/font/atkinson_hyperlegible_bold.ttf`
- Create: `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkTypography.kt`
- Modify: `docs/THIRD_PARTY_FONTS.md`

**Interfaces:**
- Produces: `@Composable fun inkTypography(): Typography`; `object ParrotTextStyles { val Numeric: TextStyle }`.

- [ ] **Step 1: Add the font files**

```bash
mkdir -p base-ui/src/commonMain/composeResources/font
cp feature/reader/ui/src/androidMain/assets/reader-fonts/bundled/Literata.ttf base-ui/src/commonMain/composeResources/font/literata.ttf
cp feature/reader/ui/src/androidMain/assets/reader-fonts/bundled/AtkinsonHyperlegible-Regular.ttf base-ui/src/commonMain/composeResources/font/atkinson_hyperlegible_regular.ttf
curl -fL -o base-ui/src/commonMain/composeResources/font/atkinson_hyperlegible_bold.ttf https://github.com/google/fonts/raw/main/ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Bold.ttf
file base-ui/src/commonMain/composeResources/font/*.ttf
```

Expected: `file` reports all three as `TrueType Font data`. If the curl fails, stop and ask the user to download the Atkinson Hyperlegible Bold TTF from Google Fonts. Don't substitute another font.

`literata.ttf` is a variable font. The Compose resources `Font(resource, weight)` sets its weight axis automatically.

- [ ] **Step 2: Create `InkTypography.kt`**

The resource accessors are generated in package `resources.icons` (see `compose.resources { packageOfResClass = "resources.icons" }` in `base-ui/build.gradle.kts`). After a build they live in `base-ui/build/generated/compose/resourceGenerator/`. If an import below doesn't resolve, open that folder and copy the exact names from it.

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import resources.icons.Res
import resources.icons.atkinson_hyperlegible_bold
import resources.icons.atkinson_hyperlegible_regular
import resources.icons.literata

/** Fixed-width digits for timecodes, page counts and stats, so numbers don't shift while updating. */
object ParrotTextStyles {
    val Numeric = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    )
}

/** Parrot Ink type scale: Literata for reading and headings, Atkinson Hyperlegible for UI. */
@Composable
fun inkTypography(): Typography {
    val reading = FontFamily(
        Font(Res.font.literata, FontWeight.Normal),
        Font(Res.font.literata, FontWeight.SemiBold),
        Font(Res.font.literata, FontWeight.Bold),
    )
    val ui = FontFamily(
        Font(Res.font.atkinson_hyperlegible_regular, FontWeight.Normal),
        Font(Res.font.atkinson_hyperlegible_bold, FontWeight.Bold),
    )

    fun style(family: FontFamily, weight: FontWeight, size: Int, lineHeight: Int) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
    )

    return Typography(
        displayLarge = style(reading, FontWeight.Bold, 48, 56),
        displayMedium = style(reading, FontWeight.Bold, 40, 48),
        displaySmall = style(reading, FontWeight.Bold, 32, 38),
        headlineLarge = style(reading, FontWeight.SemiBold, 30, 36),
        headlineMedium = style(reading, FontWeight.SemiBold, 27, 34),
        headlineSmall = style(reading, FontWeight.SemiBold, 24, 30),
        titleLarge = style(reading, FontWeight.SemiBold, 22, 28),
        titleMedium = style(ui, FontWeight.Bold, 17, 24),
        titleSmall = style(ui, FontWeight.Bold, 15, 20),
        bodyLarge = style(ui, FontWeight.Normal, 17, 26),
        bodyMedium = style(ui, FontWeight.Normal, 15, 22),
        bodySmall = style(ui, FontWeight.Normal, 13, 18),
        labelLarge = style(ui, FontWeight.Bold, 15, 20),
        labelMedium = style(ui, FontWeight.Bold, 12, 16),
        labelSmall = style(ui, FontWeight.Bold, 11, 14),
    )
}
```

- [ ] **Step 3: Update the font licence doc**

In `docs/THIRD_PARTY_FONTS.md`, add at the end:

```markdown

## App interface fonts

The app UI (all platforms) bundles Literata and Atkinson Hyperlegible (Regular and Bold) from
Google Fonts, SIL Open Font License 1.1. Files live under
`base-ui/src/commonMain/composeResources/font`.
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :base-ui:compileAndroidMain :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (`inkTypography()` isn't used yet; Task 3 wires it in.) If `:base-ui:compileAndroidMain` doesn't exist, run just `:androidApp:assembleDebug`.

- [ ] **Step 5: Commit** (only if approved)

```bash
git add base-ui/src/commonMain/composeResources/font base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkTypography.kt docs/THIRD_PARTY_FONTS.md
git commit -m "Add Parrot Ink typography and bundled UI fonts"
```

---

### Task 3: Theme wiring, e-ink flag and motion

**Files:**
- Create: `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkMotion.kt`
- Modify: `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/ParrotTheme.kt` (replace whole file)
- Modify: `feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/SettingsScreen.kt:78,237`
- Test: `base-ui/src/commonTest/kotlin/com/retro99/base/ui/compose/InkMotionTest.kt`

**Interfaces:**
- Consumes: `parrotColorScheme(darkTheme, eink)` (Task 1), `inkTypography()` (Task 2).
- Produces:
  - `val LocalEink: ProvidableCompositionLocal<Boolean>` (default `false`)
  - `object InkMotion { fun enter(eink: Boolean, default: EnterTransition): EnterTransition; fun exit(eink: Boolean, default: ExitTransition): ExitTransition; fun <T> spec(eink: Boolean, default: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> }` plus `@Composable` overloads `enter(default)`, `exit(default)`, `spec(default)` that read `LocalEink.current`.
  - `val InkShapes: Shapes`
  - `ParrotTheme(darkTheme: Boolean = isSystemInDarkTheme(), eink: Boolean = false, content)`, with the same signature as today.

- [ ] **Step 1: Write the failing test**

`base-ui/src/commonTest/kotlin/com/retro99/base/ui/compose/InkMotionTest.kt`:

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlin.test.Test
import kotlin.test.assertEquals

class InkMotionTest {

    @Test
    fun `e-ink removes enter and exit transitions`() {
        assertEquals(EnterTransition.None, InkMotion.enter(eink = true, default = fadeIn()))
        assertEquals(ExitTransition.None, InkMotion.exit(eink = true, default = fadeOut()))
    }

    @Test
    fun `color screens keep the default transitions`() {
        // Given
        val enter = fadeIn()
        val exit = fadeOut()

        // Then
        assertEquals(enter, InkMotion.enter(eink = false, default = enter))
        assertEquals(exit, InkMotion.exit(eink = false, default = exit))
    }

    @Test
    fun `e-ink animation spec snaps immediately`() {
        assertEquals(snap<Float>(), InkMotion.spec(eink = true, default = tween<Float>(300)))
        assertEquals(tween<Float>(300), InkMotion.spec(eink = false, default = tween<Float>(300)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :base-ui:testAndroidHostTest`
Expected: FAIL, `Unresolved reference 'InkMotion'`. If `compose.animation` isn't on the classpath, add `implementation(compose.animation)` to `base-ui` `commonMain.dependencies` and re-run. It must fail on `InkMotion`, not on imports.

- [ ] **Step 3: Create `InkMotion.kt`**

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** True when the app renders for an e-ink panel (detected device or the user's E-ink mode). */
val LocalEink: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * Motion that turns itself off on e-ink, where every animation frame is a slow, ghosting
 * refresh. Wrap every transition and animation spec in these helpers.
 */
object InkMotion {
    fun enter(eink: Boolean, default: EnterTransition): EnterTransition =
        if (eink) EnterTransition.None else default

    fun exit(eink: Boolean, default: ExitTransition): ExitTransition =
        if (eink) ExitTransition.None else default

    fun <T> spec(eink: Boolean, default: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (eink) snap() else default

    @Composable
    fun enter(default: EnterTransition): EnterTransition = enter(LocalEink.current, default)

    @Composable
    fun exit(default: ExitTransition): ExitTransition = exit(LocalEink.current, default)

    @Composable
    fun <T> spec(default: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        spec(LocalEink.current, default)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :base-ui:testAndroidHostTest`
Expected: PASS (7 tests total).

- [ ] **Step 5: Replace `ParrotTheme.kt`**

Replace the whole file with the code below. This deletes `EinkColorScheme`, `ParrotLightColorScheme`, `ParrotDarkColorScheme` and `ParrotTypography`. First run `grep -rn "EinkColorScheme\|ParrotLightColorScheme\|ParrotDarkColorScheme\|ParrotTypography" --include='*.kt' . | grep -v /build/` and confirm that only `ParrotTheme.kt` matches. If another file matches, change it to use `MaterialTheme.colorScheme` / `MaterialTheme.typography`.

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * App-wide Parrot Ink theme. E-ink devices get the grayscale scheme, no ripples and no motion
 * (read [LocalEink] / use [InkMotion]); color screens get the Parrot green accent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParrotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    eink: Boolean = false,
    content: @Composable () -> Unit,
) {
    val rippleConfiguration = if (eink) null else LocalRippleConfiguration.current

    CompositionLocalProvider(
        LocalEink provides eink,
        LocalRippleConfiguration provides rippleConfiguration,
    ) {
        MaterialTheme(
            colorScheme = parrotColorScheme(darkTheme = darkTheme, eink = eink),
            typography = inkTypography(),
            shapes = InkShapes,
            content = content,
        )
    }
}
```

If `LocalRippleConfiguration` is not found in this Material version, remove those lines and the `@OptIn`. Then add a note in your task report that ripples stay on for e-ink. Don't write a custom indication.

- [ ] **Step 6: Use `LocalEink` in SettingsScreen**

In `feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/SettingsScreen.kt`:
- Replace the import `import com.retro99.base.ui.platform.isEinkDisplay` with `import com.retro99.base.ui.compose.LocalEink` (keep import order).
- Replace `val animationsEnabled = remember { !isEinkDisplay() }` with `val animationsEnabled = !LocalEink.current`.

Then run `grep -rn "isEinkDisplay()" --include='*.kt' feature | grep -v /build/`. Expected: no output.

- [ ] **Step 7: Build and look**

Run: `./gradlew :base-ui:testAndroidHostTest :androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
Install and screenshot the Books tab in light and dark (see Verification commands). Expected: white/near-black backgrounds (no green-gray tint), headings in a serif, and the UI text in Atkinson. Screens still have old tinted cards; that's fixed in later tasks.

- [ ] **Step 8: Commit** (only if approved)

```bash
git add base-ui feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/SettingsScreen.kt
git commit -m "Wire Parrot Ink theme, e-ink flag and motion helpers"
```

---

### Task 4: "E-ink mode" setting

Lets anyone force the e-ink theme on a normal phone. This is needed to test every later task on an emulator.

**Files:**
- Modify: `lib/preferences/api/src/commonMain/kotlin/com/retro99/preferences/api/Preferences.kt` (add key)
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/appsettings/AppSettingsViewState.kt`
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/appsettings/AppSettingsIntent.kt`
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/appsettings/AppSettingsViewModel.kt`
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/appsettings/AppSettingsScreen.kt` (near line 375)
- Modify: `translations/src/commonMain/composeResources/values/strings.xml` (bottom)
- Modify: `composeApp/src/commonMain/kotlin/com/retro99/parrot/App.kt`
- Test: `feature/home/ui/src/commonTest/kotlin/com/retro99/home/ui/appsettings/AppSettingsViewStateTest.kt` (existing; read it first)

**Interfaces:**
- Consumes: `ParrotTheme(eink = …)` (Task 3).
- Produces: `PreferencesKey.ForceEinkMode`; `AppSettingsViewState.forceEinkMode: Boolean = false`; `AppSettingsIntent.OnForceEinkModeToggled(enabled: Boolean)`.

Follow the existing **Show Continue Reading** toggle exactly. It is the template for every step below: `PreferencesKey.ShowContinueReading`, `AppSettingsViewModel.kt:58-59` (observe), `:137` (intent dispatch), `:524-528` (setter), and `AppSettingsScreen.kt:375-377` (the switch row).

- [ ] **Step 1: Add the preference key**

In `Preferences.kt`, below `data object ShowContinueReading : PreferencesKey("ShowContinueReading")`, add:

```kotlin
    data object ForceEinkMode : PreferencesKey("ForceEinkMode")
```

- [ ] **Step 2: Write a failing view-state test**

Open `AppSettingsViewStateTest.kt` and look at its style. Add:

```kotlin
    @Test
    fun `force e-ink mode is off by default`() {
        // When
        val state = AppSettingsViewState()

        // Then
        assertEquals(false, state.forceEinkMode)
    }
```

(Add `import kotlin.test.assertEquals` if missing. If the constructor needs arguments, copy them from an existing test in the file.)

Run: `./gradlew :feature:home:ui:testAndroidHostTest` (if that task name doesn't exist, find it with `./gradlew :feature:home:ui:tasks --all | grep -i test`).
Expected: FAIL, `Unresolved reference 'forceEinkMode'`.

- [ ] **Step 3: Add state, intent and ViewModel handling**

- `AppSettingsViewState.kt`: add `val forceEinkMode: Boolean = false,` next to `showContinueReading`.
- `AppSettingsIntent.kt`: add `data class OnForceEinkModeToggled(val enabled: Boolean) : AppSettingsIntent`.
- `AppSettingsViewModel.kt`:
  - Next to the `observeBooleanPref(PreferencesKey.ShowContinueReading, …)` call, add:
    ```kotlin
    observeBooleanPref(PreferencesKey.ForceEinkMode, defaultValue = false) { enabled ->
        updateState { lastState -> lastState.copy(forceEinkMode = enabled) }
    }
    ```
  - In the intent `when`, add `is AppSettingsIntent.OnForceEinkModeToggled -> setForceEinkMode(intent.enabled)`.
  - Next to `setShowContinueReading`, add:
    ```kotlin
    private fun setForceEinkMode(enabled: Boolean) {
        preferences.putBoolean(PreferencesKey.ForceEinkMode, enabled)
        updateState { lastState -> lastState.copy(forceEinkMode = enabled) }
    }
    ```
    No analytics event. (Adding one would require analytics sanitizer changes, which this plan doesn't cover.)

Run the home UI tests again. Expected: PASS.

- [ ] **Step 4: Add strings** (at the bottom of `strings.xml`, before `</resources>`)

```xml
    <string name="app_settings_eink_mode" tools:ignore="MissingTranslation">E-ink mode</string>
    <string name="app_settings_eink_mode_description" tools:ignore="MissingTranslation">Grayscale, no animation. Turns on automatically on e-ink devices.</string>
    <string name="general_loading" tools:ignore="MissingTranslation">Loading…</string>
```

(`general_loading` is used by `ParrotLoading` in Task 5.)

- [ ] **Step 5: Add the switch row**

In `AppSettingsScreen.kt`, copy the Show Continue Reading switch row (around line 375) and paste the copy right after it. In the copy, use `app_settings_eink_mode` for the title, `app_settings_eink_mode_description` for the subtitle (if the row supports one), `viewState.forceEinkMode` for `isChecked`, and dispatch `AppSettingsIntent.OnForceEinkModeToggled(enabled)`.

- [ ] **Step 6: Read the setting in `App.kt`**

Replace `App.kt` with:

```kotlin
package com.retro99.parrot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.ParrotTheme
import com.retro99.parrot.navigation.RootNavigation
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import org.koin.compose.koinInject

@Composable
fun App(onRootWelcomeBack: (() -> Unit)? = null) {
    val platform = getPlatform()
    val preferences = koinInject<Preferences>()
    val forceEinkFlow = remember(preferences) {
        preferences.observeBoolean(PreferencesKey.ForceEinkMode)
    }
    val forceEink by forceEinkFlow.collectAsState(
        initial = preferences.getBoolean(PreferencesKey.ForceEinkMode),
    )

    ParrotTheme(eink = platform.isEink || forceEink) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            RootNavigation(
                onRootWelcomeBack = onRootWelcomeBack,
                modifier = Modifier
                    .navigationBarsPadding(),
            )
        }
    }
}
```

Keep import order as written (`androidx`, then `com.retro99`, then `org.koin`). If ktlint in this repo puts `org.*` before `com.retro99.*`, follow what the neighbouring files do.

- [ ] **Step 7: Build and check it manually**

Run: `./gradlew :androidApp:assembleDebug :androidApp:installDebug`. Open Settings, turn on E-ink mode, and screenshot. Expected: the whole app switches to black/white/gray immediately, with no green anywhere. Turn it off: green returns.

- [ ] **Step 8: Commit** (only if approved)

```bash
git add lib/preferences feature/home/ui translations composeApp/src/commonMain/kotlin/com/retro99/parrot/App.kt
git commit -m "Add E-ink mode setting"
```

---

### Task 5: Shared components

**Files:**
- Create: `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/InkComponents.kt`
- Modify: `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/ParrotComponents.kt` (`SettingsSection` ~line 118–145, `SettingsRow` icon tint ~line 175)
- Test: `base-ui/src/commonTest/kotlin/com/retro99/base/ui/compose/SegmentedProgressTest.kt`

**Interfaces:**
- Consumes: `LocalEink` (Task 3), `ParrotTextStyles` (Task 2), string `general_loading` (Task 4).
- Produces:
  - `fun filledSegments(progress: Float, segments: Int): Int`
  - `@Composable fun SegmentedProgress(progress: Float, modifier: Modifier = Modifier, segments: Int = 10)`
  - `@Composable fun MediaBadge(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false)`
  - `@Composable fun ParrotLoading(modifier: Modifier = Modifier)`
  - `@Composable fun Modifier.inkOutline(shape: Shape = MaterialTheme.shapes.medium): Modifier`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.retro99.base.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentedProgressTest {

    @Test
    fun `filled segments round down so a segment fills only when reached`() {
        assertEquals(0, filledSegments(progress = 0f, segments = 10))
        assertEquals(0, filledSegments(progress = 0.09f, segments = 10))
        assertEquals(1, filledSegments(progress = 0.1f, segments = 10))
        assertEquals(6, filledSegments(progress = 0.62f, segments = 10))
        assertEquals(10, filledSegments(progress = 1f, segments = 10))
    }

    @Test
    fun `out of range progress is clamped`() {
        assertEquals(0, filledSegments(progress = -0.5f, segments = 10))
        assertEquals(10, filledSegments(progress = 1.7f, segments = 10))
        assertEquals(0, filledSegments(progress = Float.NaN, segments = 10))
    }
}
```

Run `./gradlew :base-ui:testAndroidHostTest` → FAIL (`Unresolved reference 'filledSegments'`).

- [ ] **Step 2: Create `InkComponents.kt`**

```kotlin
package com.retro99.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_loading

/** Number of filled segments for [progress] in 0..1. Rounds down; NaN counts as 0. */
fun filledSegments(progress: Float, segments: Int): Int {
    if (progress.isNaN()) return 0
    return (progress.coerceIn(0f, 1f) * segments + 0.0001f).toInt().coerceIn(0, segments)
}

/**
 * Book progress as [segments] cells. Redraws at most [segments] times per book, which keeps
 * e-ink refreshes rare.
 */
@Composable
fun SegmentedProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    segments: Int = 10,
) {
    val filled = filledSegments(progress, segments)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(segments) { index ->
            val isFilled = index < filled
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(
                        color = if (isFilled) colors.primary else colors.surface,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isFilled) colors.primary else colors.outlineVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ),
            )
        }
    }
}

/** Small uppercase format label (EBOOK, AUDIO, READALOUD). [emphasized] fills it. */
@Composable
fun MediaBadge(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (emphasized) colors.primary else colors.surface
    val content = if (emphasized) colors.onPrimary else colors.onSurface
    Text(
        text = text.uppercase(),
        style = ParrotTextStyles.Numeric.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        color = content,
        modifier = modifier
            .background(container, MaterialTheme.shapes.extraSmall)
            .border(1.5.dp, if (emphasized) colors.primary else colors.outline, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    )
}

/** Loading state. A static label on e-ink (a spinner would refresh the panel forever). */
@Composable
fun ParrotLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (LocalEink.current) {
            Text(
                text = stringResource(StringRes.general_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
        }
    }
}

/** 2dp outline in the theme's outline color; the Parrot Ink replacement for elevation. */
@Composable
fun Modifier.inkOutline(shape: Shape = MaterialTheme.shapes.medium): Modifier =
    border(width = 2.dp, color = MaterialTheme.colorScheme.outline, shape = shape)
```

Wrap any line over 100 characters (for example the `.border(…)` call in `MediaBadge`) with one argument per line and trailing commas. `ParrotComponents.kt` shows how `StringRes` and translation imports work (`import com.retro99.translations.StringRes`, `import resources.translations.general_back`).

- [ ] **Step 3: Run tests**

`./gradlew :base-ui:testAndroidHostTest` → PASS.

- [ ] **Step 4: Restyle `SettingsSection` and `SettingsRow`**

In `ParrotComponents.kt`:
- `SettingsSection`: header text style becomes `MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp)` with `text = title.uppercase()` and `color = MaterialTheme.colorScheme.onSurfaceVariant`. Replace the `Card(…)` with:
  ```kotlin
  OutlinedCard(
      modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
      shape = MaterialTheme.shapes.medium,
      colors = CardDefaults.outlinedCardColors(
          containerColor = MaterialTheme.colorScheme.surface,
      ),
      border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
  ) {
  ```
  Add imports `androidx.compose.foundation.BorderStroke`, `androidx.compose.material3.OutlinedCard`, `androidx.compose.ui.unit.sp`, and remove the now-unused `Card` and `RoundedCornerShape` imports.
- `SettingsRow`: icon `tint = MaterialTheme.colorScheme.onSurface` (was `primary`). Ensure the row has `Modifier.heightIn(min = 56.dp)` (add `import androidx.compose.foundation.layout.heightIn`).
- If `ParrotComponents.kt` draws `HorizontalDivider` between rows, give it `thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant`.

- [ ] **Step 5: Build and look**

`./gradlew :androidApp:assembleDebug :androidApp:installDebug`. Screenshot the Settings tab in color light, color dark and e-ink. Expected: settings groups are white/near-black boxes with a solid 2dp outline and no gray tint; section headers are small uppercase gray labels.

- [ ] **Step 6: Commit** (only if approved)

```bash
git add base-ui
git commit -m "Add Parrot Ink shared components and restyle settings sections"
```

---

### Task 6: Home navigation, Continue Reading button, mini player

**Files:**
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/HomeNavigation.kt`
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/ContinueReadingButton.kt`
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/MiniPlayer.kt`
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/DraggableFloatingBubble.kt`

**Interfaces:**
- Consumes: `LocalEink`, `InkMotion`, `SegmentedProgress`, `ParrotTextStyles`, `Modifier.inkOutline` from `com.retro99.base.ui.compose`.

- [ ] **Step 1: Bottom navigation bar**

In `HomeNavigation.kt` find `NavigationBar(`. Set `containerColor = MaterialTheme.colorScheme.surface`, `tonalElevation = 0.dp`. Draw a 2dp top border by adding `HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)` directly above the `NavigationBar` (inside the same `Column`/bottomBar slot). For each `NavigationBarItem(`, pass:

```kotlin
colors = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onSurface,
    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurface,
    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
),
```

(`import androidx.compose.material3.NavigationBarItemDefaults`, `androidx.compose.material3.HorizontalDivider`.)

- [ ] **Step 2: Continue Reading button and mini player**

Apply R1, R3, R4, R6 and R7 to `ContinueReadingButton.kt`, `MiniPlayer.kt` and `DraggableFloatingBubble.kt`. Specifically:
- `ContinueReadingButton.kt:175` (`trackColor = …onPrimary.copy(alpha…)`), `:181`, `:226`: replace them per R1. If the button's progress is a `LinearProgressIndicator(progress = …)`, replace it with `SegmentedProgress(progress = <same value>)`.
- Any percentage/time text → `style = ParrotTextStyles.Numeric`.
- Any `AnimatedVisibility` / `animate*AsState` → R3.
- Floating bubble / mini player surfaces: `Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline))`.

- [ ] **Step 3: Check that nothing was missed**

```bash
grep -n "copy(alpha\|shadowElevation = [1-9]\|tonalElevation = [1-9]\|Elevated" feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/*.kt
```

Expected: no output, or only R1 exceptions (scrims over images, `alpha = 0f`). Write the remaining lines and why they're allowed in your task report.

- [ ] **Step 4: Build, install, screenshot**

`./gradlew :androidApp:assembleDebug :androidApp:installDebug`. Screenshot the Books tab (bottom bar and Continue Reading visible) and the mini player (start an audiobook first) in color light, color dark and e-ink. Compare with the spec's "Books tab" phones. Expected: selected tab has a soft-green pill on color and a black pill with white icon on e-ink.

- [ ] **Step 5: Commit** (only if approved)

```bash
git add feature/home/ui
git commit -m "Restyle home navigation for Parrot Ink"
```

---

### Task 7: Books list, series, authors

**Files:**
- Modify: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/components/BookComponents.kt` (lines ~144, ~426 tinted; cards)
- Modify: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/list/BooksListScreen.kt`
- Modify: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/series/SeriesListScreen.kt` (~311)
- Modify: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/series/detail/SeriesDetailScreen.kt`
- Modify: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/authors/AuthorsListScreen.kt` (~225)
- Modify: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/authors/detail/AuthorDetailScreen.kt`

**Interfaces:**
- Consumes: `MediaBadge`, `SegmentedProgress`, `ParrotLoading`, `InkMotion`, `ParrotTextStyles`, `Modifier.inkOutline`.

- [ ] **Step 1: Book list rows and grid cards (`BookComponents.kt`)**

- Media-type indicators (eBook / Audio / ReadALoud chips or icons) → `MediaBadge(text = stringResource(StringRes.books_media_…))`, with `emphasized = true` for Readaloud only.
- Cover images: add `.border(2.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)` and clip to `MaterialTheme.shapes.extraSmall`.
- Book titles use `MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp)` in list rows (Literata). Authors use `bodyMedium` + `onSurfaceVariant`.
- List rows are separated by `HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)` rather than cards. If rows are currently wrapped in `Card`, remove the card.
- Grid cards: R2.
- Apply R1 to lines ~144 and ~426, R4 to progress, and R6 to percentages.

- [ ] **Step 2: Filter chips, search, sort (`BooksListScreen.kt`)**

- Every `FilterChip(` gets:
  ```kotlin
  colors = FilterChipDefaults.filterChipColors(
      containerColor = MaterialTheme.colorScheme.surface,
      labelColor = MaterialTheme.colorScheme.onSurface,
      selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
      selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
      selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
  ),
  border = FilterChipDefaults.filterChipBorder(
      enabled = true,
      selected = <the chip's selected value>,
      borderColor = MaterialTheme.colorScheme.outline,
      selectedBorderColor = MaterialTheme.colorScheme.secondaryContainer,
      borderWidth = 2.dp,
      selectedBorderWidth = 2.dp,
  ),
  shape = CircleShape,
  ```
  and a leading check icon when selected (`leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null`).
- Search field: `OutlinedTextField` with `focusedBorderColor = outline`, `unfocusedBorderColor = outline`, `shape = MaterialTheme.shapes.small`.
- Screen title uses `MaterialTheme.typography.displaySmall` if it is a large header, or `ParrotTopBar` if it already uses one.
- Replace full-screen `CircularProgressIndicator()` with `ParrotLoading(Modifier.fillMaxSize())`. Apply R3 to animations.

- [ ] **Step 3: Series and authors screens**

Apply R1–R8 to the four series/authors files. Series and author tiles are either outlined cards (R2) or divider-separated rows, matching whatever the file already uses.

- [ ] **Step 4: Check that nothing was missed**

```bash
grep -n "copy(alpha\|ElevatedCard\|elevation = \|CircularProgressIndicator()" feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/{components,list,series,authors}/**/*.kt feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/{components,list,series,authors}/*.kt
```

Expected: only R1 exceptions. List them in the report.

- [ ] **Step 5: Build, run books tests, screenshot**

`./gradlew :androidApp:assembleDebug` and, if `feature/books/ui/src/commonTest` exists, `./gradlew :feature:books:ui:testAndroidHostTest`. Then install and screenshot Books (list and grid view), Series and Authors in color light, color dark and e-ink. Compare with the spec's Library phones.

- [ ] **Step 6: Commit** (only if approved)

```bash
git add feature/books/ui
git commit -m "Restyle library, series and authors for Parrot Ink"
```

---

### Task 8: Book detail

**Files:**
- Modify: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/BookDetailScreen.kt` (~276 backdrop, ~422, ~1758 tinted)

**Interfaces:**
- Consumes: `LocalEink`, `MediaBadge`, `SegmentedProgress`, `ParrotTextStyles`.

- [ ] **Step 1: Turn off the cover backdrop on e-ink (R9)**

At ~line 275 change:

```kotlin
val hasBackdrop = book.coverUrl != null
```

to:

```kotlin
val hasBackdrop = book.coverUrl != null && !LocalEink.current
```

(`import com.retro99.base.ui.compose.LocalEink`.) Leave `rememberDominantColorState` as is.

- [ ] **Step 2: Layout and components**

- Title: `MaterialTheme.typography.headlineSmall`. Author + year: `bodyMedium`, `onSurfaceVariant`.
- Format badges → `MediaBadge` (Readaloud emphasized).
- Reading progress → `SegmentedProgress` with a `Row` below it: percentage and "Ch. X of Y" in `ParrotTextStyles.Numeric` (use whatever values/strings the screen already shows; don't add new strings).
- Primary action (`books_detail_action_continue_reading` / `_read`) is a filled `Button` with `shape = MaterialTheme.shapes.small`, `Modifier.heightIn(min = 48.dp)`. The secondary action (`_listen` / `_continue_listening`) is an `OutlinedButton` with `border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)` and the same shape/height. Put them side by side in a `Row` with `Arrangement.spacedBy(8.dp)` and `Modifier.weight(1f)` each.
- Description body text: `MaterialTheme.typography.bodyLarge.copy(fontFamily = MaterialTheme.typography.titleLarge.fontFamily)` (Literata for reading text).
- Tag chips: `AssistChip` with `border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)` and `shape = CircleShape`.
- R1 at ~422 and ~1758; R3 for show more/less animations.

- [ ] **Step 3: Check, build, screenshot**

`grep -n "copy(alpha" feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/BookDetailScreen.kt` → only R1 exceptions.
`./gradlew :androidApp:assembleDebug :androidApp:installDebug`. Open a book with a cover. Screenshot in color light (colored backdrop still there), color dark and e-ink (no backdrop, plain white). Compare with the spec's "Book detail · light" phone.

- [ ] **Step 4: Commit** (only if approved)

```bash
git add feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/BookDetailScreen.kt
git commit -m "Restyle book detail for Parrot Ink"
```

---

### Task 9: Audiobook player

**Files:**
- Modify: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/audiobook/AudioPlayerContent.kt` (~136 backdrop, ~165 tinted)
- Modify: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/audiobook/AudiobookPlayerScreen.kt` (only if it has styling)

**Interfaces:**
- Consumes: `LocalEink`, `InkMotion`, `ParrotTextStyles`.

- [ ] **Step 1: Backdrop (R9)**

Find the `hasBackdrop` value near line 136 and add `&& !LocalEink.current` exactly as in Task 8 Step 1.

- [ ] **Step 2: Controls**

- Play/pause: a `FilledIconButton` (or the existing button) sized `Modifier.size(72.dp)`, `shape = CircleShape`, container `primary`, icon `onPrimary`, icon size 32dp.
- Skip back/forward buttons: at least 52dp, icon 30dp, tint `onSurface`.
- Timecodes (elapsed / remaining) → `ParrotTextStyles.Numeric`.
- Seek slider: `Slider(colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant))`.
- Speed / sleep timer / chapter controls: three equal outlined boxes in a `Row` (`Modifier.weight(1f).inkOutline(MaterialTheme.shapes.small).padding(vertical = 8.dp)`), value on top in `ParrotTextStyles.Numeric`, label below in `labelSmall` + `onSurfaceVariant`. Keep their existing click handlers.
- Title `headlineSmall` (Literata); chapter line `bodyMedium` + `onSurfaceVariant`.
- R1 at ~165; R3 for any animation.

- [ ] **Step 3: Check, build, screenshot**

`grep -n "copy(alpha" feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/audiobook/*.kt` → only R1 exceptions.
Build, install, play an audiobook, and screenshot in color light, color dark and e-ink. Compare with the spec's "Audiobook player" phones. On e-ink the elapsed time must not animate or fade between ticks.

- [ ] **Step 4: Commit** (only if approved)

```bash
git add feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/audiobook
git commit -m "Restyle audiobook player for Parrot Ink"
```

---

### Task 10: Reader chrome and readaloud highlight

**Files:**
- Modify: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/ReaderScreen.kt` (~665, ~1109 tinted)
- Modify: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/ReadAloudControls.kt`
- Modify: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/TableOfContentsSheet.kt`
- Modify: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/PositionConflictDialog.kt`

**Interfaces:**
- Consumes: `LocalEink`, `InkMotion`, `ParrotTextStyles`, `ParrotLoading`.

The book text is rendered by the reader engine with its own reader themes and fonts (user settings). **Don't change reader page themes or book fonts.** Only restyle the app chrome around the page, and the readaloud highlight if it is set from Kotlin.

- [ ] **Step 1: Chrome**

- Top and bottom reader bars: `Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 0.dp)` with a 1dp `outlineVariant` divider on the edge facing the page. Replace `surface.copy(alpha = 0.95f)` (~1109) with solid `surface`. Replace ~665 per R1.
- Page number / percentage / time text → `ParrotTextStyles.Numeric`, `onSurfaceVariant`.
- Chrome show/hide `AnimatedVisibility` → R3 (instant on e-ink).
- `ReadAloudControls.kt`: same control rules as Task 9 Step 2 (play 56dp+ here, since it's a compact bar).
- `TableOfContentsSheet.kt`: current chapter row uses `secondaryContainer`/`onSecondaryContainer` (R5). Sheet `containerColor = surface`, `tonalElevation = 0.dp`.
- `PositionConflictDialog.kt`: R2 for any card. Dialog buttons: confirm = filled, dismiss = `TextButton`.

- [ ] **Step 2: Readaloud highlight**

Search: `grep -rn "highlight" feature/reader/ui/src/commonMain --include='*.kt' | grep -i "color"`.
If the highlight color is chosen in Kotlin, then when `LocalEink.current` is true use `#DDDDDD` (a 4-bit gray level) as the background. On color screens use `MaterialTheme.colorScheme.primaryContainer`. If it's defined only in CSS/JS assets or the reader engine, leave it and say so in the report.

- [ ] **Step 3: Check, build, screenshot**

`grep -n "copy(alpha" feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/{ReaderScreen,ReadAloudControls,TableOfContentsSheet,PositionConflictDialog}.kt` → only R1 exceptions.
Build, install, open a readaloud book, show the chrome, play readaloud, and screenshot in color light, color dark and e-ink. Compare with the spec's "Reader · readaloud" phones.

- [ ] **Step 4: Commit** (only if approved)

```bash
git add feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader
git commit -m "Restyle reader chrome for Parrot Ink"
```

---

### Task 11: Settings, statistics, login, cloud account, voice settings

**Files:**
- Modify: `feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/SettingsScreen.kt` (~1353)
- Modify: `feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/servers/ServerManagementScreen.kt` (~180, ~248)
- Modify: `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/appsettings/AppSettingsScreen.kt` (~799, ~812)
- Modify: `feature/statistics/ui/src/commonMain/kotlin/com/retro99/statistics/ui/StatisticsScreen.kt` (~398)
- Modify: `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/welcome/WelcomeScreen.kt` (~271, ~324)
- Modify: `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/login/LoginScreen.kt`
- Modify: `feature/cloud-account/ui/src/commonMain/kotlin/com/retro99/cloudaccount/ui/CloudAccountScreen.kt`
- Modify: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/VoiceSettingsScreen.kt` (~621, ~1167)

**Interfaces:**
- Consumes: `SettingsSection`, `SettingsRow` (restyled in Task 5), `InkMotion`, `ParrotTextStyles`, `ParrotLoading`, `Modifier.inkOutline`.

- [ ] **Step 1: Settings-like screens**

In `SettingsScreen.kt`, `ServerManagementScreen.kt`, `AppSettingsScreen.kt`, `CloudAccountScreen.kt` and `VoiceSettingsScreen.kt`, apply R1, R2, R3, R5, R7 and R8. Switches:

```kotlin
colors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
),
```

`VoiceSettingsScreen.kt:1167` (`surface.copy(alpha = 0f)`) is a fade edge. It's an R1 exception, so leave it.

- [ ] **Step 2: Statistics**

- Stat values (time read, streak, sessions) → `ParrotTextStyles.Numeric.copy(fontSize = 22.sp, lineHeight = 26.sp)`, labels `bodySmall` + `onSurfaceVariant`.
- Chart bars: solid `onSurface` fill; today's/current bar `primary`. Grid lines `outlineVariant`, 1dp. Axis labels `ParrotTextStyles.Numeric` + `onSurfaceVariant`. No gradients or transparency.
- R1 at ~398, R2 for cards, R3 for animated bars (bars appear instantly on e-ink).

- [ ] **Step 3: Login and welcome**

Apply R1 (~271, ~324 in WelcomeScreen), R2 and R3. Welcome headline → `displaySmall` (Literata). Primary buttons filled with `shapes.small`, 48dp min height. Text fields as in Task 7 Step 2.

- [ ] **Step 4: Check, build, screenshot**

```bash
grep -rn "copy(alpha" --include='*.kt' feature base-ui | grep -v /build/
```

Expected: only R1 exceptions and `DominantColor.kt` (that file builds the color-screen backdrop, so leave it). List every remaining line with its reason in the report.

Build, run `./gradlew :base-ui:testAndroidHostTest`, install, and screenshot Settings, App settings, Servers, Statistics, Welcome and Login in color light, color dark and e-ink.

- [ ] **Step 5: Commit** (only if approved)

```bash
git add feature
git commit -m "Restyle settings, statistics and login for Parrot Ink"
```

---

### Task 12: Final sweep

**Files:** none new, unless the checks find something.

- [ ] **Step 1: Repo-wide checks**

```bash
grep -rn "copy(alpha" --include='*.kt' feature base-ui composeApp | grep -v /build/
grep -rn "ElevatedCard\|shadowElevation = [1-9]\|tonalElevation = [1-9]" --include='*.kt' feature base-ui | grep -v /build/
grep -rn "Color(0x" --include='*.kt' feature | grep -v /build/
grep -rn "FontWeight.Light\|FontWeight.Thin\|FontWeight.ExtraLight" --include='*.kt' feature base-ui | grep -v /build/
grep -rn "isEinkDisplay()" --include='*.kt' feature | grep -v /build/
```

Expected: the first only shows the R1 exceptions you listed; every other command prints nothing, **except** `Color(0x` inside reader page themes / user color pickers (those are user content, so leave them). Fix anything else.

- [ ] **Step 2: Full build and tests**

```bash
./gradlew :base-ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :composeApp:testAndroidHostTest :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Also compile iOS: `./gradlew :composeApp:compileKotlinIosSimulatorArm64` → `BUILD SUCCESSFUL`.

- [ ] **Step 3: Screenshot tour**

With E-ink mode off (light), off (dark) and on, screenshot: Books, Book detail, Reader with chrome, Audiobook player, Series, Authors, Settings, Statistics. Report any screen that still shows purple/pink defaults, translucency, shadows, or green in e-ink mode, and fix it before finishing.

- [ ] **Step 4: Report**

Tell the user:
- which tasks are done, with test/build results,
- the R1 exceptions left and why,
- anything skipped (e.g. ripple configuration unavailable, readaloud highlight in the reader engine),
- the out-of-scope items from Global Constraints.

- [ ] **Step 5: Commit** (only if approved and there are changes)

```bash
git add -A
git commit -m "Finish Parrot Ink sweep"
```
