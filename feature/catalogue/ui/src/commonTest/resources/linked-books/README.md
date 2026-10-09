Captured 2026-10-09 with individual, user-initiated HTTPS GETs (no crawling):

- first-page.xml: https://www.gutenberg.org/ebooks.opds/
- list.xml: https://www.gutenberg.org/ebooks/search.opds/
- search.xml: https://www.gutenberg.org/ebooks/search.opds/?query=whale

Unchanged response bytes, including inline generic icons. These are catalogue metadata,
not book files. Project Gutenberg terms: https://www.gutenberg.org/policy/terms_of_use.html
The generated common-test registry lets iOS run the same parser and row-rule assertions
without a resource bundle. The Android parity test checks it against the wire files.
Regenerate with python3 tools/ember-fixtures/embed_catalogue_feeds.py.
