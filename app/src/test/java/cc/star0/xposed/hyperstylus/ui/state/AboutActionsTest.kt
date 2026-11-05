package cc.star0.xposed.hyperstylus.ui.state

import android.app.Activity
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import cc.star0.xposed.hyperstylus.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AboutActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var gallery: GalleryProvider

    @Before fun setup() {
        gallery = GalleryProvider(File(context.cacheDir, "saved-tip.jpg"))
        gallery.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, gallery)
    }

    @Test fun savesOriginalJpegAndPublishesOnlyAfterCopyCompletes() {
        val uri = AboutActions.saveTipCode(context)
        assertEquals(gallery.item, uri)
        assertEquals("image/jpeg", gallery.inserted!!.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/HyperStylus", gallery.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, gallery.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertTrue(gallery.published)
        assertFalse(gallery.deleted)
        assertArrayEquals(context.resources.openRawResource(R.raw.tipcode).use { it.readBytes() }, gallery.file.readBytes())
    }

    @Test fun failedWriteRemovesThePendingImage() {
        gallery.rejectWrite = true
        assertThrows(IOException::class.java) { AboutActions.saveTipCode(context) }
        assertTrue(gallery.deleted)
        assertFalse(gallery.published)
    }

    @Test fun failedPublicationRemovesThePendingImage() {
        gallery.rejectPublish = true
        assertThrows(IOException::class.java) { AboutActions.saveTipCode(context) }
        assertTrue(gallery.deleted)
        assertFalse(gallery.published)
    }

    @Test fun unavailableBrowserReturnsFailureInsteadOfCrashing() {
        val noBrowser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(AboutActions.openLink(noBrowser, "https://star0.cc"))
    }

    @Test fun qqGroupUsesTheProvidedKeyWithoutStartingANewTaskFromAnActivity() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertTrue(AboutActions.joinQQGroup(activity))
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3DjGMPw1fEhifz25lfMoA6XtS0UVIlzZwn",
                intent.dataString)
            assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun qqGroupSupportsANonActivityContext() {
        var launched: Intent? = null
        val application = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        assertTrue(AboutActions.joinQQGroup(application))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launched!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun qqLaunchFailureFallsBackToTheProvidedWebLink() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val attempts = mutableListOf<Intent>()
            val webOnly = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) {
                    attempts += intent
                    if (intent.data?.scheme == "mqqopensdkapi") throw failure
                }
            }
            assertTrue(AboutActions.joinQQGroup(webOnly))
            assertEquals(2, attempts.size)
            assertEquals("mqqopensdkapi", attempts[0].data?.scheme)
            assertEquals(Intent.ACTION_VIEW, attempts[1].action)
            assertEquals("https://qm.qq.com/q/jVEd9nftWE", attempts[1].dataString)
        }
    }

    @Test fun unavailableQqAndBrowserReturnFailureInsteadOfCrashing() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val unavailable = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) { throw failure }
            }
            assertFalse(AboutActions.joinQQGroup(unavailable))
        }
    }

    private class GalleryProvider(val file: File) : ContentProvider() {
        val item: Uri = Uri.parse("content://media/external_primary/images/media/1")
        var inserted: ContentValues? = null
        var published = false
        var deleted = false
        var rejectWrite = false
        var rejectPublish = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            return item
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (rejectWrite) throw java.io.FileNotFoundException("Rejected gallery write")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertTrue("Bytes must be written before publication", file.length() > 0)
            assertEquals(0, values!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            if (rejectPublish) return 0
            published = true
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted = true
            file.delete()
            return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
