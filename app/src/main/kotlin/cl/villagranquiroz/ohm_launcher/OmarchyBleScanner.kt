package cl.villagranquiroz.ohm_launcher

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Immediate outcome of requesting a BLE discovery scan. */
enum class OhmBleScanStartResult {
    STARTED,
    ALREADY_SCANNING,
    PERMISSION_REQUIRED,
    BLUETOOTH_UNAVAILABLE,
}

/** Dependency-free BLE scanner for nearby Omarchy/Ohm advertisement names. */
class OmarchyBleScanner(
    context: Context,
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java),
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val appContext = context.applicationContext
    private var activeCallback: ScanCallback? = null
    private var completion: ((List<OhmBlePeer>) -> Unit)? = null
    private val collector = OmarchyBlePeerCollector()

    @Synchronized
    fun startScan(
        timeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MILLIS,
        onFinished: (List<OhmBlePeer>) -> Unit,
    ): OhmBleScanStartResult {
        require(timeoutMillis > 0) { "Scan timeout must be positive" }
        if (activeCallback != null) return OhmBleScanStartResult.ALREADY_SCANNING
        if (!hasPermissions()) return OhmBleScanStartResult.PERMISSION_REQUIRED

        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            ?: return OhmBleScanStartResult.BLUETOOTH_UNAVAILABLE
        collector.clear()
        completion = onFinished
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::record)
            }

            override fun onScanFailed(errorCode: Int) {
                finishScan()
            }
        }
        activeCallback = callback
        return try {
            // The Flutter reference UUID contains non-hex characters and cannot
            // be represented by Android's ParcelUuid, so parity uses its valid
            // advertised-name branch instead of an invalid service filter.
            scanner.startScan(callback)
            handler.postDelayed({ finishScan() }, timeoutMillis)
            OhmBleScanStartResult.STARTED
        } catch (_: SecurityException) {
            activeCallback = null
            completion = null
            OhmBleScanStartResult.PERMISSION_REQUIRED
        } catch (_: IllegalStateException) {
            activeCallback = null
            completion = null
            OhmBleScanStartResult.BLUETOOTH_UNAVAILABLE
        }
    }

    @Synchronized
    fun stopScan(): Boolean {
        if (activeCallback == null) return false
        finishScan()
        return true
    }

    private fun record(result: ScanResult) {
        if (!hasPermissions()) return
        try {
            val name = result.scanRecord?.deviceName ?: result.device.name.orEmpty()
            collector.record(OhmBlePeer(address = result.device.address, name = name, rssi = result.rssi))
        } catch (_: SecurityException) {
            // Permission can be revoked while scan results are being delivered.
        }
    }

    @Synchronized
    private fun finishScan() {
        val callback = activeCallback ?: return
        activeCallback = null
        if (hasPermissions()) {
            try {
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and platform call.
            }
        }
        val finished = completion
        completion = null
        finished?.invoke(collector.peers())
    }

    private fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        const val DEFAULT_SCAN_TIMEOUT_MILLIS = 6_000L
    }
}
