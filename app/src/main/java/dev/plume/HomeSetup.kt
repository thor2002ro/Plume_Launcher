package dev.plume

/** Home setup rules shared by the activity and its accessibility service. */
internal object HomeSetup {
    fun listed(services: String?, packageName: String) = services.orEmpty().split(':').any {
        it == "$packageName/.HomeOverride" || it == "$packageName/dev.plume.HomeOverride"
    }

    fun commands(packageName: String, sdk: Int) = listOfNotNull(
        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
        "adb shell appops set $packageName ACCESS_RESTRICTED_SETTINGS allow".takeIf { sdk >= 33 },
    )
}
