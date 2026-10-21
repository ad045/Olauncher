package app.olauncher.helper.blocker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.olauncher.data.Prefs

/**
 * Turns the whole screen grayscale while connected to the home Wi-Fi network set in Settings,
 * by flipping the system's monochromacy colour correction.
 *
 * Needs a permission that can only be granted over adb, once:
 *     adb shell pm grant app.olauncher android.permission.WRITE_SECURE_SETTINGS
 *
 * Reading the SSID needs ACCESS_FINE_LOCATION and only works while the app is in the
 * foreground, which is why [sync] is called from MainActivity.onResume rather than from a
 * background watcher. Practical effect: the screen flips the first time you touch the home
 * screen after getting home, not the second you walk through the door. That trade buys no
 * background location permission and no foreground service.
 */
object Grayscale {

    /** Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, which is @hide. */
    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"

    /** Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, which is @hide. */
    private const val DALTONIZER = "accessibility_display_daltonizer"

    private const val MONOCHROMACY = 0

    private const val UNKNOWN_SSID = "<unknown ssid>"

    fun sync(context: Context) {
        val home = Prefs(context).blockerHomeWifi
        if (home.isEmpty()) return
        val current = currentSsid(context) ?: return
        apply(context, current == home)
    }

    /** The connected network's SSID, or null when it cannot be read (no permission, Wi-Fi off, not connected). */
    fun currentSsid(context: Context): String? {
        if (hasLocationPermission(context).not()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ssid = wifiManager.connectionInfo?.ssid?.trim('"')
            if (ssid.isNullOrEmpty() || ssid == UNKNOWN_SSID) null else ssid
        }.getOrNull()
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * This is the same system setting as Settings > Accessibility > Color correction, so it
     * clobbers any colour correction mode set there by hand.
     */
    fun apply(context: Context, on: Boolean) {
        runCatching {
            val resolver = context.contentResolver
            val wanted = if (on) 1 else 0
            if (Settings.Secure.getInt(resolver, DALTONIZER_ENABLED, 0) == wanted) return
            if (on) Settings.Secure.putInt(resolver, DALTONIZER, MONOCHROMACY)
            Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, wanted)
        }
    }
}
