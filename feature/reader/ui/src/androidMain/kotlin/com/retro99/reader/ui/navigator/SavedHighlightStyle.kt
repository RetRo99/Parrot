package com.retro99.reader.ui.navigator

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate

/**
 * A saved-item fill, blended onto the page rather than painted behind the glyphs. Blending is
 * what keeps an opaque tint readable: multiplying on a light page leaves the dark glyphs under
 * it dark, screening on a dark page ([darkPage]) leaves the light ones light. It also keeps the
 * fill from being lost, since a book may paint a background behind its own text (body
 * { background: white }), which covers anything painted underneath it - the reason fills used to
 * go missing in books like that.
 */
class SavedHighlightStyle(val tint: Int, val darkPage: Boolean = false) : Decoration.Style {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
        dest.writeInt(if (darkPage) 1 else 0)
    }

    override fun equals(other: Any?): Boolean =
        other is SavedHighlightStyle && other.tint == tint && other.darkPage == darkPage

    override fun hashCode(): Int = tint * 31 + darkPage.hashCode()

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<SavedHighlightStyle> {
            override fun createFromParcel(source: Parcel) =
                SavedHighlightStyle(source.readInt(), source.readInt() != 0)

            override fun newArray(size: Int): Array<SavedHighlightStyle?> = arrayOfNulls(size)
        }

        fun template() = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            element = { decoration ->
                val style = decoration.style as SavedHighlightStyle
                fillElement(tint = style.tint, darkPage = style.darkPage)
            },
            stylesheet = """
                .parrot-saved-highlight {
                    z-index: 0;
                    border-radius: 3px;
                    box-sizing: border-box;
                }
            """.trimIndent(),
        )

        /** One fill box, in markup the navigator can position: [tint] keeps its own alpha. */
        internal fun fillElement(tint: Int, darkPage: Boolean): String {
            val blend = if (darkPage) "screen" else "multiply"
            return """<div class="parrot-saved-highlight" style="background-color: ${tint.toRgba()} !important; mix-blend-mode: $blend;"/>"""
        }
    }
}

/** An ARGB int as CSS, keeping its own alpha - the same conversion the page script does. */
private fun Int.toRgba(): String {
    val alpha = ((this ushr 24) and 0xFF) / 255.0
    return "rgba(${(this shr 16) and 0xFF}, ${(this shr 8) and 0xFF}, ${this and 0xFF}, $alpha)"
}
