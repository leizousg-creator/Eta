package io.github.mangi.eta.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.SpeechSettings
import kotlinx.serialization.json.Json
import io.github.mangi.eta.data.model.Settings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal object SettingsDataStore {
    private val SPEECH_SETTINGS = stringPreferencesKey("speech_settings_v1")
    private val speechJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun speechSettingsFlow(): Flow<SpeechSettings> {
        ensureInitialized()
        return dataStore.data.map { prefs ->
            prefs[SPEECH_SETTINGS]?.let { speechJson.decodeFromString<SpeechSettings>(it) }
                ?: SpeechSettings()
        }
    }

    suspend fun speechSettings() = speechSettingsFlow().first()

    suspend fun setSpeechSettings(settings: SpeechSettings) {
        ensureInitialized()
        dataStore.edit { it[SPEECH_SETTINGS] = speechJson.encodeToString(settings) }
    }

    private const val STORE_NAME = "eta_settings"

    private val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
    private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
    private val OFFICIAL_MODEL_CATALOG_REVISION = intPreferencesKey("official_model_catalog_revision")
    private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
    private val AUTO_MEMORY_ENABLED = booleanPreferencesKey("auto_memory_enabled")
    private val MEMORY_AUTO_COMPACT_ENABLED = booleanPreferencesKey("memory_auto_compact_enabled")
    private val AUTO_SKILLS_ENABLED = booleanPreferencesKey("auto_skills_enabled")
    private val VIRTUAL_SCREEN_ENABLED = booleanPreferencesKey("virtual_screen_enabled")
    private val VIRTUAL_SCREEN_OFF_ENABLED = booleanPreferencesKey("virtual_screen_off_enabled")
    private val VIRTUAL_SCREEN_FALLBACK_ENABLED = booleanPreferencesKey("virtual_screen_fallback_enabled")
    private val VIRTUAL_SCREEN_AUTO_RESTART_APPS = booleanPreferencesKey("virtual_screen_auto_restart_apps")
    private val VIRTUAL_SCREEN_IDLE_TIMEOUT = intPreferencesKey("virtual_screen_idle_timeout_minutes")
    private val VIRTUAL_SCREEN_FRAME_RATE = intPreferencesKey("virtual_screen_frame_rate")
    private val VIRTUAL_SCREEN_FAVORITE_APPS = stringSetPreferencesKey("virtual_screen_favorite_apps")
    private val VIRTUAL_SCREEN_FLOAT_WINDOW = booleanPreferencesKey("virtual_screen_float_window")
    private val VIRTUAL_SCREEN_FLOAT_EDGE_COLLAPSE = booleanPreferencesKey("virtual_screen_float_edge_collapse")
    private val VIRTUAL_SCREEN_FLOAT_WIDTH = intPreferencesKey("virtual_screen_float_width_dp")
    private val VIRTUAL_SCREEN_FLOAT_HEIGHT = intPreferencesKey("virtual_screen_float_height_dp")
    private val VIRTUAL_SCREEN_FLOAT_ALPHA = floatPreferencesKey("virtual_screen_float_alpha")
    private val VIRTUAL_SCREEN_FLOAT_POS_X = intPreferencesKey("virtual_screen_float_pos_x")
    private val VIRTUAL_SCREEN_FLOAT_POS_Y = intPreferencesKey("virtual_screen_float_pos_y")
    private val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    private val OVERLAY_CAPSULE_PINNED = booleanPreferencesKey("overlay_capsule_pinned")
    private val OVERLAY_CAPSULE_POS_X = intPreferencesKey("overlay_capsule_pos_x")
    private val OVERLAY_CAPSULE_POS_Y = intPreferencesKey("overlay_capsule_pos_y")
    private val RECENT_OPENED_IDS = stringPreferencesKey("recent_opened_conversation_ids")
    private val AUTO_BACKUP_INTERVAL = intPreferencesKey("auto_backup_interval_hours")
    /** 周期（分钟）：与老的小时键并存，分钟 > 0 时优先。 */
    private val AUTO_BACKUP_INTERVAL_MINUTES = intPreferencesKey("auto_backup_interval_minutes")
    private val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep_count")
    private val AUTO_BACKUP_LAST_AT = longPreferencesKey("auto_backup_last_at")
    private val AUTO_BACKUP_FINGERPRINT = stringPreferencesKey("auto_backup_fingerprint")
    private val AUTO_BACKUP_LAST_FILE = stringPreferencesKey("auto_backup_last_file")
    private val AUTO_BACKUP_DIRECTORY = stringPreferencesKey("auto_backup_directory")
    private val DIAG_PATROL_ENABLED = booleanPreferencesKey("diag_patrol_enabled")
    private val DIAG_PATROL_INTERVAL = intPreferencesKey("diag_patrol_interval_minutes")
    private val DIAG_PATROL_KEEP = intPreferencesKey("diag_patrol_keep_count")
    private val DIAG_PATROL_ONLY_ABNORMAL = booleanPreferencesKey("diag_patrol_only_abnormal")
    private val DIAG_PATROL_LAST_AT = longPreferencesKey("diag_patrol_last_at")
    private val DIAG_PATROL_LAST_FILE = stringPreferencesKey("diag_patrol_last_file")
    private val DIAG_PATROL_LAST_LEVEL = stringPreferencesKey("diag_patrol_last_level")
    private val DIAG_PATROL_LAST_SUMMARY = stringPreferencesKey("diag_patrol_last_summary")
    private val LINUX_DISTRIBUTION = stringPreferencesKey("linux_distribution")
    private val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
    private val APPEARANCE_MONET_ENABLED = booleanPreferencesKey("appearance_monet_enabled")
    private val APPEARANCE_PALETTE_STYLE = stringPreferencesKey("appearance_palette_style")
    private val APPEARANCE_ACCENT_COLOR = stringPreferencesKey("appearance_accent_color")
    private val APPEARANCE_PURE_BLACK_ENABLED = booleanPreferencesKey("appearance_pure_black_enabled")
    private val APPEARANCE_BLUR_ENABLED = booleanPreferencesKey("appearance_blur_enabled")
    private val APPEARANCE_TOP_BAR_BLUR_STYLE = stringPreferencesKey("appearance_top_bar_blur_style")
    private val APPEARANCE_SWIPE_DISMISS_ENABLED =
        booleanPreferencesKey("appearance_swipe_dismiss_enabled")
    private val APPEARANCE_PREDICTIVE_BACK_ENABLED =
        booleanPreferencesKey("appearance_predictive_back_enabled")
    private val APPEARANCE_INTERFACE_SCALE = floatPreferencesKey("appearance_interface_scale")
    private const val SELECTED_MODEL_BY_PROVIDER_PREFIX = "selected_model_id_by_provider."

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>

    /** 自动备份目录的进程内缓存：供非挂起调用（目录解析、界面展示）读取。 */
    @Volatile
    private var autoBackupDirectoryCache: String = ""

    fun cachedAutoBackupDirectory(): String = autoBackupDirectoryCache

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.applicationContext.dataStore
        }
    }

    fun settingsFlow(): Flow<Settings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { preferences ->
                preferences.toSettings().also { autoBackupDirectoryCache = it.autoBackupDirectory }
            }
    }

    suspend fun settings(): Settings = settingsFlow().first()

    /** 虚拟屏应用菜单的收藏（按包名保存，跨会话保留）。 */
    suspend fun favoriteApps(): Set<String> {
        ensureInitialized()
        return dataStore.data.first()[VIRTUAL_SCREEN_FAVORITE_APPS] ?: emptySet()
    }

    fun favoriteAppsFlow(): Flow<Set<String>> {
        ensureInitialized()
        return dataStore.data.map { it[VIRTUAL_SCREEN_FAVORITE_APPS] ?: emptySet() }
    }

    suspend fun setFavoriteApps(packages: Set<String>) {
        ensureInitialized()
        dataStore.edit { prefs ->
            if (packages.isEmpty()) prefs.remove(VIRTUAL_SCREEN_FAVORITE_APPS)
            else prefs[VIRTUAL_SCREEN_FAVORITE_APPS] = packages
        }
    }

    suspend fun officialModelCatalogRevision(): Int {
        ensureInitialized()
        return dataStore.data.first()[OFFICIAL_MODEL_CATALOG_REVISION] ?: 0
    }

    suspend fun setOfficialModelCatalogRevision(revision: Int) {
        ensureInitialized()
        dataStore.edit { preferences ->
            preferences[OFFICIAL_MODEL_CATALOG_REVISION] = revision
        }
    }

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val updated = transform(current)
            prefs.putOrRemove(SELECTED_PROVIDER_ID, updated.selectedProviderId)
            prefs.putOrRemove(SELECTED_MODEL_ID, updated.selectedModelId)
            prefs[MEMORY_ENABLED] = updated.memoryEnabled
            prefs[AUTO_MEMORY_ENABLED] = updated.autoMemoryEnabled
            prefs[MEMORY_AUTO_COMPACT_ENABLED] = updated.memoryAutoCompactEnabled
            prefs[AUTO_SKILLS_ENABLED] = updated.autoSkillsEnabled
            prefs[VIRTUAL_SCREEN_ENABLED] = updated.virtualScreenEnabled
            prefs[VIRTUAL_SCREEN_OFF_ENABLED] = updated.virtualScreenOffEnabled
            prefs[VIRTUAL_SCREEN_FALLBACK_ENABLED] = updated.virtualScreenFallbackEnabled
            prefs[VIRTUAL_SCREEN_AUTO_RESTART_APPS] = updated.virtualScreenAutoRestartApps
            prefs[VIRTUAL_SCREEN_IDLE_TIMEOUT] = io.github.mangi.eta.data.model.VirtualScreenIdleTimeout.normalize(updated.virtualScreenIdleTimeoutMinutes)
            prefs[VIRTUAL_SCREEN_FRAME_RATE] = io.github.mangi.eta.data.model.VirtualScreenFrameRate.normalize(updated.virtualScreenFrameRate)
            prefs[VIRTUAL_SCREEN_FLOAT_WINDOW] = updated.virtualScreenFloatWindowEnabled
            prefs[VIRTUAL_SCREEN_FLOAT_EDGE_COLLAPSE] = updated.virtualScreenFloatEdgeCollapseEnabled
            prefs[VIRTUAL_SCREEN_FLOAT_WIDTH] = updated.virtualScreenFloatWindowWidthDp
            prefs[VIRTUAL_SCREEN_FLOAT_HEIGHT] = updated.virtualScreenFloatWindowHeightDp
            prefs[VIRTUAL_SCREEN_FLOAT_ALPHA] = updated.virtualScreenFloatWindowAlpha
            prefs[VIRTUAL_SCREEN_FLOAT_POS_X] = updated.virtualScreenFloatWindowPosX
            prefs[VIRTUAL_SCREEN_FLOAT_POS_Y] = updated.virtualScreenFloatWindowPosY
            prefs[AUTO_BACKUP_ENABLED] = updated.autoBackupEnabled
            prefs[OVERLAY_CAPSULE_PINNED] = updated.overlayCapsulePinned
            prefs[OVERLAY_CAPSULE_POS_X] = updated.overlayCapsulePosX
            prefs[OVERLAY_CAPSULE_POS_Y] = updated.overlayCapsulePosY
            prefs[RECENT_OPENED_IDS] = updated.recentOpenedConversationIds
            prefs[AUTO_BACKUP_INTERVAL] = updated.autoBackupIntervalHours
                .coerceIn(1, io.github.mangi.eta.data.model.AutoBackupInterval.MAX_MINUTES / 60)
            prefs[AUTO_BACKUP_INTERVAL_MINUTES] =
                io.github.mangi.eta.data.model.AutoBackupInterval.normalize(updated.autoBackupIntervalMinutes)
            prefs[AUTO_BACKUP_KEEP] = io.github.mangi.eta.data.model.AutoBackupKeepCount.normalize(updated.autoBackupKeepCount)
            prefs[AUTO_BACKUP_LAST_AT] = updated.autoBackupLastAt
            prefs.putOrRemove(AUTO_BACKUP_FINGERPRINT, updated.autoBackupFingerprint)
            prefs.putOrRemove(AUTO_BACKUP_LAST_FILE, updated.autoBackupLastFileName)
            prefs.putOrRemove(AUTO_BACKUP_DIRECTORY, updated.autoBackupDirectory)
            prefs[DIAG_PATROL_ENABLED] = updated.diagPatrolEnabled
            prefs[DIAG_PATROL_INTERVAL] = io.github.mangi.eta.core.DiagnosticsPatrolInterval
                .normalize(updated.diagPatrolIntervalMinutes)
            prefs[DIAG_PATROL_KEEP] = updated.diagPatrolKeepCount.coerceIn(1, 60)
            prefs[DIAG_PATROL_ONLY_ABNORMAL] = updated.diagPatrolNotifyOnlyAbnormal
            prefs[DIAG_PATROL_LAST_AT] = updated.diagPatrolLastAt
            prefs.putOrRemove(DIAG_PATROL_LAST_FILE, updated.diagPatrolLastFile)
            prefs.putOrRemove(DIAG_PATROL_LAST_LEVEL, updated.diagPatrolLastLevel)
            prefs.putOrRemove(DIAG_PATROL_LAST_SUMMARY, updated.diagPatrolLastSummary)
            prefs.remove(stringPreferencesKey("vivo_atomic_scene"))
            prefs.putAppearance(updated.appearance.normalized())
        }
    }

    suspend fun autoBackupLastAt(): Long = settings().autoBackupLastAt

    /** 备份状态单独写，走独立 edit，避免与设置页的表单改动互相覆盖。 */
    suspend fun setAutoBackupState(lastAt: Long, fingerprint: String, fileName: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs[AUTO_BACKUP_LAST_AT] = lastAt
            if (fingerprint.isBlank()) prefs.remove(AUTO_BACKUP_FINGERPRINT)
            else prefs[AUTO_BACKUP_FINGERPRINT] = fingerprint
            if (fileName.isBlank()) prefs.remove(AUTO_BACKUP_LAST_FILE)
            else prefs[AUTO_BACKUP_LAST_FILE] = fileName
        }
    }

    fun selectedProviderIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedProviderId }

    fun selectedModelIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedModelId }

    suspend fun selectedModelIdForProvider(providerId: String): String? {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> prefs[selectedModelByProviderKey(providerId)] }
            .first()
    }

    fun memoryAutoCompactEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryAutoCompactEnabled }

    fun memoryEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryEnabled }

    fun linuxDistributionFlow(): Flow<String?> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { preferences -> preferences[LINUX_DISTRIBUTION] }
    }

    fun linuxBackendFlow(distribution: String): Flow<String?> {
        ensureInitialized()
        return dataStore.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { it[stringPreferencesKey("linux_backend.$distribution")] }
    }

    suspend fun setLinuxBackend(distribution: String, backend: String?) {
        ensureInitialized()
        dataStore.edit { preferences ->
            val key = stringPreferencesKey("linux_backend.$distribution")
            if (backend == null) preferences.remove(key) else preferences[key] = backend
        }
    }

    fun appearanceSettingsFlow(): Flow<AppearanceSettings> =
        settingsFlow().map { it.appearance }

    suspend fun setSelectedProviderId(id: String?) {
        updateSettings { it.copy(selectedProviderId = id) }
    }

    suspend fun setSelectedModelId(id: String?) {
        updateSettings { it.copy(selectedModelId = id) }
    }

    suspend fun setSelection(providerId: String?, modelId: String?) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val previousProviderId = prefs[SELECTED_PROVIDER_ID]
            val previousModelId = prefs[SELECTED_MODEL_ID]
            if (previousProviderId != null && previousModelId != null) {
                prefs[selectedModelByProviderKey(previousProviderId)] = previousModelId
            }

            prefs.putOrRemove(SELECTED_PROVIDER_ID, providerId)
            prefs.putOrRemove(SELECTED_MODEL_ID, modelId)
            if (providerId != null && modelId != null) {
                prefs[selectedModelByProviderKey(providerId)] = modelId
            }
        }
    }

    suspend fun clearSelectedModelIdForProvider(providerId: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.remove(selectedModelByProviderKey(providerId))
        }
    }

    suspend fun setMemoryAutoCompactEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryAutoCompactEnabled = enabled) }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryEnabled = enabled) }
    }

    suspend fun setLinuxDistribution(value: String?) {
        ensureInitialized()
        dataStore.edit { preferences -> preferences.putOrRemove(LINUX_DISTRIBUTION, value) }
    }

    suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        updateSettings { it.copy(appearance = settings.normalized()) }
    }

    suspend fun updateAppearanceSettings(transform: (AppearanceSettings) -> AppearanceSettings) {
        updateSettings { settings ->
            settings.copy(appearance = transform(settings.appearance).normalized())
        }
    }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) {
            "SettingsDataStore.init(context) must be called in Application.onCreate()"
        }
    }

    private fun selectedModelByProviderKey(providerId: String): Preferences.Key<String> =
        stringPreferencesKey("$SELECTED_MODEL_BY_PROVIDER_PREFIX$providerId")

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        selectedProviderId = this[SELECTED_PROVIDER_ID],
        selectedModelId = this[SELECTED_MODEL_ID],
        memoryEnabled = this[MEMORY_ENABLED] ?: true,
        autoMemoryEnabled = this[AUTO_MEMORY_ENABLED] ?: true,
        memoryAutoCompactEnabled = this[MEMORY_AUTO_COMPACT_ENABLED] ?: true,
        autoSkillsEnabled = this[AUTO_SKILLS_ENABLED] ?: true,
        virtualScreenEnabled = this[VIRTUAL_SCREEN_ENABLED] ?: false,
        virtualScreenOffEnabled = this[VIRTUAL_SCREEN_OFF_ENABLED] ?: false,
        virtualScreenFallbackEnabled = this[VIRTUAL_SCREEN_FALLBACK_ENABLED] ?: false,
        virtualScreenAutoRestartApps = this[VIRTUAL_SCREEN_AUTO_RESTART_APPS] ?: false,
        virtualScreenIdleTimeoutMinutes = io.github.mangi.eta.data.model.VirtualScreenIdleTimeout.normalize(this[VIRTUAL_SCREEN_IDLE_TIMEOUT] ?: 20),
        virtualScreenFrameRate = io.github.mangi.eta.data.model.VirtualScreenFrameRate.normalize(this[VIRTUAL_SCREEN_FRAME_RATE] ?: 60),
        virtualScreenFloatWindowEnabled = this[VIRTUAL_SCREEN_FLOAT_WINDOW] ?: false,
        virtualScreenFloatEdgeCollapseEnabled = this[VIRTUAL_SCREEN_FLOAT_EDGE_COLLAPSE] ?: true,
        virtualScreenFloatWindowWidthDp = this[VIRTUAL_SCREEN_FLOAT_WIDTH] ?: 0,
        virtualScreenFloatWindowHeightDp = this[VIRTUAL_SCREEN_FLOAT_HEIGHT] ?: 0,
        virtualScreenFloatWindowAlpha = this[VIRTUAL_SCREEN_FLOAT_ALPHA] ?: 0.85f,
        virtualScreenFloatWindowPosX = this[VIRTUAL_SCREEN_FLOAT_POS_X] ?: 16,
        virtualScreenFloatWindowPosY = this[VIRTUAL_SCREEN_FLOAT_POS_Y] ?: 120,
        autoBackupEnabled = this[AUTO_BACKUP_ENABLED] ?: false,
        overlayCapsulePinned = this[OVERLAY_CAPSULE_PINNED] ?: false,
        overlayCapsulePosX = this[OVERLAY_CAPSULE_POS_X] ?: -1,
        overlayCapsulePosY = this[OVERLAY_CAPSULE_POS_Y] ?: -1,
        recentOpenedConversationIds = this[RECENT_OPENED_IDS] ?: "",
        autoBackupIntervalMinutes = io.github.mangi.eta.data.model.AutoBackupInterval.effectiveMinutes(
            this[AUTO_BACKUP_INTERVAL_MINUTES] ?: 0,
            this[AUTO_BACKUP_INTERVAL] ?: 24,
        ),
        autoBackupIntervalHours = io.github.mangi.eta.data.model.AutoBackupInterval.fromLegacyHours(
            this[AUTO_BACKUP_INTERVAL] ?: 24,
        ) / 60,
        autoBackupKeepCount = io.github.mangi.eta.data.model.AutoBackupKeepCount.normalize(this[AUTO_BACKUP_KEEP] ?: 5),
        autoBackupLastAt = this[AUTO_BACKUP_LAST_AT] ?: 0L,
        autoBackupFingerprint = this[AUTO_BACKUP_FINGERPRINT].orEmpty(),
        autoBackupLastFileName = this[AUTO_BACKUP_LAST_FILE].orEmpty(),
        autoBackupDirectory = this[AUTO_BACKUP_DIRECTORY].orEmpty(),
        diagPatrolEnabled = this[DIAG_PATROL_ENABLED] ?: false,
        diagPatrolIntervalMinutes = io.github.mangi.eta.core.DiagnosticsPatrolInterval.normalize(
            this[DIAG_PATROL_INTERVAL] ?: io.github.mangi.eta.core.DiagnosticsPatrolInterval.DEFAULT_MINUTES,
        ),
        diagPatrolKeepCount = (this[DIAG_PATROL_KEEP] ?: 30).coerceIn(1, 60),
        diagPatrolNotifyOnlyAbnormal = this[DIAG_PATROL_ONLY_ABNORMAL] ?: true,
        diagPatrolLastAt = this[DIAG_PATROL_LAST_AT] ?: 0L,
        diagPatrolLastFile = this[DIAG_PATROL_LAST_FILE].orEmpty(),
        diagPatrolLastLevel = this[DIAG_PATROL_LAST_LEVEL].orEmpty(),
        diagPatrolLastSummary = this[DIAG_PATROL_LAST_SUMMARY].orEmpty(),
        appearance = AppearanceSettings(
            themeMode = AppearanceThemeMode.fromPersistedValue(this[APPEARANCE_THEME_MODE]),
            monetEnabled = this[APPEARANCE_MONET_ENABLED] ?: false,
            paletteStyle = AppearancePaletteStyle.fromPersistedValue(this[APPEARANCE_PALETTE_STYLE]),
            accentColor = AppearanceAccentColor.fromPersistedValue(this[APPEARANCE_ACCENT_COLOR]),
            pureBlackEnabled = this[APPEARANCE_PURE_BLACK_ENABLED] ?: false,
            blurEnabled = this[APPEARANCE_BLUR_ENABLED] ?: true,
            topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(
                this[APPEARANCE_TOP_BAR_BLUR_STYLE],
            ),
            swipeDismissEnabled = this[APPEARANCE_SWIPE_DISMISS_ENABLED] ?: true,
            predictiveBackEnabled = this[APPEARANCE_PREDICTIVE_BACK_ENABLED] ?: true,
            interfaceScale = this[APPEARANCE_INTERFACE_SCALE] ?: 1f,
        ).normalized(),
    )

    private fun MutablePreferences.putAppearance(settings: AppearanceSettings) {
        this[APPEARANCE_THEME_MODE] = settings.themeMode.persistedValue
        this[APPEARANCE_MONET_ENABLED] = settings.monetEnabled
        this[APPEARANCE_PALETTE_STYLE] = settings.paletteStyle.persistedValue
        this[APPEARANCE_ACCENT_COLOR] = settings.accentColor.persistedValue
        this[APPEARANCE_PURE_BLACK_ENABLED] = settings.pureBlackEnabled
        this[APPEARANCE_BLUR_ENABLED] = settings.blurEnabled
        this[APPEARANCE_TOP_BAR_BLUR_STYLE] = settings.topBarBlurStyle.persistedValue
        this[APPEARANCE_SWIPE_DISMISS_ENABLED] = settings.swipeDismissEnabled
        this[APPEARANCE_PREDICTIVE_BACK_ENABLED] = settings.predictiveBackEnabled
        this[APPEARANCE_INTERFACE_SCALE] = settings.interfaceScale
    }
}
