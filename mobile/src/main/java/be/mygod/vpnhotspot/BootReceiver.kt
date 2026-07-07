package be.mygod.vpnhotspot

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TetheringManager
import android.os.Build
import android.os.Parcelable
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.util.toByteArray
import be.mygod.vpnhotspot.util.toParcelable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val KEY = "service.autoStart"
        const val KEY_AUTO_START_WIFI = "service.autoStart.wifi"
        const val KEY_AUTO_START_USB = "service.autoStart.usb"
        const val KEY_AUTO_START_BLUETOOTH = "service.autoStart.bluetooth"
        const val KEY_AUTO_START_ETHERNET = "service.autoStart.ethernet"

        // 开机自动启动的系统共享类型映射。Ethernet 类型自 API 30 起可用。
        private val autoStartTetherTypes: List<Pair<String, Int>> = buildList {
            add(KEY_AUTO_START_WIFI to TetheringManager.TETHERING_WIFI)
            add(KEY_AUTO_START_USB to TetheringManagerCompat.TETHERING_USB)
            add(KEY_AUTO_START_BLUETOOTH to TetheringManagerCompat.TETHERING_BLUETOOTH)
            if (Build.VERSION.SDK_INT >= 30) add(KEY_AUTO_START_ETHERNET to TetheringManagerCompat.TETHERING_ETHERNET)
        }

        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        private var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(componentName,
                    if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        private suspend fun isUserEnabled() = withContext(Dispatchers.IO) { app.pref.getBoolean(KEY, false) }
        private suspend fun enabledAutoStartTetherTypes() = withContext(Dispatchers.IO) {
            autoStartTetherTypes.filter { (key, _) -> app.pref.getBoolean(key, false) }.map { it.second }
        }
        private suspend fun recomputeEnabled(hasStartables: Boolean) {
            enabled = (hasStartables && isUserEnabled()) || enabledAutoStartTetherTypes().isNotEmpty()
        }
        suspend fun onUserSettingUpdated(shouldStart: Boolean) = configMutex.withLock {
            recomputeEnabled(shouldStart && loadConfigLocked()?.startables?.isEmpty() == false)
        }
        private suspend fun onConfigUpdated(isNotEmpty: Boolean) = recomputeEnabled(isNotEmpty)
        suspend fun onAutoStartTetherTypeUpdated() = configMutex.withLock {
            recomputeEnabled(loadConfigLocked()?.startables?.isEmpty() == false)
        }

        private const val FILENAME = "bootconfig"
        private val configFile by lazy { File(app.deviceStorage.noBackupFilesDir, FILENAME) }
        private val configMutex = Mutex()
        private suspend fun migrateIfNecessaryLocked() = withContext(Dispatchers.IO) {
            val oldFile = File(app.noBackupFilesDir, FILENAME)
            if (oldFile.canRead()) try {
                if (!configFile.exists()) oldFile.copyTo(configFile)
                if (!oldFile.delete()) oldFile.deleteOnExit()
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
        private suspend fun loadConfigLocked(): Config? {
            migrateIfNecessaryLocked()
            return withContext(Dispatchers.IO) {
                try {
                    DataInputStream(configFile.inputStream()).use {
                        it.readBytes().toParcelable(Config::class.java.classLoader)
                    }
                } catch (_: FileNotFoundException) {
                    null
                } catch (e: Exception) {
                    Timber.w(e, "Boot config corrupted")
                    null
                }
            }
        }
        private suspend fun updateConfig(work: Config.() -> Boolean) = configMutex.withLock {
            val config = loadConfigLocked() ?: Config()
            if (config.work()) withContext(Dispatchers.IO) {
                DataOutputStream(configFile.outputStream()).use { it.write(config.toByteArray()) }
            }
            config
        }

        suspend fun add(key: String, value: Startable) = try {
            updateConfig { startables.put(key, value).let { true } }
            onConfigUpdated(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e)
        }
        suspend fun delete(key: String) = try {
            onConfigUpdated(updateConfig { startables.remove(key) != null }.startables.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e)
        }
        suspend inline fun <reified T> add(value: Startable) = add(T::class.java.name, value)
        suspend inline fun <reified T> delete() = delete(T::class.java.name)

        private var started = false
        private suspend fun startIfNecessary(startTetherTypes: Boolean = false) = configMutex.withLock {
            if (started) return@withLock
            val config = loadConfigLocked()
            val userEnabled = isUserEnabled()
            val tetherTypesToStart = if (startTetherTypes) enabledAutoStartTetherTypes() else emptyList()
            enabled = (config?.startables?.isEmpty() == false && userEnabled) || tetherTypesToStart.isNotEmpty()
            if (userEnabled) for (startable in config?.startables?.values ?: emptyList()) startable.start(app)
            for (type in tetherTypesToStart) try {
                TetheringManagerCompat.startTethering(type, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e)
            }
            started = true
        }
        suspend fun startIfEnabled() {
            if (!started && isUserEnabled()) startIfNecessary()
        }
    }

    interface Startable : Parcelable {
        fun start(context: Context)
    }

    @Parcelize
    private data class Config(var startables: MutableMap<String, Startable> = mutableMapOf()) : Parcelable

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                GlobalScope.launch(Dispatchers.Main.immediate) {
                    try {
                        startIfNecessary(startTetherTypes = true)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
