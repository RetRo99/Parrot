package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.epub.api.ElementOffset
import com.retro99.epub.api.EpubChapterText
import com.retro99.epub.api.ReadaloudTiming
import com.retro99.epub.api.TimedClip
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.sync.domain.ProgressKind

/** The same text in every copy, one sentence per element. */
val SENTENCES = listOf(
    "It is a truth universally acknowledged, that a single man in possession of a good " +
        "fortune, must be in want of a wife.",
    "However little known the feelings or views of such a man may be on his first entering " +
        "a neighbourhood, this truth is so well fixed in the minds of the surrounding families.",
    "My dear Mr. Bennet, said his lady to him one day, have you heard that Netherfield Park " +
        "is let at last?",
    "Mr. Bennet replied that he had not, and went back to reading his newspaper in silence.",
    "But it is, returned she; for Mrs. Long has just been here, and she told me all about it.",
    "Mr. Bennet made no answer, which his wife took as an invitation to say a great deal more.",
    "Do you not want to know who has taken it? cried his wife impatiently across the table.",
    "You want to tell me, and I have no objection to hearing it, he said without looking up.",
    "This was invitation enough, and she began at once to describe the young man in detail.",
    "Why, my dear, you must know, Mrs. Long says that Netherfield is taken by a young man.",
    "He came down on Monday in a chaise and four to see the place, and was much delighted.",
    "He agreed with Mr. Morris immediately and is to take possession before Michaelmas.",
)

/** A chapter whose elements are [ids] holding [sentenceIndexes], after an optional heading. */
fun chapter(
    href: String,
    ids: List<String>,
    texts: List<String>,
    heading: String? = null,
): EpubChapterText {
    val builder = StringBuilder()
    val offsets = mutableListOf<ElementOffset>()
    if (heading != null) builder.append(heading)
    ids.zip(texts).forEach { (id, text) ->
        if (builder.isNotEmpty()) builder.append('\n')
        val start = builder.length
        builder.append(text)
        offsets += ElementOffset(id, start, builder.length)
    }
    return EpubChapterText(href, heading, builder.toString(), offsets)
}

/** Splits [SENTENCES] into chapters of the given sizes. */
fun book(
    prefix: String,
    sizes: List<Int>,
    texts: List<String> = SENTENCES,
    headings: Boolean = false,
): List<EpubChapterText> {
    var next = 0
    return sizes.mapIndexed { chapterIndex, size ->
        val indexes = (next until next + size).toList()
        next += size
        chapter(
            href = "OEBPS/$prefix${chapterIndex + 1}.xhtml",
            ids = indexes.map { index -> "$prefix-s$index" },
            texts = indexes.map { index -> texts[index] },
            heading = if (headings) "Part ${chapterIndex + 1}" else null,
        )
    }
}

/** One clip per sentence, [clipMs] each, chapters split across two audio files. */
fun timingFor(chapters: List<EpubChapterText>, clipMs: Long = 10_000L): ReadaloudTiming {
    val clips = mutableListOf<TimedClip>()
    val half = chapters.size / 2
    var fileTime = 0L
    chapters.forEachIndexed { chapterIndex, chapter ->
        val audio = if (chapterIndex < half) "audio/1.mp3" else "audio/2.mp3"
        if (chapterIndex == half) fileTime = 0L
        chapter.elementOffsets.forEach { element ->
            clips += TimedClip(chapter.href, element.elementId, audio, fileTime, fileTime + clipMs)
            fileTime += clipMs
        }
    }
    val firstLength = clips.filter { clip -> clip.audioSrc == "audio/1.mp3" }
        .maxOf { clip -> clip.clipEndMs }
    val secondLength = clips.filter { clip -> clip.audioSrc == "audio/2.mp3" }
        .maxOf { clip -> clip.clipEndMs }
    return ReadaloudTiming(
        clips = clips,
        audioFileOffsetsMs = mapOf("audio/1.mp3" to 0L, "audio/2.mp3" to firstLength),
        totalDurationMs = firstLength + secondLength,
    )
}

fun copy(
    source: CopySource,
    id: String,
    kind: ProgressKind = ProgressKind.EBOOK,
    chapters: List<EpubChapterText>? = null,
    timing: ReadaloudTiming? = null,
    contentHash: String? = null,
    audioDurationMs: Long? = null,
    trackDurationsMs: List<Long>? = null,
) = CopyContent(
    key = CopyKey(source, id),
    serverId = "${source.prefix}-server",
    bookUuid = id,
    kind = kind,
    contentHash = contentHash,
    chapters = chapters,
    timing = timing,
    audioDurationMs = audioDurationMs,
    trackDurationsMs = trackDurationsMs,
)

fun textPosition(
    chapters: List<EpubChapterText>,
    elementId: String,
    bookUuid: String = "source",
): PositionDomainModel {
    val chapterIndex = chapters.indexOfFirst { chapter ->
        chapter.elementOffsets.any { element -> element.elementId == elementId }
    }
    val chapter = chapters[chapterIndex]
    val element = chapter.elementOffsets.first { offset -> offset.elementId == elementId }
    val point = TextPoint(chapterIndex, element.startOffset)
    return position(bookUuid).copy(
        locatorHref = chapter.href,
        chapterIndex = chapterIndex,
        totalChapters = chapters.size,
        progression = chapters.progressionAt(point),
        totalProgression = chapters.totalProgressionAt(point),
        cssSelector = "#$elementId",
    )
}

fun audioPosition(ms: Long, durationMs: Long, bookUuid: String = "source") =
    position(bookUuid).copy(
        audioTimestampMs = ms,
        totalDurationMs = durationMs,
        progression = ms.toDouble() / durationMs,
        totalProgression = ms.toDouble() / durationMs,
    )

fun position(bookUuid: String) = PositionDomainModel(
    bookUuid = bookUuid,
    serverId = "server",
    timestamp = null,
    createdAt = null,
    updatedAt = null,
    locatorHref = null,
    locatorType = null,
    locatorTitle = null,
    locatorTarget = null,
    audioTimestampMs = null,
    chapterIndex = null,
    progression = null,
    totalChapters = null,
    totalDurationMs = null,
    totalProgression = null,
    position = null,
)

/** The offset where [sentenceIndex] starts in [chapters], as a point. */
fun List<EpubChapterText>.pointOfSentence(sentenceIndex: Int): TextPoint {
    forEachIndexed { chapterIndex, chapter ->
        val offset = chapter.text.indexOf(SENTENCES[sentenceIndex].take(40))
        if (offset >= 0) return TextPoint(chapterIndex, offset)
    }
    error("sentence $sentenceIndex not found")
}
