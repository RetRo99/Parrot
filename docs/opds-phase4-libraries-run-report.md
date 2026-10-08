# Phase 4, run 6 — Libraries and catalogue settings

Touched-module tests, all twenty fixture captures, Android debug assembly, and iOS simulator framework linking passed. This report was committed before starting the final app builds, then updated with their results.

```text
--- REPORT ---
Worktree path and branch:
/Users/rokretar/StudioProjects/Parrot-opds-libraries — opds/phase4-libraries (base deb13f8d). Nothing pushed or merged; main checkout untouched.

Commits this run (hash + subject, oldest first):
24f0938e test(catalogue): specify Libraries statuses and settings lifecycle
6b8933d4 test(catalogue): cover settings commit races and safe routes
8478e39d feat(catalogue): add profile-fenced settings and validated editors
29acd2d9 feat(catalogue): render Ember library cards and settings dialogs
edd95175 feat(settings): wire catalogue Libraries actions and safe settings routes
692d3c86 docs(opds): record Libraries run before final app builds
abf3906e test(fixtures): add nine production-backed Libraries boards
062c8d42 docs(design): capture Libraries boards on authorized Samsung
The final report-update commit is recorded in git history and the final response.

Test command(s) run:
All Gradle commands used ANDROID_HOME="$HOME/Library/Android/sdk" and --max-workers=2.
Test-first/red and incremental retries: :feature:catalogue:ui:testAndroidHostTest; then catalogue/settings host tests; then catalogue/settings/home host tests with :tools:ember-fixtures:testDebugUnitTest :tools:ember-fixtures:assembleDebug, using --continue.
Final successful module command: ./gradlew :feature:catalogue:ui:testAndroidHostTest :feature:settings:ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :feature:catalogue:ui:iosSimulatorArm64Test :feature:settings:ui:iosSimulatorArm64Test :feature:home:ui:iosSimulatorArm64Test :translations:iosSimulatorArm64Test :tools:ember-fixtures:testDebugUnitTest :tools:ember-fixtures:assembleDebug --max-workers=2 --continue
The initial SDK-location failure was retried with ANDROID_HOME. A Kotlin compiler GC-overhead OOM during settings compilation was retried successfully without changing Gradle memory settings or weakening tests.

Per module: feature/catalogue/ui Android host 87/87, iOS 86/86
Per module: feature/settings/ui Android host 20/20, iOS 20/20
Per module: feature/home/ui Android host 84/84, iOS 90/90
Per module: translations Android host 0/0 (host-test target not configured), iOS 0/0 (no test source, task SKIPPED); resources compiled on both platforms through the UI modules.
Per module: tools/ember-fixtures Android host 0/0 (testDebugUnitTest NO-SOURCE), iOS n/a (Android-only module); debug APK assembled successfully.

App build results (Android assemble, iOS framework):
Android :androidApp:assembleDebug PASSED; iOS :composeApp:linkDebugFrameworkIosSimulatorArm64 PASSED. Combined command: ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64 --max-workers=2 --continue — BUILD SUCCESSFUL in 2m 2s. No final-build lock/OOM retry was needed. The previously reported FirebaseCore link failure did not reproduce in this worktree.

Fixtures added, and which were captured (view + themes, and on which emulator):
Nine production-composable fixtures: servers, serversMore, serverPublic, serverAccount, serverOff, serverUnsupported, editAddress, removeCat, signOutAll.
Refreshed Day/E-ink captures for all nine and Night servers/serverAccount completed on explicitly authorized Samsung SM-S921B, serial RFCWC0SSVDM, isolated com.retro99.parrot.fixtures APK. All twenty PNGs and their hierarchy XMLs are committed in design/screens/catalogue-<view>-<theme>.*. Inspected the first batch, fixed outlines/title/card typography together, then confirmed the final batch in a single contact sheet; no further polish loop. Manual Show/Hide interaction passed on serverAccount Night: the dummy key was exposed only after Show and absent from the accessibility hierarchy after Hide.
Own emulator-5556 launch failed because the installed AVD was already in use. No emulator-5554 commands or installs. The Samsung's existing demo app/data were not replaced or cleared.

Differences from the boards: found, fixed, and left (one line each):
Found: baseline Servers title/intro, missing catalogue cards/settings, shared-card outlines and card title typography differed; editAddress's account-retention promise contradicted origin clearing.
Fixed: Libraries title, catalogue cards/status buttons and routing, settings groups, long-address wrapping/masking, normal-theme outlines, sans card names, vertical dialog actions, and conditional sign-out detail.
Left: existing server card visuals intentionally untouched; shared Ember spacing/type and native system bars differ from mock images, Gutenberg uses its name's first initial, generic sign-in-needed copy instead of patron-specific copy, and Add security dialogs retain Add wording when reused by Edit. Address warning and new logout-all entry label need design approval.

Each catalogue status, its text and its button (one line each):
Public: Ready · no account needed — Browse; Checked <time> only for checks younger than seven days.
SignedIn: Signed in as <name> — Browse; same seven-day Checked rule.
SignInNeeded: Sign-in needed / This catalogue now asks for your account. — Add account details.
SignInUnsupported: Sign-in method not supported / This catalogue changed how you sign in. Parrot can't open it until that's supported. — Details; settings and Get books hide Browse.
TurnedOff: Turned off / Browsing and downloads are paused — Turn on; settings hide Browse.
Latest connection error: Couldn't reach it · <time> / The catalogue didn't answer. It may be offline. — Try again; retains time at any age. Authentication/off states win over connection errors; settings keep latest checked time at any age.

Did existing server cards or flows change (yes/no) and how I know:
No existing server-card/detail/action code or existing tests changed: diff against deb13f8d confirms this, and unchanged settings tests pass on Android/iOS. The surrounding title became Libraries, the server-only intro is hidden when catalogues are present, and a new logout-all entry/dialog was added; existing per-server login/logout/edit/remove handlers remain unchanged.

Strings marked TODO-design, with their text:
catalogue_edit_address_origin_warning: Changing the host, port or scheme removes your saved account details. Downloaded books stay.
catalogue_sign_out_everything: Sign out of everything…

Done this run:
Catalogue Libraries rows consume ServerManagementViewModel.catalogueSources; cards/settings/browser/Get books route to the correct destinations without persisting addresses/passwords in navigation. Settings support Show/Hide, validated address/account editors, wrong-password reset/focus, account-removal confirmation, enable/disable, distinct downloaded-book counts, and catalogue-removal confirmation. Account writes use CatalogueAccountEditor after validation, origin changes use Phase 2 registry clearing, and operations register profile-bound cancellation. Conditional sign-out detail uses the shared device-name helper and the existing LogoutUseCase. Added 15 catalogue logic tests, one settings visibility test, and three route tests; nine production-backed fixtures.

Not done or partly done, and why:
No iOS UI screenshots or tablet/landscape manual verification; iOS simulator logic tests did run. Full real-catalogue network/device mutation walkthrough was not performed against the Samsung's demo data. Exact mock-pixel matching is not claimed; intentionally preserved server card visuals and remaining differences are listed above.

Tests skipped, ignored or weakened (file + name + reason), or "none":
None changed, ignored or weakened. translations:iosSimulatorArm64Test SKIPPED and fixtures:testDebugUnitTest NO-SOURCE because those modules have no test source; fixture module has no iOS target, translations has no Android host-test target.

Existing tests that had to change, and why:
None. Only this run's new tests evolved during the red/green implementation (including the gateway validation-result argument and explicit generic assertion typing).

Places the plan or design was ambiguous and what I chose:
No existing logout-all dialog/entry existed in this baseline: added a Libraries footer and wired the supplied dialog to existing LogoutUseCase, not the separate Parrot Cloud sign-out. Address edits start anonymous and never silently retarget saved credentials; account fields appear after a Basic challenge, while account editing starts with the stored username and an empty password. The origin-clearing contract overrides the board's unconditional keep-account promise. Unsupported state always gets settings/Details and only Remove account details in its account group. Error timestamp >= success timestamp is treated as latest error. Runtime persistence does not nest ProfileDatabaseSession around registry cleanup because cleanup itself takes that session lock.

Files changed outside feature/settings, feature/catalogue, feature/home, translations, tools/ember-fixtures and design/screens:
docs/opds-phase4-libraries-run-report.md (this required report).

Known problems I am leaving:
An address registry update can succeed before a subsequent credential/status persistence write fails; no cross-store transaction exists, so a save error can leave the validated address changed (downloaded books remain). Secure account-store changes made outside settings do not independently trigger CatalogueAccessProvider; settings immediately clears its account display on its own Remove action. Existing unrelated books UI failures, books-domain/sync-data iOS test compilation failures, and root sign-out clearAllData() no-op are outside this run; those unrelated test suites were not rerun. The new Libraries sign-out uses the working SettingsRepository.logout path instead of that root path. The earlier FirebaseCore framework-link problem did not reproduce: the final iOS framework build passed.
--- END REPORT ---
```
