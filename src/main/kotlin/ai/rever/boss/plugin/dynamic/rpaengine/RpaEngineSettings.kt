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
        get() = File(configDir, "settings.json")

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
     * Find all available RPA configuration files
     */
    fun findAvailableConfigurations(): List<ConfigFileInfo> {
        val configs = mutableListOf<ConfigFileInfo>()

        // Check RPA Recorder saved configurations
        if (rpaRecorderConfigDir.exists() && rpaRecorderConfigDir.isDirectory) {
            rpaRecorderConfigDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
                try {
                    val config = loadConfigurationFile(file)
                    if (config != null) {
                        configs.add(ConfigFileInfo(
                            name = config.name,
                            path = file.absolutePath,
                            lastModified = file.lastModified(),
                            actionCount = config.actions.size
                        ))
                    }
                } catch (e: Exception) {
                    // Skip invalid files
                }
            }
        }

        // Check Downloads folder for RPA configs
        if (downloadsDir.exists() && downloadsDir.isDirectory) {
            downloadsDir.listFiles { file ->
                file.extension == "json" && (file.name.startsWith("rpa_") || file.name.contains("rpa"))
            }?.forEach { file ->
                try {
                    val config = loadConfigurationFile(file)
                    if (config != null && !configs.any { it.path == file.absolutePath }) {
                        configs.add(ConfigFileInfo(
                            name = config.name,
                            path = file.absolutePath,
                            lastModified = file.lastModified(),
                            actionCount = config.actions.size
                        ))
                    }
                } catch (e: Exception) {
                    // Skip invalid files
                }
            }
        }

        // Sort by last modified (newest first)
        return configs.sortedByDescending { it.lastModified }
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
}
