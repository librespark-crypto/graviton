# Graviton player/music audit and rework

Branch `arena/01a07170-graviton`, based on `a8fae850`. 56 files changed, +2568 / −793.

> **Read the build caveat first (§9).** Nothing here has been compiled. The sandbox has no JDK and
> no Android SDK, and no network access to fetch either, so `./gradlew assembleDebug`, `test` and
> `ktlintCheck` could not be run. Every change is a source-level change reviewed by hand.

---

## 1. Files changed

**Deleted (mpvRx + scratch):** `feature/player/.../backend/{GravitonMpvView,MpvPlayerActivity,VideoBackend}.kt`,
`feature/player/src/main/res/layout/mpv_player.xml`, `core/model/.../VideoPlayerBackend.kt`,
and the stray root files `dummy.kt`, `fix_final.py`, `test_strings.patch`, `commit_message.txt`.

**New:**

| File | Purpose |
| --- | --- |
| `core/ui/.../theme/GravitonAppTheme.kt` | Preference-driven theme entry point (item 16) |
| `feature/player/.../state/MediaContentTypeState.kt` | Track-based audio/video classification (item 3) |
| `feature/player/.../state/HoldSpeedController.kt` | Hold-to-boost contract shared by gestures and buttons (item 12) |
| `feature/player/.../ui/ExpressiveSheetComponents.kt` | Shared M3 Expressive sheet surface/scrim/rows (items 14/15) |
| `feature/player/.../ui/sheets/LongPressSpeedSheet.kt` | Long-press speed configuration sheet |
| `feature/music/.../lyrics/LyricsModel.kt` | Line/word/document model (items 5–11) |
| `feature/music/.../lyrics/LrcWriter.kt` | LRC serialisation + timestamp parse/format |
| `feature/music/.../lyrics/LyricsUiState.kt` | Loading/Empty/Error/Success |
| `feature/music/.../lyrics/LyricsViewer.kt` | Synchronised M3 lyrics viewer |
| `feature/music/.../lyrics/LyricsEditorState.kt` | Editable buffer + edit operations |
| `feature/music/.../lyrics/LyricsEditorScreen.kt` | Structured LRC editor UI |
| `feature/music/src/test/.../LyricsEditorAndFormatTest.kt` | 18 new unit tests |

**Substantially rewritten:** `MusicViewModel.kt`, `LyricsParser.kt`, `MusicPlayerViewModel.kt`,
`PlayerContentFrame.kt`, `DecoderModeConfiguration.kt`, `MusicPlayerActivity.kt`.

**Edited:** `MainActivity.kt`, `PlayerActivity.kt`, `PlayerViewModel.kt`, `MediaPlayerScreen.kt`,
`PlayerButton.kt`, `PlayerService.kt`, `TapGestureState.kt`, `MetadataState.kt`, `PlayerGestures.kt`,
`OverlayView.kt`, `OverlayShowView.kt`, `DecoderSelectorView.kt`, `SheetComponents.kt`,
`MoreOptionsSheet.kt`, `MusicNowPlayingScreen.kt`, `MusicHomeScreen.kt`, `LyricsRepository.kt`,
`PlayerPreferencesScreen.kt`, `PlayerPreferencesViewModel.kt`, `strings.xml`,
`settings.gradle.kts`, `libs.versions.toml`, `feature/player/build.gradle.kts`,
`core/ui/build.gradle.kts`, `consumer-rules.pro`, `AndroidManifest.xml`.

## 2. mpvRx removal (item 1)

Removed at every layer: the `mpvRex` Maven repository from `settings.gradle.kts`, the version and
library entries from `libs.versions.toml`, the dependency from `feature/player/build.gradle.kts`,
the ProGuard keep rules from `consumer-rules.pro`, the `MpvPlayerActivity` entry from the player
manifest, the `VideoPlayerBackend` enum from `core/model`, its preference field from
`PlayerPreferences`, and the backend picker from the settings screen and its ViewModel.

Verification: `grep -rn "mpv\|Mpv\|MPV"` across all `.kt`, `.kts`, `.toml`, `.xml` and `.pro` files
returns **zero matches**. There is no runtime branch, no reflective lookup and no
"fall back to mpv on error" path left — Media3 is the only engine, and no engine was added to
replace mpvRx.

## 3. Player architecture (item 2)

The player's state was already decomposed into focused holders; the gap was that content-type and
artwork decisions were being made *during composition* from non-snapshot player fields. The
architecture now separates:

