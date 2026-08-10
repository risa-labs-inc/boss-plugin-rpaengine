package ai.rever.boss.plugin.dynamic.rpaengine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RpaEngineSettingsManager.isManagedPath] is what stops `rpa_load` resolving an agent-supplied
 * substring onto a file in `~/Downloads`, where a configuration can carry `run_script` - arbitrary
 * JavaScript in a tab holding the user's session.
 */
class ManagedPathTest {

    private val manager = RpaEngineSettingsManager()
    private val root: File = java.nio.file.Files.createTempDirectory("managed").toFile()
    private val managed: File = File(root, "config/rpaengine").apply { mkdirs() }
    private val downloads: File = File(root, "Downloads").apply { mkdirs() }

    private fun check(path: String) = manager.isManagedPath(path, listOf(managed.canonicalFile))

    @kotlin.test.AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a file inside a managed root is managed`() {
        assertTrue(check(File(managed, "plan.json").absolutePath))
    }

    @Test
    fun `a file in Downloads is not`() {
        assertFalse(check(File(downloads, "rpa-evil.json").absolutePath))
    }

    @Test
    fun `traversal back into a managed root is resolved, not rejected on spelling`() {
        // Canonicalisation is the point: this path IS inside the managed root.
        val traversal = File(downloads, "../config/rpaengine/plan.json").absolutePath
        assertTrue(check(traversal))
    }

    @Test
    fun `traversal out of a managed root is rejected`() {
        assertFalse(check(File(managed, "../../Downloads/rpa-evil.json").absolutePath))
    }

    @Test
    fun `a sibling directory sharing the prefix is rejected`() {
        // File.startsWith compares path components, so this must not pass as a string prefix.
        val sibling = File(root, "config/rpaengine-evil").apply { mkdirs() }
        assertFalse(check(File(sibling, "plan.json").absolutePath))
    }

    @Test
    fun `an empty root list refuses everything`() {
        assertFalse(manager.isManagedPath(File(managed, "plan.json").absolutePath, emptyList()))
    }
}
