package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class OmarchyPathConfinementTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun allowsRootAndDescendantsButRejectsTraversalAndPrefixCollisions() {
        val root = temporaryFolder.newFolder("shared")
        val sibling = temporaryFolder.newFolder("shared-private")
        val guard = OmarchyPathConfinement(listOf(root))

        assertEquals(root.canonicalFile, guard.resolve(root.path))
        assertEquals(root.resolve("folder/file.txt").canonicalFile, guard.resolve(root.resolve("folder/file.txt").path))
        assertNull(guard.resolve(root.resolve("../${sibling.name}/secret.txt").path))
        assertNull(guard.resolve(sibling.resolve("secret.txt").path))
        assertNull(guard.resolve("relative/file.txt"))
    }

    @Test
    fun rejectsExistingSymlinkThatEscapesAnAllowedRoot() {
        val root = temporaryFolder.newFolder("public")
        val outside = temporaryFolder.newFolder("private")
        Files.createSymbolicLink(root.toPath().resolve("escape"), outside.toPath())
        val guard = OmarchyPathConfinement(listOf(root))

        assertNull(guard.resolve(root.resolve("escape/secret.txt").path))
    }

    @Test
    fun confinesUploadedNamesToOnePlainFileName() {
        val root = temporaryFolder.newFolder("uploads")
        val guard = OmarchyPathConfinement(listOf(root))

        assertEquals(root.resolve("photo.jpg").canonicalFile, guard.resolveUpload(root, "photo.jpg"))
        listOf("../secret", "folder/file", "folder\\file", ".", "..", "", "bad\u0000name")
            .forEach { assertNull(it, guard.resolveUpload(root, it)) }
        assertTrue(OmarchyPathConfinement.isSafeFileName("résumé 2026.pdf"))
    }
}
