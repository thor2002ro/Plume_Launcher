package dev.plume

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal fun Context.homeActivities() = packageManager.queryIntentActivities(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)

internal fun Context.nativeHome() = HomeSetup.native(packageManager.resolveActivity(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
)?.activityInfo?.packageName, packageName)

internal fun Context.soleHome() = HomeSetup.sole(homeActivities().map { it.activityInfo.packageName }, packageName)
