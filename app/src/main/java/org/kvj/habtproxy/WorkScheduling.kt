package org.kvj.habtproxy

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val FOREGROUND_SCAN_WORK = "foregroundScan"

fun scheduleForegroundScan(context: Context, runImmediately: Boolean, delaySeconds: Long = 0) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    val enabled = preferences.getBoolean(context.getString(R.string.settings_enabled), false)
    if (!enabled) {
        WorkManager.getInstance(context).cancelUniqueWork(FOREGROUND_SCAN_WORK)
        return
    }

    val workRequest = OneTimeWorkRequestBuilder<ScanWorker>().apply {
        if (!runImmediately && delaySeconds > 0) {
            setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
    }.build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        FOREGROUND_SCAN_WORK,
        ExistingWorkPolicy.REPLACE,
        workRequest
    )
}
