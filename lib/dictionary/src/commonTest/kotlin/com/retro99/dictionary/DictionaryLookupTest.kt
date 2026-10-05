package com.retro99.dictionary

import kotlin.test.*

class DictionaryLookupTest {
    private fun entry(word: String) = DictionaryEntry(word, groups = listOf(DictionaryGroup("noun", listOf(DictionarySense("a meaning")))))

    @Test fun selectionRulesExcludePhrasesButKeepContractionsAndHyphens() {
        assertEquals("can't", dictionaryWord(" “can’t!” "))
        assertEquals("empty", dictionaryWord("\u00adempty\u00ad"))
        assertEquals("well-known", dictionaryWord("(well-known)"))
        assertEquals("well-known", dictionaryWord("well‑known"))
        assertEquals("cafe\u0301", dictionaryWord("“cafe\u0301”"))
        assertEquals("B2B", dictionaryWord("B2B"))
        listOf("two words", "two\nwords", "1984", "a/b", "a_b").forEach { assertNull(dictionaryWord(it)) }
    }

    @Test fun exactEntriesWinOverInflectionAliases() {
        val entries = mapOf("running" to entry("running"), "run" to entry("run"))
        assertEquals("running", lookupDictionary("RUNNING", entries::get) { listOf("run") }?.headword)
    }

    @Test fun irregularFormsAndRulesResolveToDictionaryHeadwords() {
        val entries = listOf("mouse", "go", "run", "bake", "try", "can", "will", "reader").associateWith(::entry)
        val forms = mapOf("mice" to listOf("mouse"), "went" to listOf("go"))
        val expected = mapOf("mice" to "mouse", "went" to "go", "running" to "run", "baking" to "bake",
            "tried" to "try", "can't" to "can", "won't" to "will", "reader's" to "reader", "readers" to "reader")
        expected.forEach { (word, headword) -> assertEquals(headword, lookupDictionary(word, entries::get) { forms[it].orEmpty() }?.headword) }
    }

    @Test fun hyphenatedWholeWordWinsBeforeParts() {
        val entries = listOf("well-known", "well").associateWith(::entry)
        assertEquals("well-known", lookupDictionary("well-known", entries::get) { emptyList() }?.headword)
        assertEquals("well", lookupDictionary("well-missing", entries::get) { emptyList() }?.headword)
        assertNull(lookupDictionary("Tomas", entries::get) { emptyList() })
    }

    @Test fun englishLanguageTagsAreRecognised() {
        listOf("en", "en-GB", "EN_us", "eng").forEach { assertTrue(isEnglish(it)) }
        listOf(null, "", "sl", "de", "enochian").forEach { assertFalse(isEnglish(it)) }
    }
}
