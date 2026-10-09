# Book catalogues (OPDS): release checklist

Written 2026-10-09 at the end of the Phase 5 hardening run, branch `opds/phase4-screens`.
Everything here needs a person. Nothing below has been done unless it says so. Work through it
in order: later steps assume earlier ones passed.

What is already done by tests is in `opds-test-coverage.md`. What has been tried on devices is
in `opds-compatibility.md`. Security findings are in `opds-security-review.md`.

## 0. Before you start

- [ ] Read "Read this first" in `opds-compatibility.md`. Five things changed after the last
      device run and have not been seen on a device.
- [ ] Decide the five product questions in section 5. Three of them change what you test.
- [ ] Have ready: an Android phone, an iPhone, a Mac on the same Wi-Fi, a catalogue with a
      password (section 3), and a profile with a few books already in it.

The local test catalogue used below:

```bash
python3 tools/catalogue-test-server.py --port 8791
```

It serves `http://<this Mac>:8791/opds/`: one folder, five small books, no account. From the
Android emulator it is `http://10.0.2.2:8791/opds/`. For a phone on USB, forward it with
`adb reverse tcp:8791 tcp:8791` and use `http://localhost:8791/opds/`.

## 1. Android phone

Install a fresh build. Use a profile that already has books, reading positions and a
highlight, so you can see that nothing of it changes.

**A. Project Gutenberg**

- [ ] Get books → "Start with one of these" → Project Gutenberg → add. No account is asked for.
- [ ] The first page lists folders. Open one. Rows show a title and an author.
- [ ] **New since the last device run:** rows that offer a thumbnail show it. Check that
      covers appear and are the right pictures. On a book page the larger cover is used.
- [ ] Search for a title. Results are book rows. Clear the search: the page is back as it was.
- [ ] Scroll a long list to its end several times. It loads more by itself and stops at the end.
      It must never keep loading without showing new books.
- [ ] Open a book. Choose a file if there are several. Download.
- [ ] Downloads shows it running, then done. "Open" opens the book in the reader.
- [ ] Back in the list, the row says "In your library".
- [ ] The book is in the library with the right title, author and cover.

**B. The local test catalogue (plain http)**

- [ ] Add by address. The http warning shows. "Add anyway" adds it.
- [ ] Download two books with the row button. Both finish.
- [ ] Start a download, then turn on aeroplane mode. It fails with "Try again". Turn the
      network on and try again: it finishes, and the library has the book once.
- [ ] Start a download and force-stop Parrot. Open it again: Downloads shows the download as
      interrupted, with "Start again". Nothing started by itself.
- [ ] Turn the catalogue off in its settings. It cannot be browsed; its books stay.
- [ ] Turn it on, then remove it. The library keeps every book, and each still opens.

**C. The rest of the app is unchanged**

- [ ] The library, series and link suggestions look as before, with catalogues added.
- [ ] Reading positions of existing books still sync with their servers.
- [ ] Read a downloaded catalogue book offline. Its position, a bookmark, a highlight and its
      reading time are kept after a restart.
- [ ] A downloaded catalogue book is not offered to Parrot Cloud backup by "Back up all".

**D. Looks and access**

- [ ] Day, Night and E-ink themes on: Get books, a list, a book page, Downloads, a catalogue's
      settings.
- [ ] Text size at 200% on the same screens: nothing is cut off.
- [ ] TalkBack through adding a catalogue, a list row, the file sheet and Downloads. This has
      never been run; the labels were checked by reading the code and by a script.

**E. Backup**

- [ ] Add a catalogue with an account (section 3), back the phone up, restore to another
      phone or after a reset. The catalogue must come back **without** its account details.
      See section 5, question 5, for what else is in the backup.

## 2. iPhone

Nothing has ever been run on an iPhone. Do all of section 1 A, C and D on an iPhone, with
VoiceOver in place of TalkBack, then:

**The http check**

- [ ] Get books → Add by address → type the local test catalogue's `http://` address. The
      expected answer today is the dialog "This catalogue can't be added" (Parrot can only
      use https addresses on an iPhone), with no way to continue.
- [ ] Add a Storyteller or Audiobookshelf server by an `http://` address on the same Wi-Fi.
      Write down whether it connects. This tells you whether iOS blocks plain http for Parrot
      at all (plan §10.4, still open).

Where the setting is, if you decide to allow http catalogues on iPhone:

- `feature/catalogue/ui/src/iosMain/kotlin/com/retro99/catalogue/ui/add/CatalogueHttpPolicy.ios.kt`,
  the one line `private const val ALLOW_HTTP_CATALOGUES = false`. Set it to `true`.
- iOS itself must also allow it. `iosApp/iosApp/Info.plist` has no App Transport Security
  entry today. A catalogue on the local network needs `NSAllowsLocalNetworking` under
  `NSAppTransportSecurity`. Without it the request fails whatever the line above says.
- Then repeat section 1 B on the iPhone.

**iPhone only**

- [ ] Start a download and put Parrot in the background for a minute. See what state the
      download is in when you return. There is no background download.
- [ ] After a download, check in Settings → General → iPhone Storage that Parrot's size grew
      by about the book's size and not by twice that.
- [ ] Back up the iPhone (encrypted) with a catalogue account saved, and restore to another
      iPhone. Write down whether the account details came along. They are expected to; see
      section 5, question 4.

## 3. A catalogue with a password

No catalogue with a password has been tried on any device. The only cover is one automated
test with a pretend network. You need a catalogue over **https** that answers with Basic
sign-in, for example Calibre's content server with accounts turned on, behind a reverse proxy
with a real certificate. A password over http is refused by design.

On Android and on iPhone:

