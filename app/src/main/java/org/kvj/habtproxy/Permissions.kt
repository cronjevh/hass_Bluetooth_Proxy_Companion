package org.kvj.habtproxy

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

fun Context.hasPermission(permissionType: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permissionType) ==
            PackageManager.PERMISSION_GRANTED
}

private fun Context.hasLegacyLocationPermissions(): Boolean {
    val hasFineLocation = hasPermission("android.permission.ACCESS_FINE_LOCATION")
    if (!hasFineLocation) {
        return false
    }
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        hasPermission("android.permission.ACCESS_BACKGROUND_LOCATION")
}

fun Context.hasRequiredRuntimePermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission("android.permission.BLUETOOTH_SCAN") &&
            hasPermission("android.permission.BLUETOOTH_CONNECT")
    } else {
        hasLegacyLocationPermissions()
    }
}

fun Activity.requestRelevantRuntimePermissions() {
    if (hasRequiredRuntimePermissions()) { return }
    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> {
            requestLegacyLocationPermissions()
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            requestBluetoothPermissions()
        }
    }
}

private val LOCATION_PERMISSION_REQUEST_CODE = 1
private val BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 2
private val BLUETOOTH_PERMISSION_REQUEST_CODE = 3

private fun Activity.requestLegacyLocationPermissions() {
    if (!hasPermission("android.permission.ACCESS_FINE_LOCATION")) {
        requestFineLocationPermission()
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        !hasPermission("android.permission.ACCESS_BACKGROUND_LOCATION")
    ) {
        requestBackgroundLocationPermission()
    }
}

private fun Activity.requestFineLocationPermission() {
    ActivityCompat.requestPermissions(
        this,
        arrayOf("android.permission.ACCESS_FINE_LOCATION"),
        LOCATION_PERMISSION_REQUEST_CODE
    )
}

private fun Activity.requestBackgroundLocationPermission() {
    ActivityCompat.requestPermissions(
        this,
        arrayOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
        BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE
    )
}

private fun Activity.requestBluetoothPermissions() {
    ActivityCompat.requestPermissions(
        this,
        arrayOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
        ),
        BLUETOOTH_PERMISSION_REQUEST_CODE
    )
}
