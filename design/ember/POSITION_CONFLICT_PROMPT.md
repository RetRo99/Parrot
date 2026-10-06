# Ember — Reading position prompt when opening a book (prompt for Claude desktop)

Reference screenshots in this folder: `pos-posKept-day.png`, `pos-posMoved-day.png`, `pos-posDialog-day.png`, `pos-posDialog-night.png`, `pos-posDialog-eink.png`, `pos-posKept-eink.png`.

Percentages, chapter names and times in the screenshots are placeholders.

---

## Prompt

Redesign the reader's position conflict prompt (`PositionConflictDialog`, reader and books) following `design/ember/POSITION_CONFLICT_PROMPT.md` and the `pos-*.png` screenshots. Keep the conflict detection and the write logic; this changes when we interrupt and how the choice is presented. Android and iOS. Ember tokens only.

**Step 1 — plan first.** Tell me where the dialog is triggered, what data each side has (percent, chapter, device name, timestamp, source name), and whether auto-keeping a position conflicts with any existing guard (linked-resume, `linkedResumeResolved`, the Book details conflict card). Wait for my OK.

**Step 2 — implement.**

### 1. Only interrupt when the answer isn't obvious
Compare the two positions on two things: which is **newer** (last real reading time) and which is **further** (percent).
- **This device is newer and further** → no dialog. Keep this device's position and show the quiet bar (`pos-posKept-day.png`): title "**Kept your place · 86%**", second line "Storyteller was at 78%, 3 hours ago", text action **"Use 78%"**.
- **The other position is newer and further** → no dialog. Move to it and show (`pos-posMoved-day.png`): "**Moved to 86%**", "From Storyteller · this phone was at 78%", action **"Go back to 78%"**.
- **Mixed** (one is newer, the other is further), or a timestamp is missing → show the dialog.
- Differences under 1% (or under one page) are not a conflict: keep this device's position silently, no bar.

### 2. The quiet bar
- Same component and host as the reader's other bars (Bookmarked, Back to where I was): above the progress strip, or above the bottom panel when it's open; never overlapping them.
- 56dp minimum, radius 18dp, `surface`, 1dp `line` border. Title 15sp Bold, second line 13sp `ink2` (one line, ellipsis), action 14sp Bold `accentText` with a 44dp target.
- Shows for 6 s, or until the user turns a page. The action applies the other position immediately and replaces the bar with the mirrored message, so it can be undone once.
- E-ink: 2dp outline, no shadow or animation; stays until the next page turn.

### 3. The dialog (`pos-posDialog-*.png`)
- Title: **"Where do you want to continue?"** (Fraunces 22sp, sentence case). No body paragraph.
- Two option cards stacked vertically, full width. **Each card is the button**: tapping it chooses that position and closes the dialog. No separate buttons.
- Card (min 88dp, radius 16dp, tile fill, 1.5dp `chipBorder`; e-ink 2dp outline), chevron on the right:
  - line 1: the percentage, 20sp Bold; on the newer position, a **Latest** pill next to it;
  - line 2: the chapter, 15sp SemiBold, one line with ellipsis ("Chapter 8, The Crossing");
  - line 3: where and when, 13sp `ink2` ("This phone · 12 minutes ago", "Storyteller · 3 hours ago").
- The newer position is listed first and has a 2dp `accent` border. Don't label "furthest".
- Under the cards, 13sp `ink2`: "The other position is replaced."
- Back and outside tap stay blocked, as today.
- Show progress on the tapped card while the choice is applied; on failure keep the dialog open with one `err` line under the cards and let the user try again.

### 4. Names
- This device: "This phone" / "This iPhone" / "This tablet", or the device's name if the user set one.
- The other side: the source's display name once — "Storyteller", "Audiobookshelf", "Parrot Cloud", or the server's custom name. Never "Storyteller (Storyteller)", never the word "Server".
- If the other position came from another of the user's devices and we know its name, use that ("Pixel Tablet · 3 hours ago").
- Titles in sentence case; no "Reading Position Conflict".

### 5. Consistency
The Book details screen already uses the same idea (prominent card when the other device is ahead, quiet line when it's behind). Use the same wording for names, percentages and times in both places, and make sure answering in one place doesn't trigger the prompt again in the other.

### E-ink
No scrim or animation; the dialog and cards have 2dp outlines; the "Latest" pill is black with white text and is the only thing marking the newer card (don't rely on border colour).

### Accessibility
The dialog announces its title, then each card as one button: "78 percent, chapter 8, The Crossing, this phone, 12 minutes ago, latest". The bar is a polite live region and its action is reachable by TalkBack/VoiceOver before it times out (don't auto-dismiss while it has accessibility focus).

**Step 3 — verify.** Build Android and iOS. Test all four combinations of newer/further, a missing timestamp, a difference under 1%, a long chapter title, a server with a custom name, another named device, applying from the bar and undoing, a failed write, the bar with the bottom panel open, answering on Book details and then opening the book, and all three themes. Summarize changed files and any guard you had to adjust.
