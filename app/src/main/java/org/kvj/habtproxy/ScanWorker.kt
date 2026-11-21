package org.kvj.habtproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.SparseArray
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.util.forEach
import androidx.core.util.keyIterator
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

private val TAG = "ScanWorker"
// Tracked devices, one entry per device. The address is the Bluetooth address
// as reported by the adapter; nameFragment is an optional advertised-name fallback
// for devices that rotate their address.
private data class TrackedDevice(
    val label: String,
    val address: String,
    val nameFragment: String? = null,
)

private val TRACKED_DEVICES = listOf(
    TrackedDevice("Cronje phone", "C4:16:8F:A0:0A:14"),
)

// Fixed BLE beacon used as a liveness canary for the scanner.
private const val CANARY_ADDR = "FC:E2:EC:43:74:43"
private const val CANARY_NAME_FRAGMENT = "CHR68437443"

private fun isTrackedDevice(address: String, name: String?): Boolean =
    TRACKED_DEVICES.any { device ->
        address.equals(device.address, ignoreCase = true) ||
            (device.nameFragment != null && !name.isNullOrBlank() &&
                name.contains(device.nameFragment, ignoreCase = true))
    }


private data class ScanExecutionMeta(
    val status: String,
    val statusReason: String?,
    val permissionsGranted: Boolean,
    val bluetoothEnabled: Boolean,
    val scanStartedTimestamp: Long,
    val scanFinishedTimestamp: Long,
    val totalDevicesSeen: Int,
    val matchedDeviceCount: Int,
    val bondedDeviceCount: Int,
    val canarySeen: Boolean,
    val scanMode: Int,
)

private data class SingleScanAttempt(
    val devicesFound: Int,
    val matchedDeviceCount: Int,
    val canarySeen: Boolean,
    val scanMode: Int,
)

private fun isCanaryDevice(address: String, name: String?): Boolean {
    if (address == CANARY_ADDR) {
        return true
    }
    return !name.isNullOrBlank() && name.contains(CANARY_NAME_FRAGMENT, ignoreCase = true)
}

private fun scanMetaToJson(scanMeta: ScanExecutionMeta): JSONObject {
    val json = JSONObject()
    json.put("status", scanMeta.status)
    if (scanMeta.statusReason != null) {
        json.put("status_reason", scanMeta.statusReason)
    }
    json.put("permissions_granted", scanMeta.permissionsGranted)
    json.put("bluetooth_enabled", scanMeta.bluetoothEnabled)
    json.put("scan_started_timestamp", scanMeta.scanStartedTimestamp)
    json.put("scan_finished_timestamp", scanMeta.scanFinishedTimestamp)
    json.put("total_devices_seen", scanMeta.totalDevicesSeen)
    json.put("matched_device_count", scanMeta.matchedDeviceCount)
    json.put("bonded_device_count", scanMeta.bondedDeviceCount)
    json.put("canary_seen", scanMeta.canarySeen)
    json.put("scan_mode", scanMeta.scanMode)
    return json
}

fun <K> Map<K, ByteArray>.contentMapEquals(other: Map<K, ByteArray>): Boolean {
    if (keys == other.keys) {
        if (keys.any { !get(it).contentEquals(other[it]) }) {
            return false;
        }
    } else {
        return false;
    }
    return true;
}

