package app.alextran.immich.homelink

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted Home Link settings. Lives in the app's private storage (same protection as the
 * Immich session token). The WireGuard config is kept verbatim so it can be shown/edited again.
 */
class HomeLinkStore(ctx: Context) {
  private val sp: SharedPreferences =
    ctx.applicationContext.getSharedPreferences("Immich::HomeLink", Context.MODE_PRIVATE)

  var enabled: Boolean
    get() = sp.getBoolean(KEY_ENABLED, false)
    set(v) = sp.edit { putBoolean(KEY_ENABLED, v) }

  var wireguardConfig: String?
    get() = sp.getString(KEY_WG, null)
    set(v) = sp.edit { if (v.isNullOrBlank()) remove(KEY_WG) else putString(KEY_WG, v) }

  var homeUrl: String?
    get() = sp.getString(KEY_HOME_URL, null)
    set(v) = sp.edit { if (v.isNullOrBlank()) remove(KEY_HOME_URL) else putString(KEY_HOME_URL, v.trim().trimEnd('/')) }

  var lanDirect: Boolean
    get() = sp.getBoolean(KEY_LAN_DIRECT, true)
    set(v) = sp.edit { putBoolean(KEY_LAN_DIRECT, v) }

  var keepUpWhenAway: Boolean
    get() = sp.getBoolean(KEY_KEEP_UP, false)
    set(v) = sp.edit { putBoolean(KEY_KEEP_UP, v) }

  fun toConfig() = HomeLinkConfig(
    enabled = enabled,
    lanDirect = lanDirect,
    keepUpWhenAway = keepUpWhenAway,
    wireguardConfig = wireguardConfig,
    homeUrl = homeUrl,
  )

  fun save(c: HomeLinkConfig) {
    sp.edit {
      putBoolean(KEY_ENABLED, c.enabled)
      putBoolean(KEY_LAN_DIRECT, c.lanDirect)
      putBoolean(KEY_KEEP_UP, c.keepUpWhenAway)
      if (c.wireguardConfig.isNullOrBlank()) remove(KEY_WG) else putString(KEY_WG, c.wireguardConfig)
      if (c.homeUrl.isNullOrBlank()) remove(KEY_HOME_URL) else putString(KEY_HOME_URL, c.homeUrl.trim().trimEnd('/'))
    }
  }

  val isConfigured: Boolean get() = !wireguardConfig.isNullOrBlank() && !homeUrl.isNullOrBlank()

  companion object {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WG = "wgConfig"
    private const val KEY_HOME_URL = "homeUrl"
    private const val KEY_LAN_DIRECT = "lanDirect"
    private const val KEY_KEEP_UP = "keepUpWhenAway"
  }
}