- [ ] Add by address without an account. Parrot says the catalogue needs one and turns the
      account fields on. Nothing is added yet.
- [ ] Type a wrong password. "Username or password is incorrect." The password field is
      emptied. Nothing is added.
- [ ] Type the right one. The catalogue is added and opens. Its row says it is signed in.
- [ ] Browse, search, open a book. Covers show.
- [ ] Download a book and open it.
- [ ] In the catalogue's settings, change the address to another path on the same server.
      The account stays. Change it to another host: you are warned, and the account is gone.
- [ ] Remove the account details in settings. Open the catalogue: it asks you to sign in.
      Downloaded books stay.
- [ ] Sign in again from that sheet, first with a wrong password, then the right one. The page
      opens. **New:** go back to Get books at once; the row must say signed in, not
      "Sign in needed".
- [ ] Sign out of everything. The catalogue stays, its account is gone, its books stay.
- [ ] If you can, watch the server's log while doing this: every request for a page or a cover
      on the catalogue's host carries the account; no request to any other host does.

Also try, if you have them: a catalogue with a self-signed certificate (expected: a
"Can't check this catalogue", with no way to continue), and a catalogue that asks for Digest
sign-in (expected: "Can't add this catalogue yet" when its first page needs the account, or an
offer to add it without an account when its first page is public).

## 4. Other catalogues

Each of these is "not tried". Try the ones you intend to say Parrot works with, and fill in
`opds-compatibility.md`.

- [ ] Standard Ebooks, with a patron account. If nobody can test it, take the preset out
      (section 5, question 1).
- [ ] Calibre content server
- [ ] Calibre-Web
- [ ] Kavita
- [ ] Komga
- [ ] One OPDS 2 (JSON) catalogue

For each: add, browse three levels deep, search, check covers, download one EPUB, open it.
Note anything listed as "can't be downloaded here" that you expected to download.

## 5. Open product decisions

1. **Ship the Standard Ebooks preset untested?** It has never been tried and has no terms
   link (`termsUrl` is null in `catalogue-presets.json`; plan §11.7 asks for a verified one
   per preset). Options: test it with an account, or remove the preset for this release.
2. **http catalogues on iPhone.** Blocked today. Decide after the check in section 2.
3. **"Download sample".** Not built. Recommended in the plan: leave it out of the first
   release (§11.7, item 4).
4. **iPhone keychain.** Account details, like the library servers' tokens, can travel to a
   new iPhone inside an encrypted backup. Decide whether that is acceptable or whether all
   secrets should become "this device only", which signs everyone out once.
5. **What Android backs up.** Account details are excluded. The profile database is not, and
   it holds saved catalogue pages (possibly fetched with an account), download rows and where
   each book came from. Decide whether that is acceptable for this release.
6. **Backing up a catalogue book's file to Parrot Cloud.** Never automatic, and nothing in the
   app offers it. Whether the user may do it by hand, and with what wording about rights, is
   not designed (§11.7, item 5).
7. **OPDS 1 filters.** OPDS 1 catalogues' sort and filter links are not shown. Decide whether
   that is acceptable for the first release.
8. **Check Project Gutenberg's terms and addresses again on the release date.** The research
   is dated 2026-10-08. Confirm the catalogue address, the https search address and the terms
   link in `catalogue-presets.json` still answer, and that Gutenberg's guidance for apps has
   not changed (plan §2.3).

## 6. Four strings waiting for design copy

Each is marked `TODO-design` in
`translations/src/commonMain/composeResources/values/strings.xml`. The text is the developer's.

| String | Text now | What design did not say |
|---|---|---|
| `catalogue_add_error_duplicate` | "This catalogue is already in Get books." | What to say when the address being added is already saved |
| `catalogue_edit_address_origin_warning` | "Changing the host, port or scheme removes your saved account details. Downloaded books stay." | The board says account details are kept on every address change; they are removed when the server changes |
| `catalogue_sign_out_everything` | "Sign out of everything…" | The dialog is designed; the label of the entry that opens it is not |
| `catalogue_load_earlier` and `catalogue_list_failed_earlier_rest` | "Load earlier books", "The ones below are still here." | The rows a very long list shows after its first pages were dropped (20-page limit) |

Earlier reports list more wording questions (plan §7, Phase 4 notes, items 8 to 11): the
singular of the remove dialog, the access pill of a preset that needs no account, how
"<time> ago" is written, and the patron-account sentence.

## 7. Known problems that do not come from this work

They were found while building catalogues and are not fixed on this branch. Each should have
an owner before release.

| Problem | Where it is recorded | Effect |
|---|---|---|
| Creating a profile can crash the app | Reproduced on `main` (078ab8ef); `docs/manual-qa-evidence/2026-10-09/opds-profile-crash-on-main.txt` | Blocks testing with a new profile on some devices |
| Signing out clears no table | `SignOutOfEverythingTest › the app's cleaner is handed no tables…`; plan §7, Phase 3 notes | "Clear all data" on sign-out does nothing: the cleaner is given an empty list |
| Four failing tests in `feature/books/ui` | `LinkPickerViewModelTest` (2), `LinkReviewViewModelTest` (2) | Fail on this branch and on `main` |
| The composeApp iOS tests do not link | `FirebaseCore` is missing at link time | No composeApp test runs for iOS, so the app's real-graph tests, the signed-in end-to-end test among them, run for Android only |
| iOS test sources of `feature/books/domain` and `feature/sync/data` do not compile | — | Those modules have no iOS test run |

## 8. Last

- [ ] Run the full verification from the plan's status section on the release commit.
- [ ] Update `opds-compatibility.md` with what you tried.
- [ ] Update the README's "Book catalogues (OPDS)" section if a decision in section 5 changed
      what is supported.
