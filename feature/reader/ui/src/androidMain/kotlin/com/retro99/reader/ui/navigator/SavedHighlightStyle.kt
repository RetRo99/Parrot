package com.retro99.reader.ui.navigator

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.toCss

/** Saved-item fills sit behind the glyphs, even when their themed tint is opaque. */
class SavedHighlightStyle(val tint: Int) : Decoration.Style {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
    }

    override fun equals(other: Any?): Boolean = other is SavedHighlightStyle && other.tint == tint

    override fun hashCode(): Int = tint

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<SavedHighlightStyle> {
            override fun createFromParcel(source: Parcel) = SavedHighlightStyle(source.readInt())
            override fun newArray(size: Int): Array<SavedHighlightStyle?> = arrayOfNulls(size)
        }

        fun template() = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            element = { decoration ->
                val tint = (decoration.style as SavedHighlightStyle).tint
                """<div class="parrot-saved-highlight" style="background-color: ${tint.toCss()} !important;"/>"""
            },
            stylesheet = """
                .parrot-saved-highlight {
                    z-index: -1;
                    border-radius: 3px;
                    box-sizing: border-box;
                }
            """.trimIndent(),
        )
    }
}
