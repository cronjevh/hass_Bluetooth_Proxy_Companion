package org.kvj.habtproxy

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDefaultSettings()
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }

    private fun scheduleForegroundScan() {
        val workRequest = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "foregroundScan",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    override fun onResume() {
        super.onResume()
        requestRelevantRuntimePermissions()
        scheduleForegroundScan()
    }

    private fun applyDefaultSettings() {
        PreferenceManager.getDefaultSharedPreferences(this).edit().apply {
            putBoolean(getString(R.string.settings_enabled), BuildConfig.DEFAULT_PROXY_ENABLED)
            putBoolean(getString(R.string.settings_optimize_background), BuildConfig.DEFAULT_OPTIMIZE_BACKGROUND)
            putString(getString(R.string.settings_scan_duration), BuildConfig.DEFAULT_SCAN_DURATION_SECONDS.toString())
            putString(getString(R.string.settings_scan_interval), BuildConfig.DEFAULT_SCAN_INTERVAL_SECONDS.toString())
            putString(getString(R.string.settings_upload_inteval), BuildConfig.DEFAULT_UPLOAD_INTERVAL_SECONDS.toString())
            if (BuildConfig.DEFAULT_WEBHOOK.isNotBlank()) {
                putString(getString(R.string.settings_webhook), BuildConfig.DEFAULT_WEBHOOK)
            }
            apply()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private fun updateProxySection(enabled: Boolean) {
            findPreference<Preference>(getString(R.string.settings_optimize_background))?.isEnabled = enabled
            findPreference<Preference>(getString(R.string.settings_webhook))?.isEnabled = enabled
            findPreference<PreferenceCategory>("cat_intervals")?.isEnabled = enabled
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<SwitchPreferenceCompat>(getString(R.string.settings_enabled))?.let {
                it.setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    updateProxySection(enabled)
                    true
                }
                updateProxySection(it.isChecked)
            }
        }
    }
}
