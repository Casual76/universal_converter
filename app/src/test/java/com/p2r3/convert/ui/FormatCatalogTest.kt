package com.p2r3.convert.ui

import com.p2r3.convert.engine.FormatOption
import dev.antigravity.fluidengine.ui.theme.FluidTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCatalogTest {

    private var nextId = 0

    private fun format(
        format: String,
        name: String,
        extension: String = format,
        mime: String,
        category: String? = null,
        handler: String = "canvas",
        from: Boolean = true,
        to: Boolean = true,
    ) = FormatOption(
        id = nextId++,
        name = name,
        format = format,
        extension = extension,
        mime = mime,
        categories = listOfNotNull(category),
        from = from,
        to = to,
        handler = handler,
    )

    private val png = format("png", "Portable Network Graphics", mime = "image/png", category = "image")
    private val pngMagick = format("png", "Portable Network Graphics", mime = "image/png", category = "image", handler = "ImageMagick")
    private val jpeg = format("jpeg", "Joint Photographic Experts Group JFIF", extension = "jpg", mime = "image/jpeg", category = "image")
    private val mjpeg = format("mjpeg", "raw MJPEG video", extension = "mjpg", mime = "video/x-mjpeg", category = "video", handler = "FFmpeg")
    private val jp2 = format("jp2", "JPEG 2000", mime = "image/jp2", category = "image", handler = "ImageMagick")
    private val pdf = format("pdf", "Portable Document Format", mime = "application/pdf", category = "document", from = false)
    private val mp3 = format("mp3", "MP3", mime = "audio/mpeg", category = "audio", to = false)
    private val zip = format("zip", "ZIP archive", mime = "application/zip", category = "archive")
    private val obj = format("obj", "Wavefront OBJ", mime = "model/obj")

    private val all = listOf(png, pngMagick, jpeg, mjpeg, jp2, pdf, mp3, zip, obj)

    @Test
    fun `simple mode shows a format once, advanced mode once per tool`() {
        val simple = FormatCatalog.available(all, PickerTarget.Target, simpleMode = true)
        val advanced = FormatCatalog.available(all, PickerTarget.Target, simpleMode = false)
        assertEquals(1, simple.count { it.format == "png" })
        assertEquals(2, advanced.count { it.format == "png" })
    }

    @Test
    fun `each side only offers what it can do`() {
        val sources = FormatCatalog.available(all, PickerTarget.Source, simpleMode = false)
        val targets = FormatCatalog.available(all, PickerTarget.Target, simpleMode = false)
        assertTrue(pdf !in sources && pdf in targets)
        assertTrue(mp3 in sources && mp3 !in targets)
    }

    @Test
    fun `families follow the order people look for, unknown ones at the end`() {
        val groups = FormatCatalog.groups(all)
        assertEquals(listOf("image", "document", "audio", "video", "archive", "model"), groups)
        // `obj` has no category: its family is the MIME's, which happens to be a known one.
        assertEquals("model", obj.group)
    }

    @Test
    fun `an exact short name or extension comes first`() {
        val result = FormatCatalog.visible(all, "jpg", group = null)
        assertEquals(jpeg, result.first())
        assertTrue(mjpeg in result)
    }

    @Test
    fun `every word of the search has to match`() {
        val result = FormatCatalog.visible(all, "jpeg 2000", group = null)
        assertEquals(listOf(jp2), result)
    }

    @Test
    fun `a family narrows the list and an empty search keeps it whole`() {
        val images = FormatCatalog.visible(all, "", group = "image")
        assertTrue(images.all { it.group == "image" })
        assertEquals(4, images.size)
        assertEquals(all.size, FormatCatalog.visible(all, "  ", group = null).size)
    }

    @Test
    fun `labels are Italian and an unknown family is capitalised`() {
        assertEquals("Immagini", FormatCatalog.groupLabel("image"))
        assertEquals("Fogli di calcolo", FormatCatalog.groupLabel("spreadsheet"))
        assertEquals("Chemistry", FormatCatalog.groupLabel("chemistry"))
    }

    @Test
    fun `the tile colour is per family`() {
        assertEquals(FluidTone.Primary, FormatCatalog.groupTone("image"))
        assertEquals(FluidTone.Info, FormatCatalog.groupTone("video"))
        assertEquals(FluidTone.Neutral, FormatCatalog.groupTone("chemistry"))
    }

    @Test
    fun `the badge is at most four letters`() {
        assertEquals("JPEG", FormatCatalog.badge(jpeg))
        assertEquals("MJPE", FormatCatalog.badge(mjpeg))
    }

    @Test
    fun `a file is recognised by extension first, then by MIME`() {
        assertEquals(jpeg, FormatCatalog.detect(all, "JPG", "image/jpeg"))
        // Two tools offer PNG: the one that declares the same MIME wins, then the first.
        assertEquals(png, FormatCatalog.detect(all, "png", "image/png"))
        assertEquals(zip, FormatCatalog.detect(all, "", "application/zip"))
        assertNull(FormatCatalog.detect(all, "xyz", "application/x-unknown"))
        // Only what can be read is a candidate.
        assertNull(FormatCatalog.detect(all, "pdf", "application/pdf"))
    }

    @Test
    fun `the itinerary marks done, running and pending steps`() {
        // png -> jpeg -> pdf, running the second step (jpeg -> pdf).
        assertEquals(StepState.Done, stepState(index = 0, step = 2))
        assertEquals(StepState.Done, stepState(index = 1, step = 2))
        assertEquals(StepState.Current, stepState(index = 2, step = 2))
        assertEquals(StepState.Pending, stepState(index = 3, step = 2))
        // Before the first step starts, only the source is done.
        assertEquals(StepState.Pending, stepState(index = 1, step = 0))
    }
}
