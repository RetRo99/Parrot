# Ember — Parrot Cloud screens (prompt for Claude desktop)

Reference screenshots in this folder: `cloud-signIn-night.png`, `cloud-signInError-night.png`, `cloud-create-night.png`, `cloud-signIn-day.png`, `cloud-signIn-eink.png`, `cloud-connected-night.png`, `cloud-connected-day.png`, `cloud-connected-eink.png`, `cloud-syncing-night.png`, `cloud-offline-night.png`, `cloud-almostFull-night.png`, `cloud-signOut-night.png`, `cloud-signOutPending-night.png`, `cloud-delete-night.png`.

Numbers, the e-mail address, the plan name and "5 GB" in the screenshots are placeholders — use real data, and leave out anything the backend doesn't have.

---

## Prompt

Redesign the **Parrot Cloud** screens (currently titled "Sync & backup": the signed-out sign-in form and the connected account screen) following `design/ember/PARROT_CLOUD_PROMPT.md` and the `cloud-*.png` screenshots. Keep all existing auth/sync logic; this is layout, copy and state presentation. Ember tokens only (`design/ember/DESIGN_SYSTEM.md`).

**Step 1 — investigate and plan first.** Tell me:
- which composables/ViewModel own these screens and what state they expose (sync status, last sync time, counters, quota, errors);
- what actually syncs today (progress, bookmarks, collections, settings, book files?) and whether "add new books automatically" exists as a setting;
- whether plans/upgrade, "Forgot password", Google sign-in and "Create account" exist in the backend — **don't build UI for anything that doesn't exist**, list it instead;
- what Sign out and Delete account do to local files today.
Then propose the composable split and wait for my OK.

**Step 2 — implement.**

### Naming & copy (both screens)
- The screen title is **"Parrot Cloud"** everywhere (top bar, Settings row, navigation). Remove "Sync & backup" and the word "backup" — Parrot Cloud is a library in the cloud, same wording as Book details ("Add to / In Parrot Cloud").
- Remove the sentence "Book files remain on this device" (it contradicts storing books in the cloud).
- Fix the unformatted "Last successful delivery: %s". Replace it with a relative time: "Last synced 5 minutes ago" / "yesterday, 21:40" / "Not synced yet".
- Remove "0 sent, 0 received, 0 pending". Status is one sentence (below). Keep the raw counters only in Diagnostics.
- Sizes use **KB / MB / GB** with one decimal ("1.2 GB of 5 GB"), never KiB/GiB.

### Signed out (`cloud-signIn-*.png`, `cloud-create-night.png`)
- Top bar: back + "Parrot Cloud" (Fraunces 24sp).
- A quiet benefits box (`surface`, radius 16dp), three check rows: "Your books on every device", "Progress, bookmarks and settings stay in sync", "<quota> of storage included" (only if there is a free quota).
- Fields with the label **above** the field (11sp caps, `ink2`): Email, Password (show/hide eye). 52dp, radius 16dp; focused = 2dp `accent`. "Forgot password?" as a text link on the Password label row.
- **Sign in** — the only filled button (52dp pill); disabled (`disBg`) until both fields are filled; shows "Signing in…" while busy.
- "or" divider, then **Continue with Google** as an outlined 52dp pill (only if supported).
- Bottom line: "New to Parrot Cloud? **Create an account**" switches the same screen to create mode: title stays, button becomes "Create account", password helper "At least 8 characters", link becomes "Already have an account? **Sign in**".
- **Errors go under the field** they belong to (`cloud-signInError-night.png`): field gets the 2dp error border and one sentence in `err` ("That password doesn't match this email."). Network errors show as an error box above the button with "Try again". No toasts, no dialogs.

### Signed in (`cloud-connected-*.png`)
Four cards, 12dp apart, in this order:
1. **Account** — avatar circle with the initial, e-mail (one line, ellipsis), second line "<plan> · <quota>". A tinted **Upgrade** pill on the right — only if upgrading exists; otherwise nothing there.
2. **Sync** — status once, in words:
   - "✓ All synced" (`ok`) + "Last synced 5 minutes ago", outlined **Sync now** button on the right;
   - "Syncing…" + "3 of 12 changes" with a thin progress bar, no button (`cloud-syncing-night.png`; e-ink: static text, no bar animation);
   - "Can't sync right now" (`err`) + last sync time, outlined **Try again**, and an error box with one sentence: "No internet connection. Your changes are saved on this phone and will sync when you're back online." (`cloud-offline-night.png`) — map the other error types to equally plain sentences.
   - Below a divider, one 13sp line: "Syncs: reading progress · bookmarks · collections · reader settings · books you add" — list only what really syncs.
3. **Storage** — "Storage" + "1.2 GB of 5 GB" right-aligned, 6dp bar, "23 books in Parrot Cloud". Above 90%: bar and a line in `err` — "Almost full — remove books from Parrot Cloud or upgrade for more space." (`cloud-almostFull-night.png`). Hide the card if there's no quota.
4. **Settings & sign out** — row "Add new books automatically" with helper "Books you import are uploaded to Parrot Cloud" and a switch (whole row toggles; off state must look clearly off — track `track`, knob `ink2`). Only if this setting exists. Divider, then a plain row **Sign out** with "Books stay on this phone" on the right. Sign out is a neutral row, **not** a filled button.

Below the cards, centered, with 24dp of space above: text button **"Delete Parrot Cloud account…"** in `err`. It opens a confirmation dialog (`cloud-delete-night.png`): "Delete your Parrot Cloud account?" · "This permanently deletes your account and **everything stored in Parrot Cloud**: books, progress, bookmarks and settings. Books downloaded to this phone stay here. This can't be undone." · field "Type DELETE to confirm" · Cancel / **Delete account** (enabled only when typed).

### Sign out confirmation (`cloud-signOut-night.png`, `cloud-signOutPending-night.png`)
Tapping **Sign out** always opens a dialog — never sign out directly.
- Title "Sign out of Parrot Cloud?" · body "Books downloaded to this phone stay here. Books that are only in Parrot Cloud come back when you sign in again." (adjust to what sign-out really does to local files) · buttons **Cancel** / **Sign out** (`accentText`, not red — it isn't destructive).
- **If there are unsynced changes:** add an error box "**3 changes haven't synced yet.** If you sign out now, they stay on this phone and won't reach your other devices." and stack the buttons: **Sync first** (`accentText`; runs a sync, then signs out when it succeeds, stays on the dialog with the error if it fails) / **Sign out anyway** / **Cancel**.
- Back / tap outside = Cancel.

### E-ink
White background, black text, 2dp outlines instead of fills; no scrim behind the dialog (2dp outlined dialog on white); switch = black track when on, outlined when off; progress bars are outlined with a black fill and don't animate; status uses words + ✓, no color.

### Accessibility
Sync card announces status + time as one sentence; storage bar has "1.2 of 5 gigabytes used"; switch row is one toggle target; error text is linked to its field.

**Step 3 — verify.** Build Android (and iOS if set up). Test: sign in with wrong password, offline sign-in, create account, Google sign-in (if present), first sync, sync while offline, quota above 90%, sign out (confirm shown), sign out with pending changes (Sync first / Sign out anyway), delete account, and all three themes. Summarize changed files and anything from this design you left out because the backend doesn't support it.
