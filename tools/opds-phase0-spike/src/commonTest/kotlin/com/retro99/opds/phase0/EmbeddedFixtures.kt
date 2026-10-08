// GENERATED fixture registry: embedded copies of the files under
// src/commonTest/resources/opds for targets that cannot read common test
// resources (K/N iOS simulator tests run without a bundle; see the
// fixture-loading section of the module README). Do not edit the embedded
// strings; update the fixture file and regenerate this file with the
// equivalent mapping.
//
// Each embedded value is a raw string whose lines all carry margin "'";
// decode it with String.trimMargin("'"). Generation rules: fixtures must
// stay '$'-free, '"""'-free, and free of lines that start with "'"; the
// parity test in commonTest proves embedded == resource file content.
package com.retro99.opds.phase0

/**
 * Embedded copies of the shared test fixtures. Keys are the fixture paths
 * as given to [readFixture]; values decode via trimMargin("'").
 */
internal object EmbeddedFixtures {
    val sources: Map<String, String> = mapOf(
        "opds/comment-before-declaration.xml" to """'<!-- Security spike: an XML comment BEFORE the declaration — the comment is
'     the first thing in the file, the declaration the second, so this file is
'     malformed for one reason only (document-order violation) and contains no
'     DOCTYPE/_entities. Kept as a dedicated fixture so this recorded failure
'     ("Unexpected START_DOCUMENT in state START_DOC") can never be confused
'     with the DOCTYPE behavior tested by dtd-baseline.xml: a file with the
'     leading comment and NO DTD fails identically.
'
'     Recorded behavior (Phase 0, both targets): a clean XmlException at line 6
'     (the first thing after the displaced declaration). No content delivered. -->
'<?xml version="1.0" encoding="utf-8"?>
'<entry>
'  <id>urn:synthesis:misplaced-comment:1</id>
'  <title>Comment before declaration</title>
'  <summary>This document is misordered no matter what features the parser has.</summary>
'</entry>
'""",
        "opds/dtd-baseline.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!-- Security spike: what does this parser actually do with a small
'     internal DTD entity tree? Recorded behavior (Phase 0): a DOCDECL event is
'     delivered and internal entities ARE EXPANDED (expandEntities=true) or
'     delivered as resolved ENTITY_REF events (expandEntities=false) — no
'     built-in DTD rejection. Phase 1 policy: stop at the DOCDECL event.
'
'     Value tree (bounded, 4 levels): a -> "A", b -> "&a;&a;", c -> "&b;&b;",
'     d -> "&c;&c;" ; so "&d;" is AAAAAAAA (8 A's) when entities are resolved. -->
'<!DOCTYPE entry [
'  <!ENTITY a "A">
'  <!ENTITY b "&a;&a;">
'  <!ENTITY c "&b;&b;">
'  <!ENTITY d "&c;&c;">
']>
'<entry>
'  <id>urn:synthesis:dtd-baseline:1</id>
'  <title>DTD baseline</title>
'  <summary>value: &d;</summary>
'</entry>
'""",
        "opds/dtd-deep.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!-- Security spike: deep nested internal entities, to record whether xmlutil
'     has any built-in expansion limit (Phase 0 finding: none — see
'     docs/opds-phase0-spikes.md).
'
'     Tree: e0 = "A", e_n = "&e{n-1};&e{n-1};" for n in 1..15, so "&e15;" is
'     32,768 A's (~32 KB expansion) — deliberately under the plan's 5 MiB
'     budget and harmless to execute. Phase 1 must bound this itself; recorded
'     as REQUIRED work (plan §4 "Parsing", §7 Phase 1). -->
'<!DOCTYPE entry [
'  <!ENTITY e0 "A">
'  <!ENTITY e1 "&e0;&e0;">
'  <!ENTITY e2 "&e1;&e1;">
'  <!ENTITY e3 "&e2;&e2;">
'  <!ENTITY e4 "&e3;&e3;">
'  <!ENTITY e5 "&e4;&e4;">
'  <!ENTITY e6 "&e5;&e5;">
'  <!ENTITY e7 "&e6;&e6;">
'  <!ENTITY e8 "&e7;&e7;">
'  <!ENTITY e9 "&e8;&e8;">
'  <!ENTITY e10 "&e9;&e9;">
'  <!ENTITY e11 "&e10;&e10;">
'  <!ENTITY e12 "&e11;&e11;">
'  <!ENTITY e13 "&e12;&e12;">
'  <!ENTITY e14 "&e13;&e13;">
'  <!ENTITY e15 "&e14;&e14;">
']>
'<entry>
'  <id>urn:synthesis:dtd-deep:1</id>
'  <title>DTD deep nesting</title>
'  <summary>value: &e15;</summary>
'</entry>
'""",
        "opds/dtd-external.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!-- Security spike: external entity resolution attempt, with the XML
'     declaration first (the previous version of this fixture had the comment
'     before the declaration, which failedParsing for an unrelated reason and
'     produced a misleading pin — see docs/opds-phase0-spikes.md).
'
'     Recorded behavior (Phase 0): this throws XmlException
'     ("Unexpected content in document type declaration") at the DOCTYPE —
'     the parser does not implement external entities. No file access occurs.
'     Phase 1 policy: stop at the DOCDECL event before any of this matters. -->
'<!DOCTYPE entry [
'  <!ENTITY ex SYSTEM "file:///etc/hosts">
']>
'<entry>
'  <id>urn:synthesis:dtd-external:1</id>
'  <title>DTD external</title>
'  <summary>You should not read this: &ex;</summary>
'</entry>
'""",
        "opds/error.html" to """'<!doctype html>
'<!-- A non-OPDS body: servers frequently return this on 404/500 or when the feed
'     URL actually points at a web page. Phase 1 document detection must reject it. -->
'<html lang="en">
'  <head><title>Example Catalogue</title></head>
'  <body>
'    <h1>Oops</h1>
'    <p>That page could not be found, but have a look at our hand-curated shelf instead.</p>
'  </body>
'</html>
'""",
        "opds/opds1/calibre-newest.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!--
'  Synthetic acquisition feed shaped like a Calibre content-server OPDS feed:
'  publication entries carry their own acquisition links directly, and entry
'  titles do NOT share one work (here: identical titles for different authors, a
'  common Calibre-deduplicate edge cited in §11.7 design decision "editionsList").
'  Used for the grouping-rule spike (§11.7 Still open 1). All content invented.
'-->
'<feed xmlns="http://www.w3.org/2005/Atom"
'      xmlns:opds="http://opds-spec.org"
'      xmlns:dc="http://purl.org/dc/elements/1.1/">
'  <id>calibre:library:newest:synthetic</id>
'  <title>My Calibre Library</title>
'  <updated>2026-10-08T00:00:00Z</updated>
'
'  <link rel="self" href="/opds/newest" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
'
'  <entry>
'    <id>calibre:book:1</id>
'    <title>Winter Letters</title>
'    <updated>2026-10-01T00:00:00Z</updated>
'    <author><name>Mara Write</name></author>
'    <summary>Book A in a two-author same-title list.</summary>
'    <content type="html"><![CDATA[<p>First book.</p>]]></content>
'    <link rel="http://opds-spec.org/acquisition" href="/opds/get/1.epub" type="application/epub+zip"/>
'  </entry>
'
'  <entry>
'    <id>calibre:book:2</id>
'    <title>Winter Letters</title>
'    <updated>2026-10-02T00:00:00Z</updated>
'    <author><name>Jon Marnet</name></author>
'    <summary>Book B: same title, different author, different work.</summary>
'    <content type="html"><![CDATA[<p>Second book.</p>]]></content>
'    <link rel="http://opds-spec.org/acquisition" href="/opds/get/2.epub" type="application/epub+zip"/>
'  </entry>
'</feed>
'""",
        "opds/opds1/listing.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!--
'  Synthetic OPDS 1.x navigation feed.
'
'  Modeled on the shapes observed at https://www.gutenberg.org/ebooks/search.opds/
'  (research notes §2.3 of docs/opds-server-implementation-plan.md): a default-Atom
'  feed, relative `subsection` links, an OpenSearch link, `start`/`next`
'  pagination links, and an inline data-URI thumbnail. All content here is
'  invented; no third-party content is copied.
'-->
'<feed xmlns="http://www.w3.org/2005/Atom"
'      xmlns:opds="http://opds-spec.org"
'      xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
'  <id>urn:uuid:11111111-2222-3333-4444-555555555555</id>
'  <title>Example Catalogue — Listing</title>
'  <updated>2026-10-08T00:00:00Z</updated>
'  <author>
'    <name>Example Catalogue</name>
'    <uri>http://catalogue.example.org/</uri>
'  </author>
'
'  <link rel="self" href="/listing" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
'  <link rel="start" href="/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
'  <link rel="next" href="/listing?offset=25" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
'  <link rel="search" href="/osd.xml" type="application/opensearchdescription+xml"/>
'
'  <entry>
'    <id>urn:uuid:22222222-3333-4444-5555-666666666666</id>
'    <title>A Synthesized Book of Verses</title>
'    <updated>2026-10-05T00:00:00Z</updated>
'    <summary>Navigation to the publication feed for this work.</summary>
'    <link rel="subsection" href="/works/verses" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
'    <link rel="http://opds-spec.org/image" href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AL+2z4DAAAAAElFTkSuQmCC" type="image/png"/>
'  </entry>
'
'  <entry xml:base="/works/">
'    <id>urn:uuid:33333333-4444-5555-6666-777777777777</id>
'    <title>A Synthesized Treatise</title>
'    <updated>2026-10-06T00:00:00Z</updated>
'    <summary>Link below inherits this entry's xml:base.</summary>
'    <link rel="subsection" href="treatise" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
'  </entry>
'
'  <entry>
'    <id>urn:uuid:44444444-5555-6666-7777-888888888888</id>
'    <title>The Collected Synthesis (series)</title>
'    <updated>2026-10-07T00:00:00Z</updated>
'    <summary>Points at a paginated acquisition feed: NOT one book with editions.</summary>
'    <link rel="subsection" href="/series/synthesis" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
'  </entry>
'
'  <entry>
'    <id>urn:uuid:55555555-6666-7777-8888-999999999999</id>
'    <title>Plain folders</title>
'    <updated>2026-10-07T00:00:00Z</updated>
'    <summary>Second-level navigation feed, not a publication.</summary>
'    <link rel="subsection" href="/folders/top" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
'  </entry>
'
'  <opensearch:itemsPerPage>25</opensearch:itemsPerPage>
'  <opensearch:startIndex>0</opensearch:startIndex>
'</feed>
'""",
        "opds/opds1/osd.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!--
'  Synthetic OpenSearch 1.1 description in the OPDS1 search shape: two Url rules,
'  the first an OPDS acquisition feed with `{searchTerms}` plus the optional
'  `{startPage?}` parameter, the second an HTML fallback. Modeled on the OpenSearch
'  1.1 parameter rules cited in the plan (§2.4 sources; RFC fixtures reused with
'  the OpenSearch/others' own notices in the module README).
'-->
'<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
'  <ShortName>Example Catalogue</ShortName>
'  <Description>Search the synthetic example catalogue.</Description>
'  <InputEncoding>UTF-8</InputEncoding>
'  <Url type="application/atom+xml;profile=opds-catalog;kind=acquisition"
'       template="https://catalogue.example.org/opds/search?q={searchTerms}&amp;p={startPage?}"/>
'  <Url type="text/html"
'       template="https://catalogue.example.org/search/{searchTerms}"/>
'</OpenSearchDescription>
'""",
        "opds/opds1/treatise-entry.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!--
