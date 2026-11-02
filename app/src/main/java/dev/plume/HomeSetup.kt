package dev.plume

/** Home setup rules shared by the activity and its accessibility service. */
internal object HomeSetup {
    fun native(resolvedPackage: String?, packageName: String) = resolvedPackage == packageName

    fun sole(packages: List<String>, packageName: String) = packages.isNotEmpty() && packages.all { it == packageName }

    fun recover(requested: Boolean, off: Boolean, repair: Boolean) = requested && !off && !repair

    fun listed(services: String?, packageName: String) = services.orEmpty().split(':').any {
        it == "$packageName/.HomeOverride" || it == "$packageName/dev.plume.HomeOverride"
    }

    fun commands(packageName: String, sdk: Int) = listOfNotNull(
        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
        "adb shell appops set $packageName ACCESS_RESTRICTED_SETTINGS allow".takeIf { sdk >= 33 },
    )
}
