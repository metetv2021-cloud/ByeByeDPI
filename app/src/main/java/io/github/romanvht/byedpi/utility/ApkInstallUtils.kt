package io.github.romanvht.byedpi.utility

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/** Installing, finding and opening other apps. */
object ApkInstallUtils {
    private const val TAG = "ApkInstall"
    const val AURORA_STORE = "com.aurora.store"

    /**
     * Where downloaded APK files are kept. Android 7+ installs them from the app's private cache
     * through a content link; older versions need a file the installer can read.
     */
    fun apkDir(context: Context): File? {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.cacheDir else context.externalCacheDir
        return base?.let { File(it, "apks") }
    }

    /** On Android 8+ the user has to allow each app to install other apps. */
    fun canInstallApps(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
        return start(activity, intent)
    }

    /** Opens Android's installer for the file. Android asks the user before installing. */
    fun install(activity: Activity, file: File): Boolean {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                FileProvider.getUriForFile(activity, "${activity.packageName}.apks", file)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "File is outside the shared folder", e)
                return false
            }
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(activity, intent)
    }

    @Suppress("DEPRECATION")
    fun packageNameOf(context: Context, file: File): String? = try {
        context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the APK file", e)
        null
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The first of the package names that is installed. */
    fun installedPackage(context: Context, packages: List<String>): String? =
        packages.firstOrNull { isInstalled(context, it) }

    fun launch(activity: Activity, packageName: String): Boolean {
        val manager = activity.packageManager
        val intent = manager.getLaunchIntentForPackage(packageName)
            ?: manager.getLeanbackLaunchIntentForPackage(packageName)
            ?: return false
        return start(activity, intent)
    }

    /** Opens the app's page in Google Play, or in Aurora Store when [aurora] is true. */
    fun openStorePage(activity: Activity, packageName: String, aurora: Boolean): Boolean {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
        if (aurora) return start(activity, market.setPackage(AURORA_STORE))
        if (start(activity, market.setPackage("com.android.vending"))) return true
        return start(activity, Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
    }

    fun openWebsite(activity: Activity, url: String): Boolean =
        start(activity, Intent(Intent.ACTION_VIEW, url.toUri()))

    private fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app for ${intent.data}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to open ${intent.data}", e)
        false
    }
}
