package ai.pushpilot.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun valuesSurviveANewInstanceAndNullRemoves() {
        val dir = folder.root
        FileStore { dir }.put("a" to "1", "b" to "two \"quoted\"", "c" to "3")
        // A new instance reads the file, as after process death.
        val reopened = FileStore { dir }
        assertEquals("1", reopened.get("a"))
        assertEquals("two \"quoted\"", reopened.get("b"))
        reopened.put("a" to null, "c" to "33")
        val again = FileStore { dir }
        assertNull(again.get("a"))
        assertEquals("33", again.get("c"))
        assertEquals(listOf("ai.pushpilot.sdk.json"), dir.list()!!.toList())
    }

    @Test
    fun missingFileIsAFreshInstall() {
        val store = FileStore { File(folder.root, "empty").apply { mkdirs() } }
        assertNull(store.get("installation_id"))
    }

    @Test
    fun corruptFileStartsOverAndIsReplacedOnNextWrite() {
        val dir = folder.root
        File(dir, "ai.pushpilot.sdk.json").writeText("{not json")
        val store = FileStore { dir }
        assertNull(store.get("a"))
        store.put("a" to "1")
        assertEquals("1", FileStore { dir }.get("a"))
        assertFalse(File(dir, "ai.pushpilot.sdk.json.tmp").exists())
    }
}
