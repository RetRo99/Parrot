# Offline English dictionary — implementation status

## Source and pack

Open English WordNet 2025, derived from Princeton WordNet. Rechecked the upstream
[LICENSE.md](https://github.com/globalwordnet/english-wordnet/blob/main/LICENSE.md)
and [WNDB_License.txt](https://github.com/globalwordnet/english-wordnet/blob/main/WNDB_License.txt)
on 2026-10-05. Commercial redistribution and modification are permitted. Retain
both teams' attribution, copyright/licence notices and disclaimers, link to the
source and CC BY 4.0 licence, and identify modifications. Neither licence requires
share-alike; do not imply Princeton endorsement. These are engineering findings,
not legal advice. The app includes the notices offline.

The generated `tools/dictionary/dist/oewn-2025-1/english.sqlite` is **21,991,424
bytes** (22 MB in the UI), with **74,441 headwords** and **4,463 form mappings**.
SQLite integrity check passed. SHA-256:
`850da5c0f3efea6d911a17908155091230e4867a437cfa61bb3d6249e1efc053`.
It is a separate SQLite database, not merged into the library database.

### Bundled English (approved after the original download-only prompt)

The base pack is versioned at
`lib/dictionary/src/commonMain/composeResources/files/english.sqlite` and packaged
using Compose resources for Android and iOS. `BundledDictionaryPack.kt` prepares
an atomic, checksum-verified local SQLite copy when the reader opens (or on first
lookup), without making an HTTP request. The copy is reused; the index remains
lazy and closes when the reader closes. Allow about 22 MB for the SQLite copy in
addition to the compressed resource inside the application.

Settings identifies English as included in the app. Only downloaded updates can
be removed; removal restores the bundled pack and leaves saved words untouched.
The optional update check/download still requires published release assets.
The base dictionary is no longer blocked by the unpublished download URLs.

The Android APK contains exactly one bundled SQLite resource with the pinned
checksum, compressed to **6,867,137 bytes** (about 6.9 MB), plus both offline
licence notices. The bundled build passed Android dictionary tests/build, iOS
dictionary tests (including real-resource lookup of `mice` → `mouse`), and iOS
reader compilation. It was installed over the existing app on the Xiaomi
`2602BPC18G` and launched successfully without clearing app data.

Bundling changes additionally cover `lib/dictionary/build.gradle.kts` (resource
packaging and a pinned size/checksum build gate), `DictionaryService.kt`,
`BundledDictionaryPackTest.kt`, the dictionary settings UI/strings, and the pack
builder README. Regression tests cover one-time copying, update removal,
corrupt resources, and low-storage handling without reading the resource.

## Continuation changes

- `feature/reader/ui/.../saved/ReaderSavedItems.kt` and
  `feature/saved/domain/.../model/SavedItem.kt`: saved state now checks the actual
  selection anchor and chapter. Saving the same headword at a later occurrence
  remains available and updates the existing item's position instead of creating
  another item. Existing notes and creation time remain intact.
- `feature/saved/domain/.../SavedWordTest.kt`: regression coverage for saved-state
  matching at the current occurrence, not merely the same headword.
- `lib/dictionary/.../DictionaryLookup.kt` and `DictionaryLookupTest.kt`: preserve
  combining marks and letter/digit words; normalise nonbreaking/Unicode hyphens.
  Numeric-only selections and phrases still have no dictionary strip.
- `lib/packs/.../PackTransfer.kt` and `PackTransferTest.kt`: validate resumed
  response bounds, including the final byte and total, before appending.
- `lib/packs/.../PackManager.kt`: checksum verification responds to cancellation.
- `lib/packs/.../PackManagerTest.kt`: cover servers ignoring Range, insufficient
  disk space without network traffic, and schema validation before activation.
- `feature/saved/ui/.../dictionary/DictionaryUi.kt`: keep IPA alongside the
  headword while reserving room for Close; show Try again after download failure.

## Automated verification

Commands from the repository root:

```sh
./gradlew :lib:dictionary:testAndroidHostTest :lib:packs:testAndroidHostTest :feature:saved:domain:testAndroidHostTest :feature:saved:data:testAndroidHostTest :lib:server-parrot-cloud:testAndroidHostTest :lib:database:implementation:testAndroidHostTest :androidApp:assembleDebug
./gradlew :lib:dictionary:iosSimulatorArm64Test :lib:packs:compileKotlinIosSimulatorArm64 :feature:reader:ui:compileKotlinIosSimulatorArm64
```

Both passed during this continuation. iOS verification covers Kotlin reader
compilation and simulator dictionary tests (including opening the separate SQLite
path), not a complete Xcode application build. Gradle reports an existing
Coil/Skiko version mismatch on iOS; runtime validation remains necessary.

## Release gates and outstanding QA

1. **Publish optional updates and their manifest.** HEAD checks on 2026-10-05 returned
   HTTP 404 for both configured URLs:
   `https://github.com/RetRo99/tts-models/releases/download/oewn-2025-1/english.sqlite`
   and `https://github.com/RetRo99/tts-models/releases/download/dictionary/english-manifest.json`.
   Follow `tools/dictionary/README.md`; publish both licence files with the pack.
   No release assets were published during this continuation. These URLs do not
   affect bundled English lookup.
2. **Deploy server support first.**
   `supabase/migrations/20261009000000_parrot_cloud_saved_words.sql` must precede
   clients that upload words. No server migration was deployed here.
3. **Old clients:** the nullable unknown-type decoder fix protects clients that
   include it. It cannot alter already-installed older binaries whose decoder
   falls back to Bookmark. Ship that compatibility fix before enabling word sync
   for mixed-version devices, or add a protocol capability gate.
4. **Manual device QA is not yet certified:** all three themes, toolbar placement
   and selection dismissal, TalkBack/VoiceOver announcements, airplane mode,
   actual interrupted/resumed downloads and removal, update flow, word note/edit/
   delete/Undo, two-device sync, and an old client receiving words.
5. **Performance/privacy:** measure cold/warm lookup on an actual slow e-ink
   device and capture network traffic during selection. Code inspection confirms
   lookup has no network path; that is not a network-capture result.
6. **Pronunciation:** the speaker remains hidden pending approval of
   `design/SPEAK_WORD_REPORT.md`, as requested.
