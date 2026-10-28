package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTransferTest {
    private fun offer(entries: List<FolderEntry>, name: String = "Photos", pageCount: Int = 1) = FolderOffer(
        folderId = "6f1c1c55-2f55-4c3e-9a55-7d0f6c2f8a10",
        name = name,
        fileCount = entries.count { !it.isDirectory },
        folderCount = entries.count { it.isDirectory },
        totalSize = entries.sumOf { it.size },
        pageCount = pageCount,
    )

    private val tree = listOf(
        FolderEntry("a.txt", isDirectory = false, size = 3),
        FolderEntry("trip", isDirectory = true),
        FolderEntry("trip/day 1", isDirectory = true),
        FolderEntry("trip/day 1/b.jpg", isDirectory = false, size = 10),
        FolderEntry("empty", isDirectory = true),
    )

    @Test fun acceptsAWellFormedTree() {
        assertNull(validateFolderManifest(offer(tree), tree))
    }

    @Test fun rejectsTraversalAbsoluteAndControlCharacterPaths() {
        listOf("../a.txt", "/etc/passwd", "a/../b", "./a", "a//b", "a/", "tab\there", "nul\u0000", "del\u007f")
            .forEach { assertFalse(it, FolderRules.isValidPath(it)) }
        listOf(".hidden", "back\\slash", "colon:name", "spaces ok ", "Ünïcode/名前").forEach {
            assertTrue(it, FolderRules.isValidPath(it))
        }
    }

    @Test fun boundsDepthPathAndNameLength() {
        assertTrue(FolderRules.isValidPath(List(32) { "d" }.joinToString("/")))
        assertFalse(FolderRules.isValidPath(List(33) { "d" }.joinToString("/")))
        assertTrue(FolderRules.isValidName("n".repeat(255)))
        assertFalse(FolderRules.isValidName("n".repeat(256)))
        assertFalse(FolderRules.isValidName("é".repeat(128)))
        assertFalse(FolderRules.isValidPath(List(6) { "n".repeat(200) }.joinToString("/")))
    }

    @Test fun rejectsEntriesWhoseParentWasNotListedFirst() {
        val orphan = listOf(FolderEntry("trip/b.jpg", isDirectory = false, size = 1))
        assertEquals(FolderRejection.INVALID_MANIFEST, validateFolderManifest(offer(orphan), orphan))
        val fileAsParent = listOf(
            FolderEntry("a", isDirectory = false, size = 1),
            FolderEntry("a/b", isDirectory = false, size = 1),
        )
        assertEquals(FolderRejection.INVALID_MANIFEST, validateFolderManifest(offer(fileAsParent), fileAsParent))
    }

    @Test fun rejectsDuplicatesAndCountsThatDoNotMatchTheOffer() {
        val duplicate = listOf(
            FolderEntry("a.txt", isDirectory = false, size = 1),
            FolderEntry("a.txt", isDirectory = false, size = 1),
        )
        assertEquals(FolderRejection.INVALID_MANIFEST, validateFolderManifest(offer(duplicate), duplicate))
        assertEquals(
            FolderRejection.INVALID_MANIFEST,
            validateFolderManifest(offer(tree).copy(totalSize = 14), tree),
        )
        assertEquals(
            FolderRejection.INVALID_MANIFEST,
            validateFolderManifest(offer(tree).copy(folderCount = 2), tree),
        )
    }

    @Test fun rejectsOffersOutsideTheLimits() {
        val base = offer(tree)
        assertEquals(FolderRejection.LIMIT_EXCEEDED, validateFolderOffer(base.copy(fileCount = 10_001)))
        assertEquals(FolderRejection.LIMIT_EXCEEDED, validateFolderOffer(base.copy(folderCount = 2_001)))
        assertEquals(FolderRejection.LIMIT_EXCEEDED, validateFolderOffer(base.copy(totalSize = (1L shl 40) + 1)))
        assertEquals(FolderRejection.INVALID_MANIFEST, validateFolderOffer(base.copy(fileCount = 0)))
        assertEquals(FolderRejection.INVALID_MANIFEST, validateFolderOffer(base.copy(pageCount = 65)))
        assertEquals(FolderRejection.INVALID_MANIFEST, validateFolderOffer(base.copy(name = "..")))
        val huge = listOf(FolderEntry("big.bin", isDirectory = false, size = 10L * 1024 * 1024 * 1024 + 1))
        assertEquals(FolderRejection.LIMIT_EXCEEDED, validateFolderManifest(offer(huge), huge))
    }

    @Test fun planKeepsStructureAndNumbersSiblingsThatClashIgnoringCase() {
        val entries = listOf(
            FolderEntry("Album", isDirectory = true),
            FolderEntry("Album/photo.jpg", isDirectory = false, size = 1),
            FolderEntry("Album/Photo.JPG", isDirectory = false, size = 1),
            FolderEntry("Album/photo:.jpg", isDirectory = false, size = 1),
            FolderEntry("album", isDirectory = true),
            FolderEntry("album/photo.jpg", isDirectory = false, size = 1),
            FolderEntry("notes", isDirectory = false, size = 1),
            FolderEntry("notes.", isDirectory = false, size = 1),
        )
        val plan = FolderPlan.build(entries)

        assertEquals(
            listOf(
                "Album",
                "Album/photo.jpg",
                "Album/Photo (1).JPG",
                "Album/photo_.jpg",
                "album (1)",
                "album (1)/photo.jpg",
                "notes",
                "notes (1)",
            ),
            entries.indices.map(plan::localPath),
        )
    }

    @Test fun clashingSiblingsAreNumberedInManifestOrder() {
        val clashing = listOf("a*", "a?", "a:", "a|").map { FolderEntry(it, isDirectory = false, size = 0) }
        assertEquals(listOf("a_", "a_ (1)", "a_ (2)", "a_ (3)"), clashing.indices.map(FolderPlan.build(clashing)::localPath))

        // Every case variant of an 11-letter name clashes; numbering stays distinct and linear.
        val variants = (0 until 2_048).map { mask ->
            "abcdefghijk".mapIndexed { bit, char -> if (mask shr bit and 1 == 1) char.uppercaseChar() else char }.joinToString("")
        }.map { FolderEntry(it, isDirectory = false, size = 0) }
        val names = variants.indices.map(FolderPlan.build(variants)::localPath)
        assertEquals(variants.size, names.map(String::lowercase).toSet().size)
        assertEquals("ABcdefghijk (3)", names[3])
    }

    @Test fun safeLocalNamesAreNormalizedTrimmedAndBounded() {
        assertEquals("caf\u00e9", safeLocalName("cafe\u0301"))
        assertEquals("a_b_c", safeLocalName("a<b>c"))
        assertEquals("trailing", safeLocalName("  trailing. . "))
        assertEquals("_", safeLocalName("..."))
        val long = safeLocalName("n".repeat(250) + ".jpeg")
        assertEquals(FolderRules.MAX_LOCAL_NAME_BYTES, utf8Length(long))
        assertTrue(long.endsWith(".jpeg"))
        val wide = safeLocalName("é".repeat(120))
        assertTrue(utf8Length(wide) <= FolderRules.MAX_LOCAL_NAME_BYTES)
    }

    @Test fun rootNameUsesTheFirstFreeNumber() {
        val existing = setOf("photos", "photos (1)")
        assertEquals("Photos (2)", uniqueLocalName("Photos", isDirectory = true) { it.lowercase() in existing })
        assertEquals("Other", uniqueLocalName("Other", isDirectory = true) { it.lowercase() in existing })
        assertNull(uniqueLocalName("Full", isDirectory = true) { true })
    }
}
