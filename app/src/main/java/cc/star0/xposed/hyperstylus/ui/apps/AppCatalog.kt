package cc.star0.xposed.hyperstylus.ui.apps

import android.app.AppOpsManager

import cc.star0.xposed.hyperstylus.common.Protocol
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.provider.Settings
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import java.text.Collator

internal data class AppEntry(
    val packageName: String,
    val label: String,
    val installed: Boolean = true,
    val system: Boolean = false,
    val uid: Int? = null,
    val versionName: String? = null,
    val versionCode: Long = 0,
)
internal data class AppCatalogResult(val apps: List<AppEntry>, val accessDenied: Boolean = false, val failed: Boolean = false)

/** Package queries and bitmap conversion are called from Dispatchers.IO, never during composition. */
internal object AppCatalog {
    private val bitmapCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun read(context: Context): AppCatalogResult {
        bitmapCache.evictAll()
        if (AppListAccess.missingRuntimePermission(context) != null || AppListAccess.appOpDenied(context)) {
            return AppCatalogResult(emptyList(), accessDenied = true)
        }
        return try {
            val manager = context.packageManager
            val collator = Collator.getInstance()
            val entries = manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).mapNotNull { pkg ->
                val app = pkg.applicationInfo ?: return@mapNotNull null
                val label = runCatching { manager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                AppEntry(app.packageName, label.ifBlank { app.packageName },
                    system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    uid = app.uid, versionName = pkg.versionName, versionCode = pkg.longVersionCode)
            }.sortedWith { a, b ->
                collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
            }
            val visibleWithoutPermission = setOf(context.packageName, "android", Protocol.SYSTEM_UI,
                Protocol.BLUETOOTH, Protocol.SECURITY_CORE, Protocol.HOME, "com.miui.securitycenter")
            AppCatalogResult(entries, accessDenied = entries.none { it.packageName !in visibleWithoutPermission })
        } catch (_: SecurityException) {
            AppCatalogResult(emptyList(), accessDenied = true)
        } catch (_: RuntimeException) {
            AppCatalogResult(emptyList(), failed = true)
        }
    }

    fun icon(context: Context, packageName: String, sizeDp: Int = 48): Bitmap? {
        val size = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val key = "$packageName:$size:${context.resources.configuration.uiMode}"
        bitmapCache.get(key)?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName).mutate()
            createBitmap(size, size).also { bitmap ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
                bitmapCache.put(key, bitmap)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** QUERY_ALL_PACKAGES itself is install-time; only actual OEM dangerous permissions are requested. */
internal object AppListAccess {
    private val vendorPermissions = listOf("com.android.permission.GET_INSTALLED_APPS", "com.miui.permission.GET_INSTALLED_APPS")

    fun missingRuntimePermission(context: Context): String? = vendorPermissions.firstOrNull { permission ->
        try {
            context.packageManager.getPermissionInfo(permission, 0).protection == PermissionInfo.PROTECTION_DANGEROUS &&
                context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun appOpDenied(context: Context): Boolean = vendorPermissions.any { permission ->
        try {
            val operation = AppOpsManager.permissionToOp(permission) ?: return@any false
            val mode = context.getSystemService(AppOpsManager::class.java)
                ?.checkOpNoThrow(operation, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
        } catch (_: RuntimeException) {
            false
        }
    }

    fun openSettings(context: Context): Boolean {
        val destinations = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").setPackage("com.miui.securitycenter")
                .putExtra("extra_pkgname", context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        )
        for (intent in destinations) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Some ROMs omit the Xiaomi permissions activity; use Android app details.
            } catch (_: SecurityException) {
                // Fall through to the next supported permission editor.
            }
        }
        return false
    }
}