fun SparseArray<ByteArray>.contentMapEquals(other: SparseArray<ByteArray>): Boolean {
    val list = keyIterator().asSequence().toList()
    if (list == other.keyIterator().asSequence().toList()) {
        if (list.any { !get(it).contentEquals(other[it]) }) {
            return false;
        }
    } else {
        return false;
    }
    return true;
}

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val FG_NOTIFICATION_ID = 1
    private val scanCallbackDrainMillis = 750L
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(applicationContext, BluetoothManager::class.java) as BluetoothManager
        bluetoothManager.adapter
    }

    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
    }

    private val powerManager by lazy {
        getSystemService(applicationContext, PowerManager::class.java)!!
    }

    private val discoveryResults by lazy {
        DiscoveryResults.instance
    }

    @SuppressLint("MissingPermission")
    private fun bondedAddresses(): Set<String> =
        bluetoothAdapter.bondedDevices.orEmpty().map { it.address.uppercase() }.toSet() +
            TRACKED_DEVICES.map { it.address.uppercase() }

    private suspend fun uploadData(scanMeta: ScanExecutionMeta): Boolean {
        val webhook = preferences.getString(applicationContext, R.string.settings_webhook, 0)
        if (TextUtils.isEmpty(webhook)) {
            Log.w(TAG, "uploadData(): No webhook set")
            return false
        }
        val now = Date().time
        val uploadInterval = preferences.getInt(applicationContext, R.string.settings_upload_inteval, R.string.settings_upload_inteval_def)
        if (now - discoveryResults.lastUploadTimestamp < uploadInterval) {
            Log.d(TAG, "uploadData(): Skip upload due to the uploadInterval $uploadInterval s")
            return true
        }
        val arr = JSONArray()
        var uploadCount = 0
        Log.d(TAG, "uploadData(): Evaluating ${discoveryResults.discoveredRecords.size} cached devices, lastUploadTimestamp=${discoveryResults.lastUploadTimestamp}, now=$now")
        discoveryResults.discoveredRecords.forEach {
            uploadCount++
            val obj = JSONObject()
            obj.put("address", it.key)
            obj.put("name", it.value.name)
            obj.put("rssi", it.value.rssi)
            obj.put("tx_power", it.value.txPower)
            obj.put("timestamp", it.value.timestamp)
            it.value.record.serviceUuids?.let {
                val uuids = JSONArray()
                it.forEach { uuids.put(it.uuid.toString()) }
                obj.put("service_uuids", uuids)
            }
            it.value.record.serviceData?.let {
                val serviceData = JSONObject()
                it.forEach {
                    serviceData.put(it.key.uuid.toString(), Base64.encodeToString(it.value, Base64.DEFAULT))
                }
                obj.put("service_data", serviceData)
            }
            it.value.record.manufacturerSpecificData?.let {
                val mData = JSONObject()
                it.forEach { key, value ->
                    mData.put(key.toString(), Base64.encodeToString(value, Base64.DEFAULT))
                }
                obj.put("manufacturer_data", mData)
            }
            arr.put(obj)
        }
        val payload = JSONObject()
        payload.put("scan_meta", scanMetaToJson(scanMeta))
        payload.put("devices", arr)
        if (arr.length() == 0) {
            Log.d(TAG, "uploadData(): Uploading empty payload heartbeat (no cached devices)")
        } else {
            Log.d(TAG, "uploadData(): Uploading $uploadCount cached devices")
        }
        val result = withContext(Dispatchers.IO) {
            val conn = URL(webhook).openConnection() as HttpURLConnection
            try {
                conn.doOutput = true
                conn.setChunkedStreamingMode(0)
                conn.requestMethod = "POST"
                conn.addRequestProperty("content-type", "application/json")
                conn.outputStream.bufferedWriter().let {
                    it.write(payload.toString())
                    it.close()
                }
                conn.inputStream.bufferedReader().let {
                    val text = it.readText()
                    it.close()
                    text
                }
                discoveryResults.lastUploadTimestamp = now
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data via webhook", e)
                null
            } finally {
                conn.disconnect()
            }
        }
        Log.d(TAG, "Webhook send result: $result")
        return result == null
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSingleScan(
        bondedAddresses: Set<String>,
        scanMode: Int,
        scanDurationSeconds: Int,
    ): SingleScanAttempt? {
        val bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Log.w(TAG, "BluetoothLeScanner unavailable")
            return null
        }
        Log.d(TAG, "executeScan(): Bonded device filter=$bondedAddresses")
        discoveryResults.discoveredRecords.clear()
        val devicesFound = mutableSetOf<String>()
        val matchedDevices = mutableSetOf<String>()
        var canarySeen = false
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { result ->
                    result.scanRecord?.let { record ->
                        val address = result.device.address.uppercase()
                        val isBonded = bondedAddresses.contains(address)
                        val isCanary = isCanaryDevice(address, record.deviceName)
                        val isNamedTarget = isTrackedDevice(address, record.deviceName)
                        devicesFound.add(address)
                        if (!isBonded && !isCanary && !isNamedTarget) {
                            return
                        }
                        matchedDevices.add(address)
                        canarySeen = canarySeen || isCanary
                        if (!discoveryResults.discoveredRecords.containsKey(address)) {
                            discoveryResults.discoveredRecords[address] = DiscoveredDevice(record, result.rssi, result.txPower, record.deviceName)
                            Log.d(TAG, "onScanResult(): New entry[$address]: ${discoveryResults.discoveredRecords[address]}")
                        } else {
                            if (discoveryResults.discoveredRecords[address]?.updateMaybe(record, result.rssi, result.txPower, record.deviceName) == true) {
                                Log.d(TAG, "onScanResult(): Updated entry[$address]: ${discoveryResults.discoveredRecords[address]}")
                            }
                        }
                        record
                    }
                }
//                Log.d(TAG, "onScanResult(): New cache: $discoveredRecords")
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                val count = results?.size ?: 0
                Log.d(TAG, "onBatchScanResults(): count=$count")
                results.orEmpty().forEach { result ->
                    val address = result.device.address?.uppercase() ?: "<unknown>"
                    val record = result.scanRecord
                    if (record == null) {
                        Log.d(TAG, "onBatchScanResults(): address=$address ignored because scanRecord is null")
                        return@forEach
                    }
                    val isBonded = bondedAddresses.contains(address)
                    val isCanary = isCanaryDevice(address, record.deviceName)
                    devicesFound.add(address)
                    val isNamedTarget = isTrackedDevice(address, record.deviceName)
                    if (!isBonded && !isCanary && !isNamedTarget) {
                        Log.d(TAG, "onBatchScanResults(): address=$address ignored because it is not bonded or canary")
                        return@forEach
                    }
                    matchedDevices.add(address)
                    canarySeen = canarySeen || isCanary
                    if (!discoveryResults.discoveredRecords.containsKey(address)) {
                        discoveryResults.discoveredRecords[address] = DiscoveredDevice(record, result.rssi, result.txPower, record.deviceName)
                        Log.d(TAG, "onBatchScanResults(): New entry[$address]: ${discoveryResults.discoveredRecords[address]}")
                    } else {
                        if (discoveryResults.discoveredRecords[address]?.updateMaybe(record, result.rssi, result.txPower, record.deviceName) == true) {
                            Log.d(TAG, "onBatchScanResults(): Updated entry[$address]: ${discoveryResults.discoveredRecords[address]}")
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "onScanFailed(): errorCode=$errorCode")
            }
        }
        bleScanner.startScan(emptyList(), settings, callback)
        Log.d(TAG, "Scan has started for $scanDurationSeconds s, mode=$scanMode")
        withContext(Dispatchers.IO) {
            Thread.sleep(1000L * scanDurationSeconds)
        }
        bleScanner.stopScan(callback)
        Log.d(TAG, "executeScan(): Waiting ${scanCallbackDrainMillis} ms for late scan callbacks to drain")
        withContext(Dispatchers.IO) {
            Thread.sleep(scanCallbackDrainMillis)
        }
        Log.d(TAG, "Scan has finished, devicesFound=${devicesFound.size}, cachedDevices=${discoveryResults.discoveredRecords.size}")
        if (devicesFound.isNotEmpty()) {
            updateNotification("Devices discovered: ${devicesFound.size}")
        }
        return SingleScanAttempt(
            devicesFound = devicesFound.size,
            matchedDeviceCount = matchedDevices.size,
            canarySeen = canarySeen,
            scanMode = scanMode,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun executeScan(): ScanExecutionMeta {
        val scanStartedTimestamp = Date().time
        val permissionsGranted = applicationContext.hasRequiredRuntimePermissions()
        if (!permissionsGranted) {
            Log.w(TAG, "No runtime permissions")
            val scanFinishedTimestamp = Date().time
            discoveryResults.discoveredRecords.clear()
            return ScanExecutionMeta(
                status = "permissions_missing",
                statusReason = "missing_runtime_permissions",
                permissionsGranted = false,
                bluetoothEnabled = false,
                scanStartedTimestamp = scanStartedTimestamp,
                scanFinishedTimestamp = scanFinishedTimestamp,
                totalDevicesSeen = 0,
                matchedDeviceCount = 0,
                bondedDeviceCount = 0,
                canarySeen = false,
                scanMode = -1,
            )
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter disabled")
            val scanFinishedTimestamp = Date().time
            discoveryResults.discoveredRecords.clear()
            return ScanExecutionMeta(
                status = "bluetooth_disabled",
                statusReason = "bluetooth_adapter_disabled",
                permissionsGranted = true,
                bluetoothEnabled = false,
                scanStartedTimestamp = scanStartedTimestamp,
                scanFinishedTimestamp = scanFinishedTimestamp,
                totalDevicesSeen = 0,
                matchedDeviceCount = 0,
                bondedDeviceCount = 0,
                canarySeen = false,
                scanMode = -1,
            )
        }

        val bondedAddresses = bondedAddresses()
        val configuredScanDuration = preferences.getInt(
            applicationContext,
            R.string.settings_scan_duration,
            R.string.settings_scan_duration_def
        )
        val primaryAttempt = runSingleScan(
            bondedAddresses = bondedAddresses,
            scanMode = ScanSettings.SCAN_MODE_LOW_POWER,
            scanDurationSeconds = configuredScanDuration,
        )
        if (primaryAttempt == null) {
            val scanFinishedTimestamp = Date().time
            discoveryResults.discoveredRecords.clear()
            return ScanExecutionMeta(
                status = "scanner_unavailable",
                statusReason = "bluetooth_le_scanner_unavailable",
                permissionsGranted = true,
                bluetoothEnabled = true,
                scanStartedTimestamp = scanStartedTimestamp,
                scanFinishedTimestamp = scanFinishedTimestamp,
                totalDevicesSeen = 0,
                matchedDeviceCount = 0,
                bondedDeviceCount = bondedAddresses.size,
                canarySeen = false,
                scanMode = -1,
            )
        }

        var finalAttempt = primaryAttempt
        var statusReason: String? = null
        if (primaryAttempt.devicesFound == 0) {
            val recoveryDurationSeconds = maxOf(configuredScanDuration, 10)
            Log.w(
                TAG,
                "executeScan(): First scan returned 0 devices, retrying with scanMode=${ScanSettings.SCAN_MODE_LOW_LATENCY}, duration=${recoveryDurationSeconds}s"
            )
            val retryAttempt = runSingleScan(
                bondedAddresses = bondedAddresses,
                scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
                scanDurationSeconds = recoveryDurationSeconds,
            )
            if (retryAttempt != null) {
                finalAttempt = retryAttempt
                statusReason = if (retryAttempt.devicesFound > 0) {
                    "recovered_after_zero_result_retry"
                } else {
                    "zero_results_after_retry"
                }
            } else {
                statusReason = "scanner_unavailable_after_retry"
            }
        }

        return ScanExecutionMeta(
            status = "scan_completed",
            statusReason = statusReason,
            permissionsGranted = true,
            bluetoothEnabled = true,
            scanStartedTimestamp = scanStartedTimestamp,
            scanFinishedTimestamp = Date().time,
            totalDevicesSeen = finalAttempt.devicesFound,
            matchedDeviceCount = finalAttempt.matchedDeviceCount,
            bondedDeviceCount = bondedAddresses.size,
            canarySeen = finalAttempt.canarySeen,
            scanMode = finalAttempt.scanMode,
        )
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())
        val enabled = preferences.getBool(applicationContext, R.string.settings_enabled, R.string.settings_enabled_def)
        val optimizeBackground = preferences.getBool(applicationContext, R.string.settings_optimize_background, R.string.settings_optimize_background_def)
        Log.d(TAG, "doWork(): Next scan: $enabled / ${powerManager.isInteractive} / ${optimizeBackground}")
        if (!enabled) {
            Log.d(TAG, "doWork(): Stopping background scan as not enabled")
            return Result.success()
        }
        val scanMeta = executeScan()
        uploadData(scanMeta)
        if (optimizeBackground && !powerManager.isInteractive) {
            Log.d(TAG, "doWork(): Stopping background scan for optimization")
            return Result.success()
        }
        val scanInterval = preferences.getInt(applicationContext, R.string.settings_scan_interval, R.string.settings_scan_interval_def)
        Log.d(TAG, "doWork(): Scheduling next scan in $scanInterval s")
        scheduleForegroundScan(applicationContext, runImmediately = false, delaySeconds = scanInterval.toLong())
        return Result.success()
    }

    private fun createNotification(text: String) : Notification {
        return NotificationCompat.Builder(applicationContext, "foreground")
            .setContentTitle("Bluetooth Proxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_foreground_scan)
            .setOngoing(true)
//            .addAction(android.R.drawable.ic_delete, cancel, intent)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        NotificationManagerCompat.from(applicationContext).notify(FG_NOTIFICATION_ID, createNotification(text))
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channel = NotificationChannelCompat.Builder("foreground", NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Foreground tasks")
            .build()
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(channel)
        val notification = createNotification("Application starting")
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                ForegroundInfo(FG_NOTIFICATION_ID, notification)
            }
            else -> {
                ForegroundInfo(
                    FG_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        }
    }
}
