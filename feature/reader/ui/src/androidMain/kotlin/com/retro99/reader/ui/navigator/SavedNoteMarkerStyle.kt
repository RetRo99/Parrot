package com.retro99.reader.ui.navigator

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate

/** The small "note" pill drawn after a highlight or bookmark sentence that has a note. */
class SavedNoteMarkerStyle(
    val label: String,
    val tint: Int,
) : Decoration.Style {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(label)
        dest.writeInt(tint)
    }

    override fun equals(other: Any?): Boolean =
        other is SavedNoteMarkerStyle && other.label == label && other.tint == tint

    override fun hashCode(): Int = label.hashCode() * 31 + tint

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<SavedNoteMarkerStyle> {
            override fun createFromParcel(source: Parcel) =
                SavedNoteMarkerStyle(label = source.readString().orEmpty(), tint = source.readInt())

            override fun newArray(size: Int): Array<SavedNoteMarkerStyle?> = arrayOfNulls(size)
        }

        /**
         * One box per line of the range; only the last line's box draws the pill, just after
         * the text. The box itself is invisible so the highlight under it shows through.
         */
        fun template(): HtmlDecorationTemplate {
            val className = "parrot-note-marker"
            return HtmlDecorationTemplate(
                layout = HtmlDecorationTemplate.Layout.BOXES,
                element = { decoration ->
                    val style = decoration.style as? SavedNoteMarkerStyle
                    val label = style?.label.orEmpty().escapeHtml()
                    val tint = String.format("#%06X", (style?.tint ?: 0) and 0xFFFFFF)
                    """<div class="$className" data-label="$label" style="--parrot-note: $tint"></div>"""
                },
                stylesheet = """
                    .$className { pointer-events: none; }
                    .$className:last-child::after {
                        content: attr(data-label);
                        position: absolute;
                        left: 100%;
                        top: 50%;
                        transform: translate(3px, -70%);
                        padding: 0 6px;
                        border-radius: 999px;
                        background: var(--parrot-note);
                        color: #fff;
                        font: 600 0.55em/1.5 sans-serif;
                        white-space: nowrap;
                    }
                """.trimIndent(),
            )
        }

        private fun String.escapeHtml(): String =
            replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
