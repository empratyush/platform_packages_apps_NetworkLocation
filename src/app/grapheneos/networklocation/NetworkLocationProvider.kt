package app.grapheneos.networklocation

import android.annotation.SuppressLint
import android.content.Context
import android.ext.settings.NetworkLocationSettings
import android.location.Location
import android.location.LocationManager
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderProperties
import android.location.provider.ProviderRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.grapheneos.networklocation.apple_wps.AppleWps
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.pow
import kotlin.properties.Delegates
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A location provider that uses Apple's Wi-Fi positioning service to get an approximate location.
 */
class NetworkLocationProvider(private val context: Context) : LocationProviderBase(
    context, TAG, PROPERTIES
) {
    private val networkLocationServerSetting: Int
        get() {
            return Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.NETWORK_LOCATION
            )
        }

    private val wifiScanner = WifiScanner(context, ::mRequest, ::scanFinished)
    private var mRequest: ProviderRequest = ProviderRequest.EMPTY_REQUEST
    private var expectedNextLocationUpdateElapsedRealtimeNanos by Delegates.notNull<Long>()
    private var expectedNextBatchUpdateElapsedRealtimeNanos by Delegates.notNull<Long>()
    private var reportLocationJob: Job? = null
    private val reportLocationCoroutineScope = CoroutineScope(Dispatchers.IO)
    private val previousScanKnownAccessPoints: MutableSet<AppleWps.AccessPoint> = mutableSetOf()
    private val previousScanUnknownAccessPoints: MutableSet<AppleWps.AccessPoint> = mutableSetOf()
    private val isBatching: Boolean
        get() {
            return if (mRequest.isActive) {
                mRequest.maxUpdateDelayMillis >= (mRequest.intervalMillis * 2)
            } else {
                false
            }
        }
    private val batchedLocations: MutableList<Location> = mutableListOf()

    override fun isAllowed(): Boolean {
        val wifiManager = context.getSystemService(WifiManager::class.java)!!
        return (networkLocationServerSetting != NetworkLocationSettings.NETWORK_LOCATION_DISABLED) && (wifiManager.isWifiEnabled || wifiManager.isScanAlwaysAvailable)
    }

    fun scanFinished(scanResponse: WifiScanner.ScanResponse) {
        reportLocationJob = reportLocationCoroutineScope.launch {
            if (!mRequest.isActive) {
                cancel()
            }
            val scanResults = when (scanResponse) {
                is WifiScanner.ScanResponse.Failed -> {
                    delay(1000)
                    startNextScan()
                    cancel()
                    return@launch
                }

                is WifiScanner.ScanResponse.Success -> scanResponse.scanResults
            }
            val location = Location(LocationManager.NETWORK_PROVIDER)

            scanResults.sortByDescending { it.level }

            previousScanKnownAccessPoints.retainAll { knownAccessPoint ->
                scanResults.any { result ->
                    result.BSSID == knownAccessPoint.bssid
                }
            }
            previousScanUnknownAccessPoints.retainAll { unknownAccessPoint ->
                scanResults.any { result ->
                    result.BSSID == unknownAccessPoint.bssid
                }
            }

            scanResults.removeAll { result ->
                previousScanUnknownAccessPoints.any { unknownAccessPoints ->
                    unknownAccessPoints.bssid == result.BSSID
                }
            }

            var bestAvailableAccessPoint: Pair<ScanResult, AppleWps.AccessPoint>? = null

            for (accessPointScanResult in scanResults) {
                run {
                    val foundAccessPoint = previousScanKnownAccessPoints.find { knownAccessPoint ->
                        knownAccessPoint.bssid == accessPointScanResult.BSSID
                    }
                    if (foundAccessPoint != null) {
                        bestAvailableAccessPoint = Pair(accessPointScanResult, foundAccessPoint)
                    }
                }
                if (bestAvailableAccessPoint != null) {
                    break
                }

                try {
                    val url = URL(
                        when (networkLocationServerSetting) {
                            NetworkLocationSettings.NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY -> {
                                "https://gs-loc.apple.grapheneos.org/clls/wloc"
                            }

                            NetworkLocationSettings.NETWORK_LOCATION_SERVER_APPLE -> {
                                "https://gs-loc.apple.com/clls/wloc"
                            }

                            else -> {
                                throw RuntimeException("Server is not selected!")
                            }
                        }
                    )
                    val connection = url.openConnection() as HttpsURLConnection

                    try {
                        connection.requestMethod = "POST"
                        connection.setRequestProperty(
                            "Content-Type", "application/x-www-form-urlencoded"
                        )
                        connection.doOutput = true

                        connection.outputStream.use { outputStream ->
                            var header = byteArrayOf()

                            header += 1.toShort().toBeBytes()
                            header += 0.toShort().toBeBytes()
                            header += 0.toShort().toBeBytes()
                            header += 0.toShort().toBeBytes()
                            header += 0.toShort().toBeBytes()
                            header += 1.toShort().toBeBytes()
                            header += 0.toShort().toBeBytes()
                            header += 0.toByte()

                            val body = AppleWps.Body.newBuilder().addAccessPoints(
                                AppleWps.AccessPoint.newBuilder()
                                    .setBssid(accessPointScanResult.BSSID)
                                    .build()
                            ).build()

                            outputStream.write(header)
                            body.writeDelimitedTo(outputStream)
                        }

                        val responseCode = connection.responseCode
                        if (responseCode == HttpsURLConnection.HTTP_OK) {
                            connection.inputStream.use { inputStream ->
                                inputStream.skip(10)
                                val response = AppleWps.Body.parseFrom(inputStream)

                                val nullLatitudeOrLongitude = -18000000000
                                val matchedAccessPoint =
                                    response.accessPointsList.firstOrNull().let {
                                        return@let if ((it != null) && (it.bssid == accessPointScanResult.BSSID) && (it.positioningInfo.latitude != nullLatitudeOrLongitude) && (it.positioningInfo.longitude != nullLatitudeOrLongitude)) {
                                            it
                                        } else {
                                            null
                                        }
                                    }

                                if (matchedAccessPoint != null) {
                                    previousScanKnownAccessPoints.add(matchedAccessPoint)
                                    bestAvailableAccessPoint =
                                        Pair(accessPointScanResult, matchedAccessPoint)
                                } else {
                                    previousScanUnknownAccessPoints.add(
                                        AppleWps.AccessPoint.newBuilder()
                                            .setBssid(accessPointScanResult.BSSID)
                                            .build()
                                    )
                                }
                            }
                        } else {
                            delay(1000)
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting access point info", e)
                    delay(1000)
                }
            }

            if (bestAvailableAccessPoint != null) {
                location.elapsedRealtimeNanos =
                    bestAvailableAccessPoint!!.first.timestamp.toDuration(DurationUnit.MICROSECONDS).inWholeNanoseconds
                location.time =
                    (System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos()
                        .toDuration(DurationUnit.NANOSECONDS).inWholeMilliseconds) + location.elapsedRealtimeNanos.toDuration(
                        DurationUnit.NANOSECONDS
                    ).inWholeMilliseconds

                location.latitude =
                    bestAvailableAccessPoint!!.second.positioningInfo.latitude.toDouble() * 10.toDouble()
                        .pow(-8)
                location.longitude =
                    bestAvailableAccessPoint!!.second.positioningInfo.longitude.toDouble() * 10.toDouble()
                        .pow(-8)

                // estimate distance in meters from access point using the Log-Distance Path Loss Model
                val distanceFromAccessPoint = run {
                    val rssi = bestAvailableAccessPoint!!.first.level
                    // assume it's 30
                    val transmittedPower = 30f
                    val pathLoss = transmittedPower - rssi
                    val referenceDistance = 1f
                    // assume RSSI at reference distance is -30
                    val pathLossAtReferenceDistance = transmittedPower - (-30)
                    val pathLossExponent = 3f

                    referenceDistance * 10f.pow((pathLoss - pathLossAtReferenceDistance) / (10f * pathLossExponent))
                }

                // should be at the 68th percentile confidence level
                location.accuracy =
                    (bestAvailableAccessPoint!!.second.positioningInfo.accuracy.toFloat() * 0.68f) + distanceFromAccessPoint
            }

            if (isBatching) {
                batchedLocations += location

                if ((SystemClock.elapsedRealtimeNanos() >= expectedNextBatchUpdateElapsedRealtimeNanos) || (batchedLocations.size >= (mRequest.maxUpdateDelayMillis / mRequest.intervalMillis))) {
                    expectedNextBatchUpdateElapsedRealtimeNanos += mRequest.maxUpdateDelayMillis.toDuration(
                        DurationUnit.MILLISECONDS
                    ).inWholeNanoseconds
                    if (location.isComplete) {
                        reportLocations(batchedLocations)
                    }
                }
            } else if (location.isComplete) {
                reportLocation(location)
            }

            expectedNextLocationUpdateElapsedRealtimeNanos += mRequest.intervalMillis.toDuration(
                DurationUnit.MILLISECONDS
            ).inWholeNanoseconds

            startNextScan()
        }
    }

    private fun start() {
        expectedNextLocationUpdateElapsedRealtimeNanos =
            SystemClock.elapsedRealtimeNanos() + mRequest.intervalMillis.toDuration(
                DurationUnit.MILLISECONDS
            ).inWholeNanoseconds
        if (isBatching) {
            expectedNextBatchUpdateElapsedRealtimeNanos =
                SystemClock.elapsedRealtimeNanos() + mRequest.maxUpdateDelayMillis.toDuration(
                    DurationUnit.MILLISECONDS
                ).inWholeNanoseconds
        }

        reportLocationJob = reportLocationCoroutineScope.launch {
            startNextScan()
        }
    }

    fun stop() {
        wifiScanner.stop()
        mRequest = ProviderRequest.EMPTY_REQUEST
        reportLocationJob?.cancel()
        reportLocationJob = null
        previousScanKnownAccessPoints.clear()
        previousScanUnknownAccessPoints.clear()
    }

    private suspend fun startNextScan() {
        // scan takes ~11 seconds on lynx (Pixel 7a)
        val estimatedAfterScanElapsedRealtimeNanos =
            SystemClock.elapsedRealtimeNanos() + 11.0.toDuration(DurationUnit.SECONDS).inWholeNanoseconds
        if (estimatedAfterScanElapsedRealtimeNanos < expectedNextLocationUpdateElapsedRealtimeNanos) {
            // delay to ensure we get a fresh location
            delay(expectedNextLocationUpdateElapsedRealtimeNanos - estimatedAfterScanElapsedRealtimeNanos)
        }
        wifiScanner.start()
    }

    override fun onSetRequest(request: ProviderRequest) {
        stop()
        mRequest = request

        if (mRequest.isActive) {
            start()
        }
    }

    override fun onFlush(callback: OnFlushCompleteCallback) {
        if (batchedLocations.isNotEmpty()) {
            if (batchedLocations.size == 1) {
                reportLocation(batchedLocations[0])
            } else {
                reportLocations(batchedLocations)
            }

            batchedLocations.clear()
        }

        callback.onFlushComplete()
    }

    override fun onSendExtraCommand(command: String, extras: Bundle?) {
    }

    companion object {
        private const val TAG: String = "NetworkLocationProvider"
        private val PROPERTIES: ProviderProperties =
            ProviderProperties.Builder()
                .setHasNetworkRequirement(true)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
    }
}

fun Short.toBeBytes(): ByteArray {
    return byteArrayOf(
        ((this.toInt() shr 8) and 0xFF).toByte(), (this.toInt() and 0xFF).toByte()
    )
}