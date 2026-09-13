package cl.villagranquiroz.ohm_launcher

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyFileRepositoryTest {
    @Test
    fun listsDirectoriesFirstAndRejectsTraversal() {
        val root = Files.createTempDirectory("ohm-files").toFile()
        root.resolve("z.txt").writeText("z")
        root.resolve("Folder").mkdir()
        root.resolve("a.txt").writeText("aa")
        val repository = OmarchyFileRepository(root, root.resolve("OhmLauncher/shared"))

        val response = repository.list(root.absolutePath)!!

        assertEquals(listOf("Folder", "a.txt", "z.txt"), response.entries.map { it.name })
        assertTrue(response.entries.first().isDirectory)
        assertNull(repository.list(root.parentFile.resolve("outside").absolutePath))
        assertNull(repository.download("../outside"))
    }

    @Test
    fun uploadUsesSafeBasenameAndCanBeDownloaded() {
        val root = Files.createTempDirectory("ohm-upload").toFile()
        val repository = OmarchyFileRepository(root, root.resolve("OhmLauncher/shared"))

        val stored = repository.upload("folder/picture.jpg", byteArrayOf(1, 2, 3))!!

        assertEquals("picture.jpg", stored.name)
        assertTrue(stored.isFile)
        assertTrue(repository.download(stored.absolutePath)!!.contentEquals(byteArrayOf(1, 2, 3)))
    }
}
