package com.nawaf.mohammel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * الربط مع تطبيق «عزل» (إزالة الموسيقى). محمّل يرسل له الرابط، وعزل يشيل الموسيقى منه.
 */
object Azl {
    private const val PACKAGE = "com.azl.app"
    const val DOWNLOAD_PAGE = "https://github.com/nawafz2011-blip/azl/releases/latest"

    /** بعد كم مرة نقترح على المستخدم يفتح عزل مباشرة. */
    private const val TIP_AFTER = 3

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("azl", Context.MODE_PRIVATE)

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** يفتح عزل والرابط جاهز فيه. يرجّع false إذا عزل مو مثبت. */
    fun send(ctx: Context, url: String): Boolean {
        if (!installed(ctx)) return false
        val intent = Intent(Intent.ACTION_SEND)
            .setPackage(PACKAGE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun openApp(ctx: Context): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openDownloadPage(ctx: Context) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------- الإعدادات
    fun alwaysRemove(ctx: Context) = prefs(ctx).getBoolean("always", false)

    fun setAlwaysRemove(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("always", on).apply()

    /** يزيد العدّاد، ويرجّع true إذا هذا وقت النصيحة (مرة وحدة بس). */
    fun countAndShouldTip(ctx: Context): Boolean {
        val p = prefs(ctx)
        val n = p.getInt("count", 0) + 1
        p.edit().putInt("count", n).apply()
        if (n >= TIP_AFTER && !p.getBoolean("tip_shown", false)) {
            p.edit().putBoolean("tip_shown", true).apply()
            return true
        }
        return false
    }
}
