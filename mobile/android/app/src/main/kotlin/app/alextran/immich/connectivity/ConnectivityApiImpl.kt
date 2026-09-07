package app.alextran.immich.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

class ConnectivityApiImpl(context: Context) : ConnectivityApi {
  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val wifiManager =
    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

  override fun getCapabilities(): List<NetworkCapability> {
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
      ?: return emptyList()

    var hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
    var hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val hasVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    var isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    // Home Link: our own per-app WireGuard tunnel is the active network while away. What matters
    // for the "Wi-Fi only" backup rules is the network *under* it, so look through the VPN.
    if (hasVpn && !hasWifi && !hasCellular) {
      val underlying = connectivityManager.allNetworks.filter { n ->
          connectivityManager.getNetworkCapabilities(n)?.let { c ->
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          } == true
        }
      val caps = underlying.mapNotNull { connectivityManager.getNetworkCapabilities(it) }
      if (caps.isNotEmpty()) {
        hasWifi = caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
        hasCellular = !hasWifi && caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
        isUnmetered = caps.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
      }
    }

    return buildList {
      if (hasWifi) add(NetworkCapability.WIFI)
      if (hasCellular) add(NetworkCapability.CELLULAR)
      if (hasVpn) {
        add(NetworkCapability.VPN)
        if (!hasWifi && !hasCellular) {
          if (wifiManager.isWifiEnabled) add(NetworkCapability.WIFI)
          // If VPN is active, but neither WIFI nor CELLULAR is reported as active,
          // assume CELLULAR if WIFI is not enabled
          else add(NetworkCapability.CELLULAR)
        }
      }
      if (isUnmetered) add(NetworkCapability.UNMETERED)
    }
  }
}