'  Synthetic OPDS 1.x standalone full entry with a CDATA HTML description and an
'  inherited xml:base on the entry element. All content invented.
'-->
'<entry xmlns="http://www.w3.org/2005/Atom"
'       xmlns:opds="http://opds-spec.org"
'       xmlns:dc="http://purl.org/dc/elements/1.1/"
'       xml:base="/cache/">
'  <id>urn:synthesis:treatise:1</id>
'  <title>A Synthesized Treatise</title>
'  <updated>2026-10-06T00:00:00Z</updated>
'  <author>
'    <name>Bern Synth</name>
'  </author>
'  <dc:language>en</dc:language>
'  <summary>Standalone full entry.</summary>
'  <content type="html"><![CDATA[<p>An <b>HTML</b> blob describing the treatise &amp; more.</p>]]></content>
'
'  <link rel="http://opds-spec.org/acquisition" href="treatise.epub" type="application/epub+zip"/>
'  <link rel="http://opds-spec.org/image" href="treatise.png" type="image/png"/>
'</entry>
'""",
        "opds/opds1/verses-acquisition.xml" to """'<?xml version="1.0" encoding="utf-8"?>
'<!--
'  Synthetic OPDS 1.x acquisition feed for one work with two editions ("variants"),
'  modeled on the observed shape of https://www.gutenberg.org/ebooks/1342.opds
'  (research notes §2.3): two entries with the same work title, distinct atom ids,
'  multiple acquisition links (one entry exposes more than one EPUB link), XHTML
'  description, rights, language, related links. All content is invented.
'-->
'<feed xmlns="http://www.w3.org/2005/Atom"
'      xmlns:opds="http://opds-spec.org"
'      xmlns:dc="http://purl.org/dc/elements/1.1/"
'      xmlns:thread="http://purl.org/syndication/thread/1.0">
'  <id>urn:uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</id>
'  <title>A Synthesized Book of Verses</title>
'  <updated>2026-10-08T00:00:00Z</updated>
'
'  <link rel="self" href="/works/verses" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
'  <link rel="up" href="/listing" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
'
'  <entry>
'    <id>urn:synthesis:verses:edition:1</id>
'    <title>A Synthesized Book of Verses</title>
'    <updated>2026-10-05T00:00:00Z</updated>
'    <author>
'      <name>Adele Synthling</name>
'    </author>
'    <dc:language>en</dc:language>
'    <dc:rights>Released under a catalogue-of-record setting. See the related link.</dc:rights>
'    <summary>First edition.</summary>
'    <content type="xhtml">
'      <div xmlns="http://www.w3.org/1999/xhtml">
'        <p>Description with <em>inline</em> markup and an opaque &amp; opaque entity.</p>
'      </div>
'    </content>
'
'    <link rel="self" href="/works/verses/edition-1" type="application/atom+xml;profile=opds-catalog;kind=acquisition;thread=update"/>
'    <link rel="http://opds-spec.org/acquisition" href="/cache/verses-1.epub.noimages" type="application/epub+zip"/>
'    <link rel="http://opds-spec.org/acquisition" href="/cache/verses-1.epub.images" type="application/epub+zip"/>
'    <link rel="http://opds-spec.org/acquisition" href="/cache/verses-1.txt" type="text/plain"/>
'    <link rel="related" href="https://catalogue.example.org/work/verses/edition-1" type="text/html"/>
'    <link rel="http://opds-spec.org/image" href="/covers/verses-1.png" type="image/png"/>
'  </entry>
'
'  <entry>
'    <id>urn:synthesis:verses:edition:2</id>
'    <title>A Synthesized Book of Verses</title>
'    <updated>2026-09-30T00:00:00Z</updated>
'    <author>
'      <name>Adele Synthling</name>
'    </author>
'    <dc:language>en</dc:language>
'    <dc:rights>Synthetic rights statement.</dc:rights>
'    <summary>Second edition, published later, with fewer formats.</summary>
'    <content type="text">Plain-text description for the second edition.</content>
'
'    <link rel="http://opds-spec.org/acquisition" href="/cache/verses-2.epub" type="application/epub+zip"/>
'    <link rel="http://opds-spec.org/acquisition" href="/cache/verses-2.mobi" type="application/x-mobipocket-ebook"/>
'    <link rel="http://opds-spec.org/image" href="/covers/verses-2.png" type="image/png"/>
'  </entry>
'</feed>
'""",
        "opds/opds2/catalog.json" to """'{
'  "_comment": "Synthetic OPDS 2.0 feed (§3.2 research notes): navigation, publications, groups, facets, pagination links, and a URI-template search link as used by OPDS2 catalogues. All content invented.",
'  "metadata": {
'    "title": "Example Catalogue — Root",
'    "identifier": "urn:uuid:77777777-8888-9999-aaaa-bbbbbbbbbbbb",
'    "modified": "2026-10-08T00:00:00Z",
'    "attribution": { "name": "Example Catalogue", "href": "http://catalogue.example.org/" },
'    "language": "en"
'  },
'  "links": [
'    { "rel": "self", "type": "application/opds+json", "href": "/opds/2/root.json" },
'    { "rel": "next", "type": "application/opds+json", "href": "/opds/2/root.json?pag=2" },
'    { "rel": "search", "type": "application/opds-publication+json", "href": "{?query,title}" }
'  ],
'  "navigation": [
'    { "href": "/opds/2/new.json", "title": "New publications", "rel": "http://opds-spec.org/sort/new", "type": "application/opds+json" },
'    { "href": "/opds/2/genres.json", "title": "Genres" },
'    { "href": "/opds/2/by-author.json", "title": "Authors" }
'  ],
'  "publications": [
'    {
'      "metadata": {
'        "identifier": "urn:uuid:88888888-9999-aaaa-bbbb-cccccccccccc",
'        "title": "River Narrative",
'        "language": ["en", "de"],
'        "author": [
'          "Casey Script",
'          { "name": "Emery Quill", "role": "author" },
'          { "name": "Gordon Manifold", "role": "editor" }
'        ],
'        "publisher": { "name": "Synth Press" },
'        "published": "2026-03-01",
'        "description": "<p>One book, <em>three</em> contributors in {mixed} forms.</p>"
'      },
'      "images": [
'        { "href": "/covers/river-low.png", "width": 300, "height": 450 },
'        { "href": "/covers/river-high.png", "width": 600, "height": 900 }
'      ],
'      "links": [
'        { "rel": "self", "href": "/opds/2/publications/river.json", "type": "application/opds-publication+json" },
'        { "rel": "http://opds-spec.org/acquisition", "href": "/files/river.epub", "type": "application/epub+zip" }
'      ]
'    }
'  ],
'  "groups": [
'    {
'      "metadata": { "title": "Visitors" },
'      "links": [
'        { "href": "/opds/2/visitors.json", "title": "All visitor publications", "type": "application/opds+json", "rel": "self" }
'      ],
'      "publications": []
'    },
'    {
'      "metadata": { "title": "Residents" },
'      "links": [
'        { "href": "/opds/2/residents.json", "title": "All resident publications", "type": "application/opds+json", "rel": "self" }
'      ],
'      "publications": [
'        {
'          "metadata": {
'            "identifier": "urn:uuid:99999999-aaaa-bbbb-cccc-dddddddddddd",
'            "title": "Second Narrative",
'            "language": "en",
'            "author": "Kim Synth"
'          },
'          "links": [
'            { "rel": "http://opds-spec.org/acquisition", "href": "/files/second.epub", "type": "application/epub+zip" }
'          ]
'        }
'      ]
'    }
'  ],
'  "facets": [
'    {
'      "metadata": { "name": "Genre" },
'      "links": [
'        { "href": "/opds/2/root.json?genre=fiction", "title": "Fiction", "active": true },
'        { "href": "/opds/2/root.json?genre=essays", "title": "Essays" }
'      ]
'    }
'  ]
'}
'""",
        "opds/opds2/landscape.json" to """'{
'  "_comment": "Synthetic OPDS 2.0 publication document (per the OPDS 2.0 living standard, plan §2.2): self identity, localized title field, contributor string/object forms, responsive images, direct EPUB link, indirect acquisition tree via properties.indirectAcquisition, priced buy link, and an alternate OPDS1 link. All content invented.",
'  "metadata": {
'    "identifier": "urn:uuid:eeeeeeee-ffff-0000-1111-222222222222",
'    "title": { "und": "Localized Landscape", "de": "Lokalisierte Landschaft" },
'    "language": ["en"],
'    "author": [
'      "Gray Script",
'      { "name": "Emery Quill", "role": "author" }
'    ],
'    "translator": {
'      "name": "Hesta Vane",
'      "href": "http://catalogue.example.org/people/vane"
'    },
'    "publisher": "Synth Press",
'    "published": "2026-02-20T00:00:00Z",
'    "description": "<p>Indirect acquisition: an HTML flow ends in the EPUB.</p>",
'    "rights": "Synthetic rights text."
'  },
'  "images": [
'    { "href": "/covers/landscape.png?w=300", "width": 300, "height": 450 },
'    { "href": "/covers/landscape.png?w=900", "width": 900, "height": 1350 }
'  ],
'  "links": [
'    { "rel": "self", "href": "/opds/2/publications/landscape.json", "type": "application/opds-publication+json" },
'    {
'      "rel": "http://opds-spec.org/acquisition",
'      "href": "/flows/landscape-licence",
'      "type": "text/html",
'      "properties": {
'        "indirectAcquisition": [
'          {
'            "type": "text/html",
'            "child": [
'              { "type": "application/epub+zip", "href": "/files/landscape.epub" }
'            ]
'          }
'        ]
'      }
'    },
'    {
'      "rel": "buy",
'      "href": "/flow/buy/landscape",
'      "type": "text/html",
'      "properties": {
'        "priceValue": 12.5,
'        "currency": "EUR"
'      }
'    },
'    { "rel": "alternate", "href": "/opds/1/publications/landscape.opds", "type": "application/atom+xml;profile=opds-catalog;kind=acquisition" }
'  ]
'}
'""",
    )
}
