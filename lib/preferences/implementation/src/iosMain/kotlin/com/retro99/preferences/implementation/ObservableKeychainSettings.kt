package com.retro99.preferences.implementation

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SettingsListener

/**
 * Wraps a non-observable [Settings] store and adds in-process change notification.
 *
 * [com.russhwolf.settings.KeychainSettings] only implements [Settings] (the keychain has no
 * change notifications), but [MultiplatformPreferences] requires [ObservableSettings] for its
 * `toFlowSettings()` flows. This decorator keeps keychain storage semantics and notifies
 * listeners after writes made through this instance. Changes made outside the app are not
 * observed, which matches the underlying store's capabilities.
 */
class ObservableKeychainSettings(
    private val delegate: Settings,
) : Settings by delegate, ObservableSettings {

    private val listeners = mutableMapOf<String, MutableList<() -> Unit>>()

    private fun register(key: String, onChanged: () -> Unit): SettingsListener {
        listeners.getOrPut(key) { mutableListOf() }.add(onChanged)
        return object : SettingsListener {
            override fun deactivate() {
                listeners[key]?.remove(onChanged)
            }
        }
    }

    private fun notifyChanged(key: String) {
        listeners[key]?.toList()?.forEach { onChanged -> onChanged() }
    }

    override fun putInt(key: String, value: Int) {
        delegate.putInt(key, value)
        notifyChanged(key)
    }

    override fun putLong(key: String, value: Long) {
        delegate.putLong(key, value)
        notifyChanged(key)
    }

    override fun putString(key: String, value: String) {
        delegate.putString(key, value)
        notifyChanged(key)
    }

    override fun putFloat(key: String, value: Float) {
        delegate.putFloat(key, value)
        notifyChanged(key)
    }

    override fun putDouble(key: String, value: Double) {
        delegate.putDouble(key, value)
        notifyChanged(key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        delegate.putBoolean(key, value)
        notifyChanged(key)
    }

    override fun remove(key: String) {
        delegate.remove(key)
        notifyChanged(key)
    }

    override fun clear() {
        delegate.clear()
        listeners.keys.toList().forEach { notifyChanged(it) }
    }

    override fun addIntListener(key: String, defaultValue: Int, callback: (Int) -> Unit): SettingsListener =
        register(key) { callback(delegate.getInt(key, defaultValue)) }

    override fun addLongListener(key: String, defaultValue: Long, callback: (Long) -> Unit): SettingsListener =
        register(key) { callback(delegate.getLong(key, defaultValue)) }

    override fun addStringListener(key: String, defaultValue: String, callback: (String) -> Unit): SettingsListener =
        register(key) { callback(delegate.getString(key, defaultValue)) }

    override fun addFloatListener(key: String, defaultValue: Float, callback: (Float) -> Unit): SettingsListener =
        register(key) { callback(delegate.getFloat(key, defaultValue)) }

    override fun addDoubleListener(key: String, defaultValue: Double, callback: (Double) -> Unit): SettingsListener =
        register(key) { callback(delegate.getDouble(key, defaultValue)) }

    override fun addBooleanListener(key: String, defaultValue: Boolean, callback: (Boolean) -> Unit): SettingsListener =
        register(key) { callback(delegate.getBoolean(key, defaultValue)) }

    override fun addIntOrNullListener(key: String, callback: (Int?) -> Unit): SettingsListener =
        register(key) { callback(delegate.getIntOrNull(key)) }

    override fun addLongOrNullListener(key: String, callback: (Long?) -> Unit): SettingsListener =
        register(key) { callback(delegate.getLongOrNull(key)) }

    override fun addStringOrNullListener(key: String, callback: (String?) -> Unit): SettingsListener =
        register(key) { callback(delegate.getStringOrNull(key)) }

    override fun addFloatOrNullListener(key: String, callback: (Float?) -> Unit): SettingsListener =
        register(key) { callback(delegate.getFloatOrNull(key)) }

    override fun addDoubleOrNullListener(key: String, callback: (Double?) -> Unit): SettingsListener =
        register(key) { callback(delegate.getDoubleOrNull(key)) }

    override fun addBooleanOrNullListener(key: String, callback: (Boolean?) -> Unit): SettingsListener =
        register(key) { callback(delegate.getBooleanOrNull(key)) }
}
