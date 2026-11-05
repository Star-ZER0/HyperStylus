package cc.star0.xposed.hyperstylus.ui.state

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import cc.star0.xposed.hyperstylus.R
import java.io.IOException

/** External actions stay outside the composable; gallery writes run on the caller's IO worker. */
internal object AboutActions {
    fun openLink(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** The official QQ deep link opens the group join screen; the user confirms inside QQ. */
    fun joinQQGroup(context: Context): Boolean =
        openQQGroupClient(context) || openLink(context, context.getString(R.string.qq_group_url))

    private fun openQQGroupClient(context: Context): Boolean = try {
        val groupUrl = "http://qm.qq.com/cgi-bin/qm/qr?from=app&p=android&jump_from=webapi&k=" +
            context.getString(R.string.qq_group_key)
        val uri = Uri.Builder().scheme("mqqopensdkapi").authority("bizAgent")
            .appendPath("qm").appendPath("qr").appendQueryParameter("url", groupUrl).build()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun saveTipCode(context: Context): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "HyperStylus_WeChat_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HyperStylus")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create appreciation image")
        try {
            context.resources.openRawResource(R.raw.tipcode).use { source ->
                (resolver.openOutputStream(uri) ?: throw IOException("Could not open appreciation image")).use { target ->
                    source.copyTo(target)
                }
            }
            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) throw IOException("Could not publish appreciation image")
            return uri
        } catch (error: Exception) {
            try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}
