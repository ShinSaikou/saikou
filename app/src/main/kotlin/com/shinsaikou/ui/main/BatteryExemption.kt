package com.shinsaikou.ui.main

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * The platform's battery optimisation exemption, behind an interface so a view model can ask for
 * it without holding a `Context` and a test can answer without a device.
 */
interface BatteryExemption {

    /** True when Android already lets this app run unrestricted. */
    fun isExempt(): Boolean

    /** Opens the system prompt. It answers nothing back: the platform owns the decision. */
    fun request(activity: Activity)
}

@ContributesBinding(AppScope::class)
@Inject
class AppBatteryExemption(private val context: Context) : BatteryExemption {

    override fun isExempt(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * The per-app prompt first, the whole settings list second. Some builds answer the direct
     * request with nothing at all, and a tap that opens no screen reads as a broken row.
     */
    @SuppressLint("BatteryLife")
    override fun request(activity: Activity) {
        val target = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${activity.packageName}".toUri(),
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { activity.startActivity(target) }
            .recoverCatching { activity.startActivity(fallback) }
    }
}
