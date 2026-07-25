package app.olauncher.data

/**
 * Personal launcher defaults, applied once on first open (fresh install).
 *
 * - [homeApps]: the five apps pinned to the home screen.
 * - [renameLabels]: custom app labels with number prefixes ("0 ", "1 ", "2 ")
 *   so typing a single digit in the app drawer search brings up the wanted
 *   group of apps directly.
 *
 * Edit this file to change the defaults, then reinstall (or clear app data)
 * to re-trigger the first-open setup.
 */
object PersonalDefaults {

    data class HomeApp(val label: String, val packageName: String)

    val homeApps = listOf(
        HomeApp("WhatsApp", "com.whatsapp"),
        HomeApp("Calendar", "com.google.android.calendar"),
        HomeApp("Maps", "com.google.android.apps.maps"),
        HomeApp("Mail", "com.google.android.gm"),
        HomeApp("Fitbit", "com.fitbit.FitbitMobile"),
    )

    val renameLabels = mapOf(
        "com.android.chrome" to "0 Chrome",
        "com.google.android.deskclock" to "0 Clock",
        "com.google.android.dialer" to "0 Phone",
        "com.google.android.apps.messaging" to "1 Messages",
        "com.microsoft.office.outlook" to "1 Outlook (TUM)",
        "com.google.android.GoogleCamera" to "2 Camera",
        "com.google.android.apps.photos" to "2 Photos",
    )

    fun apply(prefs: Prefs) {
        prefs.homeAppsNum = homeApps.size

        homeApps.getOrNull(0)?.let { prefs.appName1 = it.label; prefs.appPackage1 = it.packageName }
        homeApps.getOrNull(1)?.let { prefs.appName2 = it.label; prefs.appPackage2 = it.packageName }
        homeApps.getOrNull(2)?.let { prefs.appName3 = it.label; prefs.appPackage3 = it.packageName }
        homeApps.getOrNull(3)?.let { prefs.appName4 = it.label; prefs.appPackage4 = it.packageName }
        homeApps.getOrNull(4)?.let { prefs.appName5 = it.label; prefs.appPackage5 = it.packageName }

        renameLabels.forEach { (packageName, label) ->
            prefs.setAppRenameLabel(packageName, label)
        }
    }
}
