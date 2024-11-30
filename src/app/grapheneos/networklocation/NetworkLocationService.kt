package app.grapheneos.networklocation

import android.app.Service
import android.content.Intent
import android.ext.settings.NetworkLocationSettings
import android.location.provider.ProviderRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * The network location service.
 */
class NetworkLocationService : Service() {

    private val networkLocationSettingObserver = {
        networkLocationSettingValue()
    }
    private var networkLocationSettingValue = {
        val networkLocationSettingValue = networkLocationSetting.get(this)
        val isAllowed =
            networkLocationSettingValue != NetworkLocationSettings.NETWORK_LOCATION_DISABLED
        if (provider.isAllowed != isAllowed) {
            provider.isAllowed = isAllowed
            if (!provider.isAllowed) {
                provider.onSetRequest(ProviderRequest.EMPTY_REQUEST)
            }
        }
        networkLocationSettingValue
    }
    private val provider: NetworkLocationProvider = NetworkLocationProvider(
        context = this,
        networkLocationSettingValue = networkLocationSettingValue
    )

    override fun onBind(intent: Intent?): IBinder? {
        networkLocationSetting.registerObserver(this,
            Handler(Looper.getMainLooper())) {

        }
        return provider.binder
    }

    override fun onDestroy() {
        super.onDestroy()
        provider.onSetRequest(ProviderRequest.EMPTY_REQUEST)
        networkLocationSetting.unregisterObserver(this, networkLocationSettingObserver)
    }

    companion object {
        private val networkLocationSetting = NetworkLocationSettings.NETWORK_LOCATION_SETTING
    }
}
