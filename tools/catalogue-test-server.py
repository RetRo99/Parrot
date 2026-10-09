#!/usr/bin/env python3
"""A small static OPDS test catalogue, served from this Mac. No accounts, nothing stored.

  python3 tools/catalogue-test-server.py [--port 8791]

From the Android emulator the catalogue is at http://10.0.2.2:<port>/opds/ (10.0.2.2 is the
emulator's name for this Mac). On this Mac itself: http://localhost:<port>/opds/.

What it serves:
  /opds/            first page: one folder ("All books") and one book
  /opds/all         a list of single-edition books that each have an EPUB file
  /files/<n>.epub   a valid, tiny EPUB for each book (made in memory, different for each)
  /not-a-catalogue  an HTML page, for the "this is a web page" answer
"""
import argparse
import io
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

BOOKS = [
    (1, "The Lantern Keeper", "Mira Halloway"),
    (2, "Salt and Signal", "Tomas Brandt"),
    (3, "A Quiet Harbour", "Ines Moreau"),
    (4, "Winter Cartography", "Oleg Varga"),
    (5, "The Long Orchard", "Priya Natarajan"),
]
ATOM = "application/atom+xml;profile=opds-catalog;kind=navigation"
ACQ = "application/atom+xml;profile=opds-catalog;kind=acquisition"


def epub(number: int, title: str, author: str) -> bytes:
    """A minimal valid EPUB 3: mimetype first and stored, container, package, nav, one chapter."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>', compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/content.opf", f'''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">urn:parrot-test-catalogue:{number}</dc:identifier>
<dc:title>{escape(title)}</dc:title><dc:creator>{escape(author)}</dc:creator><dc:language>en</dc:language>
<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
</metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="c1"/></spine>
</package>''', compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/nav.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter1.xhtml">{escape(title)}</a></li></ol></nav></body></html>', compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/chapter1.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{escape(title)}</title></head><body><h1>{escape(title)}</h1><p>By {escape(author)}. This is a test book from the local test catalogue.</p><p>It has one short chapter so that it opens, shows text and can be read to the end.</p></body></html>', compress_type=zipfile.ZIP_DEFLATED)
    return buffer.getvalue()


def feed(base: str, path: str, title: str, entries: str) -> str:
    return f'''<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
<id>urn:parrot-test-catalogue:{path}</id><title>{escape(title)}</title><updated>2026-01-01T00:00:00Z</updated>
<author><name>Parrot test catalogue</name></author>
<link rel="self" href="{base}{path}" type="{ATOM}"/><link rel="start" href="{base}/opds/" type="{ATOM}"/>
{entries}
</feed>'''


def book_entry(base: str, number: int, title: str, author: str, size: int) -> str:
    return f'''<entry><id>urn:parrot-test-catalogue:book:{number}</id><title>{escape(title)}</title><updated>2026-01-01T00:00:00Z</updated>
<author><name>{escape(author)}</name></author><dc:language>en</dc:language>
<summary>A short test book, number {number}.</summary>
<link rel="http://opds-spec.org/acquisition/open-access" href="{base}/files/{number}.epub" type="application/epub+zip" length="{size}"/></entry>'''


class Handler(BaseHTTPRequestHandler):
    def base(self) -> str:
        return f"http://{self.headers.get('Host', 'localhost')}"

    def send(self, body: bytes, content_type: str, status: int = 200):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        base, path = self.base(), self.path.split("?")[0]
        files = {n: epub(n, t, a) for n, t, a in BOOKS}
        if path in ("/opds", "/opds/"):
            first = BOOKS[0]
            entries = f'''<entry><id>urn:parrot-test-catalogue:all</id><title>All books</title><updated>2026-01-01T00:00:00Z</updated><content type="text">Every book in this test catalogue.</content>
<link rel="subsection" href="{base}/opds/all" type="{ACQ}"/></entry>''' + book_entry(base, first[0], first[1], first[2], len(files[first[0]]))
            self.send(feed(base, "/opds/", "Parrot test catalogue", entries).encode(), ATOM)
        elif path == "/opds/all":
            entries = "".join(book_entry(base, n, t, a, len(files[n])) for n, t, a in BOOKS)
            self.send(feed(base, "/opds/all", "All books", entries).encode(), ACQ)
        elif path.startswith("/files/") and path.endswith(".epub") and path[7:-5].isdigit() and int(path[7:-5]) in files:
            self.send(files[int(path[7:-5])], "application/epub+zip")
        elif path == "/not-a-catalogue":
            self.send(b"<!doctype html><html><head><title>Not a catalogue</title></head><body><h1>This is a web page</h1></body></html>", "text/html; charset=utf-8")
        else:
            self.send(b"Not found", "text/plain", 404)

    def log_message(self, fmt, *args):
        print(self.address_string(), fmt % args, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8791)
    args = parser.parse_args()
    print(f"Emulator: http://10.0.2.2:{args.port}/opds/   This Mac: http://localhost:{args.port}/opds/", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
