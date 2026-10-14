package app.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    @Test
    fun filesAreParsed() {
        val json = """{"files":[{"id":"abc123","name":"logo cutout.svg","folder":"Laser","size":2048,"modified":1760000000}]}"""
        val files = Parse.files(json)
        assertEquals(1, files.size)
        assertEquals("abc123", files[0].id)
        assertEquals("logo cutout.svg", files[0].name)
        assertEquals(2048L, files[0].size)
    }

    @Test
    fun emptyFileListIsFine() {
        assertTrue(Parse.files("""{"files":[]}""").isEmpty())
        assertTrue(Parse.files("""{}""").isEmpty())
    }

    @Test
    fun messagesAndCursor() {
        val json = """{"messages":[{"id":4,"from":"room","text":"on our way","at":1},{"id":5,"from":"jarvis","text":"No one answered.","at":2}],"last":5,"waiting":false}"""
        val (msgs, last) = Parse.messages(json)
        assertEquals(2, msgs.size)
        assertEquals("room", msgs[0].from)
        assertEquals(5, last)
    }

    @Test
    fun statusWithLock() {
        val json = """{"ok":true,"device":"Pixel","jarvis":"ready","page_open":true,"problems":["voice: no credit"],"lock":{"enabled":true,"unlocked":false,"seconds_left":0}}"""
        val s = Parse.status(json)
        assertEquals("ready", s.jarvis)
        assertTrue(s.pageOpen)
        assertTrue(s.lockEnabled)
        assertFalse(s.unlocked)
        assertEquals(listOf("voice: no credit"), s.problems)
    }

    @Test
    fun statusToleratesMissingFields() {
        val s = Parse.status("""{"ok":true}""")
        assertEquals("unknown", s.jarvis)
        assertFalse(s.lockEnabled)
        assertTrue(s.problems.isEmpty())
    }

    @Test
    fun fileNameFromHeader() {
        assertEquals("logo cutout.svg", Parse.fileName("attachment; filename*=utf-8''logo%20cutout.svg", "x"))
        assertEquals("a.png", Parse.fileName("attachment; filename=\"a.png\"", "x"))
        assertEquals("fallback", Parse.fileName(null, "fallback"))
        assertEquals("fallback", Parse.fileName("inline", "fallback"))
    }

    @Test
    fun baseUrlGetsAScheme() {
        assertEquals("http://100.64.0.1:8341", Parse.baseUrl("100.64.0.1:8341/"))
        assertEquals("http://100.64.0.1:8341", Parse.baseUrl("  http://100.64.0.1:8341  "))
        assertEquals("https://pc.tailnet.ts.net:8341", Parse.baseUrl("https://pc.tailnet.ts.net:8341"))
    }

    @Test
    fun sizesAndMimeTypes() {
        assertEquals("512 B", Parse.humanSize(512))
        assertEquals("2 KB", Parse.humanSize(2048))
        assertEquals("1.5 MB", Parse.humanSize(1572864))
        assertEquals("image/svg+xml", Parse.mimeFor("Logo.SVG"))
        assertEquals("application/octet-stream", Parse.mimeFor("thing.lbrn2"))
    }
}
