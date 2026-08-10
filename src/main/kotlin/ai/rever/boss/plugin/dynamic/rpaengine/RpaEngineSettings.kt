package ai.rever.boss.plugin.dynamic.rpaengine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent settings for the RPA Engine plugin
 */
@Serializable
data class RpaEnginePersistedSettings(
    val lastConfigPath: String = "",
    val executionSpeed: Float = 1.0f,
    val humanLikeMode: Boolean = true,
    val stopOnError: Boolean = true,
    val recentConfigurations: List<String> = emptyList()
)

/**
 * Manager for RPA Engine settings and configurations
 */
class RpaEngineSettingsManager {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configDir: File
        get() {
            val homeDir = System.getProperty("user.home")
            val dir = File(homeDir, ".boss/config/rpaengine")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    private val settingsFile: File
        get() = File(configDir, SETTINGS_FILE_NAME)

    // Also check the RPA Recorder's saved configurations
    private val rpaRecorderConfigDir: File
        get() {
            val homeDir = System.getProperty("user.home")
            return File(homeDir, ".boss/config/rparecorder/configurations")
        }

    // Downloads folder for exported configs
    private val downloadsDir: File
        get() {
            val homeDir = System.getProperty("user.home")
            return File(homeDir, "Downloads")
        }

    private var cachedSettings: RpaEnginePersistedSettings? = null

    /**
     * Load settings from disk
     */
    fun loadSettings(): RpaEnginePersistedSettings {
        cachedSettings?.let { return it }

        return try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                json.decodeFromString<RpaEnginePersistedSettings>(content).also {
                    cachedSettings = it
                }
            } else {
                RpaEnginePersistedSettings().also {
                    cachedSettings = it
                    saveSettings(it)
                }
            }
        } catch (e: Exception) {
            RpaEnginePersistedSettings().also {
                cachedSettings = it
            }
        }
    }

    /**
     * Save settings to disk
     */
    fun saveSettings(settings: RpaEnginePersistedSettings) {
        try {
            cachedSettings = settings
            settingsFile.writeText(json.encodeToString(RpaEnginePersistedSettings.serializer(), settings))
        } catch (e: Exception) {
            // Log error but continue
        }
    }

    /**
     * Update settings
     */
    fun updateSettings(update: (RpaEnginePersistedSettings) -> RpaEnginePersistedSettings) {
        val current = loadSettings()
        val updated = update(current)
        saveSettings(updated)
    }

    /**
     * Whether [path] sits in a directory BOSS owns, as opposed to `~/Downloads`.
     *
     * Anything writable by a download is not a trustworthy source for an agent-selected plan:
     * configurations can carry `run_script` actions, which execute arbitrary JavaScript in a tab
     * holding the user's session.
     */
    fun isManagedPath(path: String): Boolean = isManagedPath(path, managedRoots())

    /**
     * As [isManagedPath], against an explicit set of roots.
     *
     * The roots are a parameter so this is testable without touching the real home directory, and
     * so a scan can resolve them once instead of per candidate - the `configDir` getter runs
     * `exists()` + `mkdirs()`, which is a poor thing to hide inside a security predicate called in
     * a loop.
     *
     * `File.startsWith` compares path *components*, so a sibling named `rpaengine-evil` is
     * correctly rejected. Do not turn it into a string prefix.
     */
    fun isManagedPath(path: String, roots: List<File>): Boolean =
        // canonicalFile is what throws (IOException), not startsWith - so it has to be inside.
        // Fail closed: a path that cannot be canonicalised is not one to hand an agent.
        runCatching {
            val file = File(path).canonicalFile
            roots.any { dir -> file.startsWith(dir) }
        }.getOrDefault(false)

    /** The canonical directories BOSS owns, resolved once. */
    fun managedRoots(): List<File> =
        listOf(configDir, rpaRecorderConfigDir).mapNotNull { dir ->
            runCatching { dir.canonicalFile }.getOrNull()
        }

    /**
     * Find all available RPA configuration files.
     *
     * Sources, newest first: the engine's own config directory, the RPA Recorder's saved
     * configurations, and `rpa`-named JSON in Downloads.
     *
     * The engine's own directory used to be excluded, which is why a plan written there - what
     * LLM RPA's handoff produces - was invisible here and a run silently did nothing.
     */
    fun findAvailableConfigurations(): List<ConfigFileInfo> {
        val configs = mutableListOf<ConfigFileInfo>()

        collectFrom(configDir, configs) { it.extension == "json" && it.name != SETTINGS_FILE_NAME }
        collectFrom(rpaRecorderConfigDir, configs) { it.extension == "json" }
        collectFrom(downloadsDir, configs) { it.extension == "json" && it.name.contains("rpa", ignoreCase = true) }

        // Sort by last modified (newest first)
        return configs.sortedByDescending { it.lastModified }
    }

    /**
     * Add every file in [dir] matching [accept] that parses as a configuration to [into],
     * skipping paths already collected from an earlier source.
     */
    private fun collectFrom(
        dir: File,
        into: MutableList<ConfigFileInfo>,
        accept: (File) -> Boolean,
    ) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles { file -> accept(file) }?.forEach { file ->
            if (into.any { it.path == file.absolutePath }) return@forEach
            val config = runCatching { loadConfigurationFile(file) }.getOrNull() ?: return@forEach
            into.add(
                ConfigFileInfo(
                    name = config.name,
                    path = file.absolutePath,
                    lastModified = file.lastModified(),
                    actionCount = config.actions.size
                )
            )
        }
    }

    /**
     * Load a configuration file
     */
    fun loadConfigurationFile(file: File): RpaConfiguration? {
        return try {
            if (file.exists()) {
                json.decodeFromString<RpaConfiguration>(file.readText())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load a configuration by path
     */
    fun loadConfiguration(path: String): RpaConfiguration? {
        return loadConfigurationFile(File(path))
    }

    /**
     * Add to recent configurations
     */
    fun addToRecent(path: String) {
        updateSettings { settings ->
            val recent = settings.recentConfigurations.toMutableList()
            recent.remove(path) // Remove if exists
            recent.add(0, path) // Add to front
            if (recent.size > 10) {
                recent.subList(10, recent.size).clear() // Keep only 10 most recent
            }
            settings.copy(
                recentConfigurations = recent,
                lastConfigPath = path
            )
        }
    }

    /**
     * Get recent configurations
     */
    fun getRecentConfigurations(): List<ConfigFileInfo> {
        val settings = loadSettings()
        return settings.recentConfigurations.mapNotNull { path ->
            val file = File(path)
            if (file.exists()) {
                try {
                    val config = loadConfigurationFile(file)
                    if (config != null) {
                        ConfigFileInfo(
                            name = config.name,
                            path = path,
                            lastModified = file.lastModified(),
                            actionCount = config.actions.size
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    /**
     * Format timestamp for display
     */
    fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} min ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            diff < 604800_000 -> "${diff / 86400_000} days ago"
            else -> {
                val date = java.util.Date(timestamp)
                java.text.SimpleDateFormat("MMM d, yyyy").format(date)
            }
        }
    }

    private companion object {
        /** The engine's own settings live beside the configurations; it is not one of them. */
        const val SETTINGS_FILE_NAME = "settings.json"
    }
}
