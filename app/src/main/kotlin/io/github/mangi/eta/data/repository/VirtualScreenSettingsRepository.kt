package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.VirtualScreenSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * 虚拟屏设置。
 *
 * 和出站改写一样：虚拟屏控制器是同步跑的，不能在里面 await DataStore，
 * 所以维护一份内存快照，[current] 给控制器同步读，[publish] 由启动时的 Flow 收集刷新。
 */
object VirtualScreenSettingsRepository {
    private val snapshot = MutableStateFlow(VirtualScreenSettings())

    val stateFlow: StateFlow<VirtualScreenSettings> = snapshot

    fun flow(): Flow<VirtualScreenSettings> =
        SettingsDataStore.settingsFlow().map { it.virtualScreen.normalized() }

    suspend fun settings(): VirtualScreenSettings = SettingsDataStore.settings().virtualScreen.normalized()

    suspend fun update(transform: (VirtualScreenSettings) -> VirtualScreenSettings) {
        SettingsDataStore.updateSettings { current ->
            current.copy(virtualScreen = transform(current.virtualScreen).normalized())
        }
    }

    fun current(): VirtualScreenSettings = snapshot.value

    fun publish(settings: VirtualScreenSettings) {
        snapshot.value = settings.normalized()
    }
}
