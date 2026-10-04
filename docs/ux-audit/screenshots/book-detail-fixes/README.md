# Book details: Saved and other-device position

Emulator captures of the production Compose book-detail content, using temporary
fixture data (no real reading positions or saved items were modified).

- Local position: 88%, chapter 18 of 20.
- Other device: unknown name (“Another device”), observed five minutes ago.
- Ahead: 94%. Behind: 81%.
- Saved: 3 bookmarks, 4 highlights, 2 notes.
- The fixture has no cover image.

| | Ahead | Behind |
| --- | --- | --- |
| Day | [Screenshot](day-ahead.png) | [Screenshot](day-behind.png) |
| E-ink | [Screenshot](eink-ahead.png) | [Screenshot](eink-behind.png) |

The quiet behind hint is dismissed by long-press, or hidden when local progress
advances. Dismissal only changes presentation; it does not resolve the conflict.
Device names are optional position metadata, with “Another device” as fallback.

The behind presentation uses 14sp copy with the name and percentage emphasized,
a 36dp outlined pill inside a 48dp touch target, and a 2dp × 12dp progress tick.
