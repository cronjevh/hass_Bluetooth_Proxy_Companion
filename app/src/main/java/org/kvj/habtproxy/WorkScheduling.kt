package org.kvj.habtproxy

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val FOREGROUND_SCAN_PERIODIC_WORK = "foregroundScan"
private const val FOREGROUND_SCAN_IMMEDIATE_WORK = "foregroundScanImmediate"

fun scheduleForegroundScan(context: Context, runImmediately: Boolean) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    val enabled = preferences.getBoolean(context.getString(R.string.settings_enabled), false)
    if (!enabled) {
        return
    }

    val workManager = WorkManager.getInstance(context)
    val periodicWorkRequest = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
        .build()
    workManager.enqueueUniquePeriodicWork(
        FOREGROUND_SCAN_PERIODIC_WORK,
        ExistingPeriodicWorkPolicy.UPDATE,
        periodicWorkRequest
    )

    if (runImmediately) {
        val immediateWorkRequest = OneTimeWorkRequestBuilder<ScanWorker>()
            .build()
        workManager.enqueueUniqueWork(
            FOREGROUND_SCAN_IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            immediateWorkRequest
        )
    }
}