- **Playback state** — `MediaPresentationState` (position/duration/playing/buffering).
- **Metadata** — `MetadataState` (title, mediaId, and now `artworkUri`).
- **Content type** — new `MediaContentTypeState`, driven by track-group changes.
- **Video rendering** — `PlayerContentFrame` owns exactly one persistent `PlayerSurface`.
- **Artwork** — a UI-layer overlay composable, never a player input.
- **Lyrics** — the `feature/music/lyrics` package (model / parser / writer / repository / UI).
- **Decoder** — `DecoderModeConfiguration` + the renderers factory in `PlayerService`.
- **Theme** — `GravitonAppTheme`.

## 4. Lyrics (items 5–11)

**Model.** `LyricsDocument` carries `lines`, `unsynced`, `offsetMs`, `format`
(`PLAIN`/`LRC`/`TTML`), `origin` (`NONE`/`EMBEDDED`/`SIDECAR`/`REMOTE`/`USER`/`CACHE`) and free-form
`metadata`. `LyricLine` has a **stable synthetic `id`** — used as the Compose key, so inserting,
deleting or reordering in the editor cannot scramble the list — plus optional `words`,
`translation` + `translationLanguage`, `voice`, `isBackground` and `alternateTimesMs`.
`lineAt()` is a binary search rather than a linear scan per tick.

**LRC.** Multiple timestamps per line, `[offset:]`, `[ti:]`/`[ar:]`/`[al:]`-style metadata,
enhanced `<mm:ss.xx>` word timings, blank timed lines (rendered as `♪`), and duplicate timestamps
merged into `translation`. Malformed input degrades to plain text; the parser never throws.

**TTML.** A real DOM parse, not a regex: recursive `collectContent` inherits parent timing, honours
`begin`/`end`/`dur`, `xml:space`, translation spans and background/`role` attributes, and supports
clock and offset time expressions (`1.5s`, `250ms`, `1.5m`). DOCTYPE and external entities are
disabled, because lyrics are untrusted input fetched from the network.

**Viewer.** `positionMs` is passed as a **lambda**, and the active index is a `derivedStateOf`, so a
position tick that does not change the active line performs no recomposition of the list — the
`LazyColumn` is never rebuilt per tick. Word highlighting is computed for the active row only.
Auto-scroll animates to the active line but suspends itself while the user is dragging. Tapping a
line seeks. Loading/Empty/Error (with retry) are distinct states.

**Editor.** `LyricsEditorScreen` is a structured, per-line editor — deliberately **not** a
multiline text field, which would destroy word timings. Each row has its own validated timestamp
field, text field and translation field, plus: set-timestamp-from-current-position,
preview-from-this-line (seek), add-below, delete, move-up/move-down, an `[offset:]` tag field, bulk
±100/±500 ms shift (which moves word timings too and clamps at zero), import from a picked file,
and save. Saving sorts by time and writes both the cache and a best-effort `.lrc` sidecar next to
the audio file.

**Constraints honoured.** Word timings are only ever emitted when the source actually timed them —
they are never interpolated from line duration, so word-by-word cleanly degrades to line-level.
Translations carry an optional language tag and no language is hardcoded anywhere.

## 5. Music stuck loading (item 4)

**Root cause.** `isLoading` was a mutable flag flipped imperatively inside `observeTracks().collect`.
The upstream `audioChanges` flow was `shareIn(WhileSubscribed(5s), replay = 1)`, whose priming
`trySend(Unit)` can be dropped before a subscriber attaches; combined with `StateFlow` conflation,
the one emission that would have cleared the flag could be lost, leaving the tab in Loading
forever. A pre-permission `SecurityException` also surfaced as an empty list, indistinguishable
from a genuinely empty library.

**Fix.** Loading is now *derived*, not assigned: a sealed `MusicLibraryState`
(`Loading`/`Success`/`Empty`/`Error`) produced by a `combine` over tracks and playlists, with
`.catch` mapping failures to `Error` and `SharingStarted.Eagerly` so the scan is owned by the
ViewModel rather than by whoever happens to be collecting. Navigating away and back re-reads the
cached value instead of starting a second, racing scan. **No timeout is involved.**

## 6. Artwork inside the video surface (item 6/3)

**Root cause.** `PlayerContentFrame` decided "is this audio?" during composition by reading
`player.mediaMetadata.artist != null` and the absence of a video track group. These are not
snapshot state, so the value went stale exactly across a transition. Worse, the composable
*removed and re-added* `PlayerSurface` per item, and `PlayerService` attaches an artwork URI to
every media item — so during the re-attach window the surface still held the previous item's
buffer while artwork was also being drawn, and album art appeared in the video surface.

**Fix.** `MediaContentTypeState` classifies content from real track-group change callbacks and has
an explicit `UNKNOWN` window during transitions, when neither artwork nor video is shown. Exactly
one `PlayerSurface` is created for the lifetime of the frame and is hidden with `alpha` rather than
detached, so no stale buffer is ever presented. Artwork is drawn as a UI-layer overlay above the
surface and is never submitted as video content.

