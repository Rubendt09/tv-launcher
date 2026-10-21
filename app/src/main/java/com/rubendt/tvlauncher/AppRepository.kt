package com.rubendt.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class TvApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val banner: Drawable?,
    val launchIntent: Intent,
)

fun loadApps(context: Context): List<TvApp> {
    val pm = context.packageManager
    val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
    return categories
        .flatMap { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(it), 0) }
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map {
            val info = it.activityInfo
            TvApp(
                packageName = info.packageName,
                label = it.loadLabel(pm).toString(),
                icon = it.loadIcon(pm),
                banner = info.loadBanner(pm),
                launchIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.packageName, info.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        .sortedBy { it.label.lowercase() }
}
