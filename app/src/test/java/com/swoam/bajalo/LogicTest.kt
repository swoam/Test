package com.swoam.bajalo

import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test
    fun extractsLinksFromSharedText() {
        val text = """
            Mira este video https://www.tiktok.com/@user/video/123?lang=es.
            y este: https://youtu.be/abc123,
            www.instagram.com/reel/XYZ/
            repetido https://youtu.be/abc123
        """.trimIndent()
        assertEquals(
            listOf(
                "https://www.tiktok.com/@user/video/123?lang=es",
                "https://youtu.be/abc123",
                "https://www.instagram.com/reel/XYZ/",
            ),
            Links.extract(text),
        )
    }

    @Test
    fun noLinks() {
        assertEquals(emptyList<String>(), Links.extract("hola, sin enlaces"))
    }

    @Test
    fun classifiesFilesIntoFolders() {
        assertEquals(Saver.Kind.VIDEO, Saver.kindOf("clip [x].mp4"))
        assertEquals(Saver.Kind.AUDIO, Saver.kindOf("canción.M4A"))
        assertEquals(Saver.Kind.AUDIO, Saver.kindOf("tema.mp3"))
        assertEquals(Saver.Kind.PHOTO, Saver.kindOf("foto.jpg"))
        assertEquals(Saver.Kind.VIDEO, Saver.kindOf("sin_extension"))
    }

    @Test
    fun durations() {
        assertEquals("0:59", formatDuration(59))
        assertEquals("3:05", formatDuration(185))
        assertEquals("1:01:01", formatDuration(3661))
    }
}
