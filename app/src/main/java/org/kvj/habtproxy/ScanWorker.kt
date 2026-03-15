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

private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

private fun SparseArray<ByteArray>.toDebugString(): String {
    if (size() == 0) {
        return "{}"
    }
    return keyIterator().asSequence().joinToString(prefix = "{", postfix = "}") { key ->
        "$key=${get(key).toHexString()}"
    }
}

private fun Map<android.os.ParcelUuid, ByteArray>.toDebugString(): String {
    if (isEmpty()) {
        return "{}"
    }
    return entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${key.uuid}=${value.toHexString()}"
    }
}

private fun logScanResult(address: String, result: ScanResult, record: android.bluetooth.le.ScanRecord) {
    val serviceUuids = record.serviceUuids?.joinToString(prefix = "[", postfix = "]") { it.uuid.toString() } ?: "[]"
    Log.d(
        TAG,
        "scanResult(): address=$address, name=${record.deviceName}, rssi=${result.rssi}, " +
            "txPower=${result.txPower}, recordTxPower=${record.txPowerLevel}, " +
            "serviceUuids=$serviceUuids, serviceData=${record.serviceData.toDebugString()}, " +
            "manufacturerData=${record.manufacturerSpecificData.toDebugString()}"
    )
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

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
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
        bluetoothAdapter.bondedDevices.orEmpty().map { it.address.uppercase() }.toSet()

    private suspend fun uploadData(): Boolean {
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
                    it.write(arr.toString())
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
    private suspend fun executeScan(): Boolean {
        if (!applicationContext.hasRequiredRuntimePermissions()) {
            Log.w(TAG, "No runtime permissions")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter disabled")
            return false
        }
        val bondedAddresses = bondedAddresses()
        Log.d(TAG, "executeScan(): Bonded device filter=$bondedAddresses")
        discoveryResults.discoveredRecords.keys.retainAll(bondedAddresses)
        val devicesFound = mutableSetOf<String>()
        val scanMode = ScanSettings.SCAN_MODE_LOW_POWER
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { result ->
                    result.scanRecord?.let { record ->
                        val address = result.device.address.uppercase()
                        if (!bondedAddresses.contains(address)) {
                            return
                        }
                        logScanResult(address, result, record)
                        devicesFound.add(address)
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
                    if (!bondedAddresses.contains(address)) {
                        Log.d(TAG, "onBatchScanResults(): address=$address ignored because it is not bonded")
                        return@forEach
                    }
                    logScanResult(address, result, record)
                    devicesFound.add(address)
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
        val scanDuration = preferences.getInt(applicationContext, R.string.settings_scan_duration, R.string.settings_scan_duration_def)
        Log.d(TAG, "Scan has started for $scanDuration s, mode=$scanMode")
        withContext(Dispatchers.IO) {
            Thread.sleep(1000L * scanDuration)
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
        return true
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
        executeScan()
        uploadData()
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
                ForegroundInfo(FG_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        }
    }
}
