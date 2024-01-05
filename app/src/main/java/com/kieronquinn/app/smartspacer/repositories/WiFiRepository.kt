package com.kieronquinn.app.smartspacer.repositories

import android.Manifest
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.ACCESS_WIFI_STATE
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.kieronquinn.app.smartspacer.components.smartspace.requirements.WiFiRequirement
import com.kieronquinn.app.smartspacer.repositories.WiFiRepository.WiFiNetwork
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerRequirementProvider
import com.kieronquinn.app.smartspacer.utils.extensions.currentWiFiNetwork
import com.kieronquinn.app.smartspacer.utils.extensions.getSSIDCompat
import com.kieronquinn.app.smartspacer.utils.extensions.hasPermission
import com.kieronquinn.app.smartspacer.utils.extensions.registerReceiverCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.annotations.VisibleForTesting

interface WiFiRepository {

    /**
     *  The currently connected WiFi network
     */
    val connectedNetwork: StateFlow<WiFiNetwork?>

    /**
     *  Available networks from background scanning
     */
    val availableNetworks: StateFlow<List<WiFiNetwork>>

    /**
     *  Gets the saved WiFi networks via the Shizuku service, if available
     */
    suspend fun getSavedWiFiNetworks(): List<WiFiNetwork>

    /**
     *  Re-registers the current network listener, for use if permissions have changed
     */
    fun refresh()

    /**
     *  Performs an immediate network scan, only used in-app (not in background)
     */
    fun scan()

    fun hasWiFiPermissions(): Boolean
    fun hasBackgroundLocationPermission(): Boolean
    fun hasEnabledBackgroundScanning(): Boolean

    data class WiFiNetwork(val ssid: String?, val mac: String?)

}

class WiFiRepositoryImpl(
    private val context: Context,
    private val shizukuServiceRepository: ShizukuServiceRepository,
    private val scope: CoroutineScope = MainScope()
): WiFiRepository {

    companion object {
        private val UNKNOWN_SSID = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WifiManager.UNKNOWN_SSID
        } else {
            "<unknown ssid>"
        }
    }

    @VisibleForTesting
    val refreshBus = MutableStateFlow(System.currentTimeMillis())

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val availableNetworks = MutableStateFlow<List<WiFiNetwork>>(emptyList())

    @RequiresApi(Build.VERSION_CODES.Q)
    override val connectedNetwork = refreshBus.flatMapLatest {
        connectivityManager.currentWiFiNetwork()
    }.map {
        it?.toWiFiNetwork()
    }.distinctUntilChanged().onEach {
        notifyWiFiChanged()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     *  Uses Shizuku service to get a list of saved WiFi networks via Shell.
     */
    @Suppress("UNCHECKED_CAST", "DEPRECATION") //Not actually deprecated, just a system API
    override suspend fun getSavedWiFiNetworks(): List<WiFiNetwork> {
        val list = shizukuServiceRepository.runWithService {
            it.savedWiFiNetworks
        }.unwrap()?.list as? List<WifiConfiguration> ?: return emptyList()
        return list.map {
            WiFiNetwork(it.SSID?.formatSSID(), it.BSSID)
        }
    }

    override fun refresh() {
        scope.launch {
            refreshBus.emit(System.currentTimeMillis())
        }
    }

    @Suppress("DEPRECATION") //No alternative. When removed, will have to invoke settings.
    override fun scan() {
        wifiManager.startScan()
    }

    private fun registerWiFiScanReceiver() {
        context.registerReceiverCompat(object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onNetworksChanged()
            }
        }, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    @SuppressLint("MissingPermission")
    private fun onNetworksChanged() = scope.launch {
        if(hasWiFiPermissions()){
            availableNetworks.emit(wifiManager.scanResults.map { it.toWiFiNetwork() })
            notifyWiFiChanged()
        }
    }

    override fun hasWiFiPermissions(): Boolean {
        return false
    }

    override fun hasBackgroundLocationPermission(): Boolean {
        return false
    }

    @Suppress("DEPRECATION") //It's still used. The docs are lying.
    override fun hasEnabledBackgroundScanning(): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
            0
        ) == 1
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("HardwareIds")
    private fun NetworkCapabilities.toWiFiNetwork(): WiFiNetwork? {
        val wifiInfo = null
        val ssid =  null
        return WiFiNetwork(ssid, wifiInfo)
    }

    private fun ScanResult.toWiFiNetwork(): WiFiNetwork {
        val ssid = getSSIDCompat()?.formatSSID()?.takeIf {
            it != UNKNOWN_SSID
        }
        return WiFiNetwork(ssid, BSSID)
    }

    private fun String.formatSSID(): String {
        return removePrefix("\"").removeSuffix("\"")
    }

    private fun notifyWiFiChanged() {
        SmartspacerRequirementProvider.notifyChange(context, WiFiRequirement::class.java)
    }

    init {
        registerWiFiScanReceiver()
        onNetworksChanged()
    }

}