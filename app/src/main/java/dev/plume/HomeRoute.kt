package dev.plume

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** App-owned returns must never certify a physical Home-button test. */
internal fun Context.plumeHomeIntent() = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_HOME).putExtra(HomeOverride.REDIRECTED_HOME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun Context.homeActivities() = packageManager.queryIntentActivities(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)

private fun Context.homeResolver() = packageManager.resolveActivity(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
)?.activityInfo

internal fun Context.resolvesHome() = homeResolver()?.packageName == packageName

private fun Context.homeSignature(homes: List<HomeEntry>) = homeResolver()?.let {
    HomeSetup.signature("${it.packageName}/${it.name}", homes, Build.FINGERPRINT, packageName)
}

internal fun Context.homeEntries() = homeActivities().map {
    val a = it.activityInfo
    HomeEntry(a.packageName, a.name, a.targetActivity)
}

internal fun Context.nativeHome(): Boolean {
    val homes = homeEntries()
    val key = homeSignature(homes) ?: return false
    return HomeSetup.native(packageName, packageName, HomeSetup.sole(homes.map { it.pkg }, packageName),
        key == getSharedPreferences("home", Context.MODE_PRIVATE).getString("native_home_verified", null))
}

internal fun Context.verifyNativeHome(): Boolean {
    val key = homeSignature(homeEntries()) ?: return false
    getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("native_home_verified", key).apply()
    return true
}

internal fun Context.soleHome() = HomeSetup.sole(homeActivities().map { it.activityInfo.packageName }, packageName)
