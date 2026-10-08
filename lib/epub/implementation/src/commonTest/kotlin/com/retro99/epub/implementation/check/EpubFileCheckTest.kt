package com.retro99.epub.implementation.check

import com.retro99.epub.api.EpubFileCheck
import com.retro99.epub.api.EpubFileLimits
import com.retro99.epub.api.EpubFileProblem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubFileCheckTest {

    private fun check(bytes: ByteArray): EpubFileCheck = checkEpub(ByteArraySource(bytes))

    private fun problem(bytes: ByteArray): EpubFileProblem? = (check(bytes) as? EpubFileCheck.NotAnEpub)?.problem

    private fun withEntries(vararg extra: TestEntry): ByteArray = TestZip.build(TestEpub.entries() + extra)

    @Test
    fun `a minimal epub is valid whether its entries are stored or deflated`() {
        assertEquals(EpubFileCheck.Valid, check(TestEpub.valid(deflated = false)))
        assertEquals(EpubFileCheck.Valid, check(TestEpub.valid(deflated = true)))
    }

    @Test
    fun `the file is closed after a check whatever the answer`() {
        listOf(TestEpub.valid(), ByteArray(0), "<html></html>".encodeToByteArray(), TestEpub.valid().copyOf(40)).forEach { bytes ->
            val source = ByteArraySource(bytes)
            checkEpub(source)
            assertTrue(source.closed)
        }
    }

    @Test
    fun `an empty file is not a book`() {
        assertEquals(EpubFileProblem.Empty, problem(ByteArray(0)))
    }

    @Test
    fun `a web page is not a book`() {
        val page = "<!DOCTYPE html><html><body>Please sign in to download.</body></html>".encodeToByteArray()
        assertEquals(EpubFileProblem.NotAZip, problem(page))
        assertEquals(EpubFileProblem.NotAZip, problem("PK".encodeToByteArray()))
    }

    @Test
    fun `a truncated download is not a book`() {
        val whole = TestEpub.valid()
        listOf(whole.size - 1, whole.size / 2, 64).forEach { length ->
            assertEquals(EpubFileProblem.NotAZip, problem(whole.copyOf(length)), "cut at $length")
        }
    }

    @Test
    fun `a zip that is not an epub is not a book`() {
        val plainZip = TestZip.build(listOf(TestEntry("readme.txt", "hello")))
        assertEquals(EpubFileProblem.MimetypeNotFirst, problem(plainZip))
    }

    @Test
    fun `mimetype must be the first entry`() {
        val entries = TestEpub.entries()
        val reordered = listOf(entries[1], entries[0], entries[2], entries[3])
        assertEquals(EpubFileProblem.MimetypeNotFirst, problem(TestZip.build(reordered)))
    }

    @Test
    fun `mimetype must say epub`() {
        val entries = listOf(TestEntry("mimetype", "application/zip")) + TestEpub.entries().drop(1)
        assertEquals(EpubFileProblem.WrongMimetype, problem(TestZip.build(entries)))
        val padded = listOf(TestEntry("mimetype", "application/epub+zip\n")) + TestEpub.entries().drop(1)
        assertEquals(EpubFileCheck.Valid, check(TestZip.build(padded)))
    }

    @Test
    fun `container xml must exist`() {
        val entries = TestEpub.entries().filterNot { entry -> entry.name == "META-INF/container.xml" }
        assertEquals(EpubFileProblem.NoContainer, problem(TestZip.build(entries)))
    }

    @Test
    fun `the container must point at a package document that exists`() {
        val missing = TestEpub.entries().filterNot { entry -> entry.name == "OEBPS/content.opf" }
        assertEquals(EpubFileProblem.NoPackageDocument, problem(TestZip.build(missing)))

        val noRootfile = TestEpub.entries().map { entry ->
            if (entry.name == "META-INF/container.xml") TestEntry(entry.name, "<container><rootfiles/></container>") else entry
        }
        assertEquals(EpubFileProblem.NoPackageDocument, problem(TestZip.build(noRootfile)))
    }

    @Test
    fun `a package document that is another archive is rejected`() {
        val nested = TestEpub.entries().map { entry ->
            if (entry.name == "OEBPS/content.opf") TestEntry(entry.name, TestEpub.valid()) else entry
        }
        assertEquals(EpubFileProblem.NoPackageDocument, problem(TestZip.build(nested)))

        val notPackage = TestEpub.entries().map { entry ->
            if (entry.name == "OEBPS/content.opf") TestEntry(entry.name, "<html><body>hi</body></html>") else entry
        }
        assertEquals(EpubFileProblem.NoPackageDocument, problem(TestZip.build(notPackage)))
    }

    @Test
    fun `entries that would escape the book folder are rejected`() {
        listOf(
            "../outside.xhtml",
            "OEBPS/../../outside.xhtml",
            "/etc/passwd",
            "C:/Windows/system.ini",
            "OEBPS\\..\\outside.xhtml",
            "OEBPS/bad\u0000name.xhtml",
        ).forEach { name ->
            assertEquals(EpubFileProblem.UnsafeEntryPath, problem(withEntries(TestEntry(name, "x"))), name)
        }
    }

    @Test
    fun `ordinary entry names are allowed`() {
        listOf("OEBPS/chapter 1.xhtml", "OEBPS/images/cover..jpg", "OEBPS/..hidden", "OEBPS/Größe/ü.xhtml", "OEBPS/a:b.txt")
            .forEach { name -> assertTrue(isSafeEntryPath(name), name) }
        assertFalse(isSafeEntryPath(""))
        assertFalse(isSafeEntryPath(".."))
    }

    @Test
    fun `more entries than the limit is rejected`() {
        val many = TestEpub.entries() + List(EpubFileLimits.MAX_ENTRIES) { index -> TestEntry("OEBPS/f$index", "") }
        assertEquals(EpubFileProblem.TooManyEntries, problem(TestZip.build(many)))
    }

    @Test
    fun `an archive that claims to unpack past the size limit is rejected`() {
        val bomb = withEntries(
            TestEntry("OEBPS/a.bin", ByteArray(8), claimedUncompressedSize = 0xFFFF_FFF0L),
        )
        assertEquals(EpubFileProblem.TooLargeUncompressed, problem(bomb))
    }

    @Test
    fun `a tiny archive that claims a huge expansion is rejected`() {
        val claimed = EpubFileLimits.RATIO_CHECK_FLOOR_BYTES + 1
        val bomb = withEntries(TestEntry("OEBPS/a.bin", ByteArray(8), deflated = true, claimedUncompressedSize = claimed))
        assertEquals(EpubFileProblem.TooLargeUncompressed, problem(bomb))
    }

    @Test
    fun `entries that share the same data are rejected`() {
        val overlapping = withEntries(
            TestEntry("OEBPS/copy1.xhtml", ByteArray(0), sharesDataWith = "OEBPS/chapter1.xhtml"),
            TestEntry("OEBPS/copy2.xhtml", ByteArray(0), sharesDataWith = "OEBPS/chapter1.xhtml"),
        )
        assertEquals(EpubFileProblem.MalformedArchive, problem(overlapping))
    }

    @Test
    fun `a name used twice is rejected`() {
        val twice = withEntries(TestEntry("OEBPS/chapter1.xhtml", "other"))
        assertEquals(EpubFileProblem.MalformedArchive, problem(twice))
    }

    @Test
    fun `an archive glued to other data is rejected`() {
        val epub = TestEpub.valid()
        val page = "<html><body>".encodeToByteArray()
        assertEquals(EpubFileProblem.NotAZip, problem(TestZip.build(TestEpub.entries(), prefix = page)))
        assertEquals(EpubFileProblem.MalformedArchive, problem(TestZip.build(TestEpub.entries(), suffix = ByteArray(16))))
        assertEquals(EpubFileProblem.MalformedArchive, problem(TestZip.build(TestEpub.entries(), prefix = epub)))
        assertEquals(EpubFileProblem.MalformedArchive, problem(epub + epub))
    }

    @Test
    fun `a directory that lists fewer entries than it declares is rejected`() {
        val lying = TestZip.build(TestEpub.entries(), claimedEntryCount = 9)
        assertEquals(EpubFileProblem.MalformedArchive, problem(lying))
    }

    @Test
    fun `encrypted content documents mean the book is protected`() {
        val aes = TestEpub.encryption("http://www.w3.org/2001/04/xmlenc#aes256-cbc", "OEBPS/chapter1.xhtml")
        assertEquals(EpubFileCheck.Protected, check(withEntries(TestEntry("META-INF/encryption.xml", aes))))
        assertEquals(EpubFileCheck.Protected, check(withEntries(TestEntry("META-INF/encryption.xml", aes, deflated = true))))
    }

    @Test
    fun `font obfuscation alone is not protection`() {
        listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC").forEach { algorithm ->
            val fonts = TestEpub.encryption(algorithm, "OEBPS/fonts/serif.otf")
            assertEquals(EpubFileCheck.Valid, check(withEntries(TestEntry("META-INF/encryption.xml", fonts))), algorithm)
        }
    }

    @Test
    fun `obfuscated fonts next to encrypted content is still protected`() {
        val mixed = """<encryption xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
              <enc:CipherData><enc:CipherReference URI="OEBPS/fonts/serif.otf"/></enc:CipherData></enc:EncryptedData>
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/>
              <KeyInfo><EncryptedKey><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#rsa-1_5"/></EncryptedKey></KeyInfo>
              <enc:CipherData><enc:CipherReference URI="OEBPS/chapter1.xhtml"/></enc:CipherData></enc:EncryptedData>
            </encryption>"""
        assertEquals(EpubFileCheck.Protected, check(withEntries(TestEntry("META-INF/encryption.xml", mixed))))
    }

    @Test
    fun `encrypted data with no stated method is protected`() {
        val unknown = "<encryption><EncryptedData><CipherData><CipherReference URI=\"OEBPS/chapter1.xhtml\"/></CipherData></EncryptedData></encryption>"
        assertEquals(EpubFileCheck.Protected, check(withEntries(TestEntry("META-INF/encryption.xml", unknown))))
    }

    @Test
    fun `an empty encryption file protects nothing`() {
        assertEquals(EpubFileCheck.Valid, check(withEntries(TestEntry("META-INF/encryption.xml", "<encryption/>"))))
    }

    @Test
    fun `zip level encryption of an entry is protected`() {
        val encrypted = withEntries(TestEntry("OEBPS/chapter2.xhtml", ByteArray(12), flags = 0x1))
        assertEquals(EpubFileCheck.Protected, check(encrypted))
    }

    @Test
    fun `a missing file is unreadable`() = runTest {
        val checker = EpubFileCheckerImpl().apply { openSource = { null } }
        assertEquals(EpubFileCheck.NotAnEpub(EpubFileProblem.Unreadable), checker.check("/nowhere/book.epub"))
        val throwing = EpubFileCheckerImpl().apply { openSource = { error("denied") } }
        assertEquals(EpubFileCheck.NotAnEpub(EpubFileProblem.Unreadable), throwing.check("/nowhere/book.epub"))
    }

    @Test
    fun `the checker reads the file it is given`() = runTest {
        val checker = EpubFileCheckerImpl().apply { openSource = { ByteArraySource(TestEpub.valid()) } }
        assertEquals(EpubFileCheck.Valid, checker.check("/staging/a.epub.part"))
    }
}
