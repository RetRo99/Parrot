package com.retro99.server.api

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogueImageBytesTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
    private val gif = "GIF89a".encodeToByteArray() + byteArrayOf(1, 0, 1, 0)
    private val webp = "RIFF".encodeToByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBPVP8 ".encodeToByteArray()
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".encodeToByteArray()

    private fun dataUri(type: String, bytes: ByteArray) = "data:$type;base64,${Base64.Default.encode(bytes)}"

    @Test fun raster_types_are_recognised_by_their_first_bytes() {
        assertEquals("image/png", catalogueRasterImageType(png))
        assertEquals("image/jpeg", catalogueRasterImageType(jpeg))
        assertEquals("image/gif", catalogueRasterImageType(gif))
        assertEquals("image/gif", catalogueRasterImageType("GIF87a".encodeToByteArray() + byteArrayOf(1, 0)))
        assertEquals("image/webp", catalogueRasterImageType(webp))
    }

    @Test fun anything_else_is_not_an_image_we_decode() {
        assertNull(catalogueRasterImageType(svg))
        assertNull(catalogueRasterImageType("<?xml version=\"1.0\"?><svg/>".encodeToByteArray()))
        assertNull(catalogueRasterImageType("<html><body>Sign in</body></html>".encodeToByteArray()))
        assertNull(catalogueRasterImageType("RIFF".encodeToByteArray() + byteArrayOf(4, 0, 0, 0) + "WAVEfmt ".encodeToByteArray()))
        assertNull(catalogueRasterImageType(byteArrayOf()))
        assertNull(catalogueRasterImageType(byteArrayOf(0x89.toByte(), 0x50)))
        // BMP, ICO and TIFF are raster but not on the list.
        assertNull(catalogueRasterImageType("BM".encodeToByteArray() + ByteArray(20)))
        assertNull(catalogueRasterImageType(byteArrayOf(0x49, 0x49, 0x2A, 0) + ByteArray(20)))
    }

    @Test fun an_inline_raster_image_is_decoded() {
        assertContentEquals(png, decodeCatalogueDataImage(dataUri("image/png", png)))
        assertContentEquals(jpeg, decodeCatalogueDataImage(dataUri("image/jpeg", jpeg)))
        assertContentEquals(gif, decodeCatalogueDataImage(dataUri("image/gif", gif)))
        assertContentEquals(webp, decodeCatalogueDataImage(dataUri("image/webp", webp)))
        assertContentEquals(png, decodeCatalogueDataImage("DATA:IMAGE/PNG;BASE64,${Base64.Default.encode(png)}"))
        assertContentEquals(png, decodeCatalogueDataImage("data:image/png;charset=utf-8;base64,${Base64.Default.encode(png)}"))
    }

    @Test fun inline_svg_is_rejected_whatever_it_claims_to_be() {
        assertNull(decodeCatalogueDataImage(dataUri("image/svg+xml", svg)))
        assertNull(decodeCatalogueDataImage(dataUri("image/png", svg)))
        assertNull(decodeCatalogueDataImage("data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%2F%3E"))
        // A real PNG labelled as SVG is still refused: the label decides which decoder a platform picks.
        assertNull(decodeCatalogueDataImage(dataUri("image/svg+xml", png)))
    }

    @Test fun other_inline_content_is_rejected() {
        assertNull(decodeCatalogueDataImage(dataUri("text/html", png)))
        assertNull(decodeCatalogueDataImage(dataUri("image/bmp", "BM".encodeToByteArray() + ByteArray(20))))
        assertNull(decodeCatalogueDataImage("data:;base64,${Base64.Default.encode(png)}"))
        assertNull(decodeCatalogueDataImage("data:image/png,notbase64"))
        assertNull(decodeCatalogueDataImage("data:image/png;base64,***"))
        assertNull(decodeCatalogueDataImage("data:image/png;base64,"))
        assertNull(decodeCatalogueDataImage("data:image/png;base64"))
        assertNull(decodeCatalogueDataImage("https://books.example/cover.png"))
    }

    @Test fun an_inline_image_over_the_ceiling_is_rejected() {
        val atCeiling = png + ByteArray(CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES - png.size)
        assertEquals(CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES, decodeCatalogueDataImage(dataUri("image/png", atCeiling))?.size)
        assertNull(decodeCatalogueDataImage(dataUri("image/png", atCeiling + byteArrayOf(0))))
    }

    @Test fun the_model_never_prints_its_address() {
        val model = CatalogueImageModel("source", "https://books.example/key/abc123/cover.png")
        assertEquals("CatalogueImageModel(redacted)", model.toString())
        assertEquals(true, CatalogueImageModel("s", "data:image/png;base64,AAAA").isInline)
        assertEquals(false, model.isInline)
    }
}
