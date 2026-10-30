package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.FileAttachments
import io.github.nideta231.hermesremote.data.FileAttachments.Preview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardAttachmentTest {
    @Test fun overTheLimitSaysSizeAndLimit() {
        assertNull(FileAttachments.boardLimitError("a.png", 10L * 1024 * 1024))
        assertNull(FileAttachments.boardLimitError("a.png", null))  // unknown size: the read and the bridge still check
        assertEquals("clip.mp4 is 24.5 MB; board attachments can be up to 10 MB",
            FileAttachments.boardLimitError("clip.mp4", (24.5 * 1024 * 1024).toLong()))
    }

    @Test fun sizeLabels() {
        assertEquals("830 KB", FileAttachments.sizeLabel(830L * 1024))
        assertEquals("2.0 MB", FileAttachments.sizeLabel(2L * 1024 * 1024))
    }

    @Test fun previewKindByExtension() {
        assertEquals(Preview.PICTURE, FileAttachments.previewOf("/x/files/p1/Shot One.PNG"))
        assertEquals(Preview.TEXT, FileAttachments.previewOf("Data/report.md"))
        assertEquals(Preview.NONE, FileAttachments.previewOf("/x/app.apk"))
        assertEquals(Preview.NONE, FileAttachments.previewOf("/x/Makefile"))
    }

    @Test fun textPeekCutsLongFiles() {
        assertEquals("a\nb", FileAttachments.textPeek("a\nb\n"))
        assertEquals("1\n2\n…", FileAttachments.textPeek("1\n2\n3\n4", maxLines = 2))
    }
}