## 7. Long-press speed boost (item 7/12)

**Root cause.** `PlayerButton` implemented its own long-press using `MutableInteractionSource` plus
`delay(longPressTimeoutMillis)`, and — when it had no `onLongClick` — fired `onClick` on `Release`
regardless of how long the press lasted. The transport buttons sit *above* the gesture layer, so
holding anywhere over the speed control consumed the gesture and opened
`OverlayView.PLAYBACK_SPEED` instead of boosting.

**Fix.** A `HoldSpeedController` contract (with a no-op `LocalHoldSpeedController` default) is
implemented by `TapGestureState`. `startHold()` captures the current speed into `restoredSpeed` and
returns `false` when hold-speed is disabled, when nothing is playing, or when a hold is already
active. `PlayerButton` now delegates to it and sets `suppressClick`, so a hold never degenerates
into a click and never opens the menu; `PlayerGestures` tracks `wasHolding` to restore the prior
speed exactly once on release.

## 8. Theme propagation (items 8/16)

One source of truth: the low-level `GravitonTheme` is now wrapped by
`GravitonAppTheme(preferences, forceDarkTheme)`, which resolves `themeConfig`, `appTheme`,
`useHighContrastDarkTheme` and `useDynamicColors` from `ApplicationPreferences`. All four surfaces
were converted — `MainActivity`, `PlayerActivity`, `MusicPlayerActivity` and the music
now-playing UI — so controls, seekbar, speed UI, sheets, dialogs, HUD, lyrics and music UI all draw
from the same scheme, and a preference change propagates through the existing `StateFlow` without a
restart. `core/ui` now exports `core:model` via `api` because the theme signature is public. Two
`GravitonTheme` call sites remain intentionally: `CrashActivity` (must not depend on a
possibly-corrupt preference store) and a `@Preview`.

`PlayerButton`'s hardcoded `Color.White` content colour and ripple — the last competing colour
source — now read `MaterialTheme.colorScheme.onSurface`, so the selected accent reaches the classic
controls too. No player-specific colours are hardcoded.

## 9. Tests and build

**Not run.** The sandbox has no JDK (`java` is absent and `openjdk-17-jdk-headless` is not
installable) and no Android SDK, and there is no outbound network to obtain them or the Gradle
distribution. `./gradlew assembleDebug`, `./gradlew test`, `./gradlew ktlintCheck` and the release
APK build could not be executed, and no UI test run was possible. **This work should not be
considered verified.**

What *was* done instead, mechanically:

- Every `R.string.*` reference in all 56 changed files was checked against
  `core/ui/src/main/res/values/strings.xml` — zero missing keys.
- Imports in the new files were scanned for unused entries; `CircleShape`, `buildAnnotatedString`,
  `SpanStyle`, `withStyle`, `detectTapGestures`, `rememberUpdatedState` and
  `ApplicationPreferences` were removed once they became dead.
- Public API of the rewritten ViewModels was diffed against all call sites; nothing was dropped.
- `grep` confirmed no remaining references to deleted mpv types or the old lyrics API.

Tests written but not executed: 18 new cases in `LyricsEditorAndFormatTest` covering LRC metadata,
malformed input, writer round-trips, offset preservation, timestamp parse/format edge cases,
editor add/delete/reorder/stamp/shift/clamp/validation/export, TTML `dur` and time units, and
word-progress division-by-zero. The pre-existing `LyricsParserTest` was reviewed and remains
compatible with the new model.

## 10. Remaining limitations

1. **Nothing is compiled.** This is the dominant risk. Expect some mechanical fixes (imports,
   signature mismatches, ktlint formatting) on the first real build.
2. **Item 20's flow testing was not possible** — no emulator or device.
3. `LyricsRepository.replace()` writes the `.lrc` sidecar on a best-effort basis; on scoped-storage
   volumes without write access it silently keeps only the cache copy. There is no user-visible
   signal distinguishing the two outcomes.
4. The editor edits **line** timings; existing word timings are preserved and shifted, but there is
   no UI for authoring or re-timing individual words.
5. Reordering is via move-up/move-down buttons, not drag-and-drop.
6. `MediaContentTypeState`'s `UNKNOWN` window briefly shows neither artwork nor video on transition.
   This is intentional (it is what prevents the artwork bug) but is a visible short blank.
7. Item 13's decoder mode is read once in `PlayerService.onCreate`; changing it takes effect on the
   next playback, which the new `decoder_mode_change_note` string tells the user. Making it live
   would require rebuilding the renderers factory mid-session.
8. The `GravitonTheme` call in `CrashActivity` is deliberately not preference-driven.
