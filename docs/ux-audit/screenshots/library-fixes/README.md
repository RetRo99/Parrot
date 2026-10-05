# Library list: progress consistency and book rows

Emulator captures of the library list, with fixture books (a long title, two unstarted
books and one in progress at 50%). No real reading data was used.

| | |
| --- | --- |
| Day | [Screenshot](day.png) |
| Night | [Screenshot](night.png) |
| E-ink | [Screenshot](eink.png) |
| Last row above the dock | [Screenshot](dock-clearance.png) |
| Before these fixes | [Screenshot](before.png) |

## Before

The same book read three different progress values from three sources: the
continue-reading card froze the reader's value at the last checkpoint (0% here), the
list row used the remote progress cached when the list loaded, and book details fetched
the remote position live (50%). Rows also truncated the title to one line and the
author to "Seraphina Winterbourne-Quill…", and the card carried a ⋮ menu.

## After

One source: every surface reads the same `BookProgressInfoUiModel` (local position,
remote position from the shared `RemotePositionStore`) and the same
`progressPercentOf` rounding. The card and the row agree (50%). Titles wrap to two
lines, the author gets a full line, and the quiet 13sp line reads
"eBook · On this phone · Storyteller" (the source only when the user has more than one
source connected). The ⋮ menu is gone; its single item ("Clear") moved to a long press
on the card with a confirmation.

The Parrot Cloud note ("Add 12 books on this phone to Parrot Cloud") is not in these
captures: the emulator has no Parrot Cloud account, and the note only shows when signed
in with local-only books left.
