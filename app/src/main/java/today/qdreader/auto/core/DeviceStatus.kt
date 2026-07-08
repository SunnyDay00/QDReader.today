package today.qdreader.auto.core

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import today.qdreader.auto.accessibility.QidianAccessibilityService

object DeviceStatus {
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, QidianAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabledServices
            .split(':')
            .any { it.equals(expected, ignoreCase = true) }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isTargetAppInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(AppConstants.QIDIAN_PACKAGE, 0)
        }.isSuccess
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openTargetApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(AppConstants.QIDIAN_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        context.startActivity(intent)
        return true
    }

    fun restartTargetApp(context: Context): Boolean {
        runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            activityManager.killBackgroundProcesses(AppConstants.QIDIAN_PACKAGE)
        }

        val intent = context.packageManager.getLaunchIntentForPackage(AppConstants.QIDIAN_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return false
        context.startActivity(intent)
        return true
    }

    fun openAutomationApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return false
        context.startActivity(intent)
        return true
    }
}
