package com.kira.harmoid.compat

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import ohos.ace.plugin.photoaccesshelper.PhotoPlugin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class, manifest = Config.NONE)
class PhotoCompatibilityTest {
    private class TestPhoto(context: Context) : PhotoPlugin(context) {
        override fun nativeInit() { }
    }

    private class ResultProvider : ContentProvider() {
        var result: Cursor? = null
        var deny = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           args: Array<out String>?, order: String?): Cursor? {
            if (deny) throw SecurityException("Photo permission revoked")
            return result
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun photoResultsCloseCursorsAndHandleEmptyOrUnavailableData() {
        val app = RuntimeEnvironment.getApplication()
        val provider = ResultProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "harmoid.photos" })
        ShadowContentResolver.registerProviderInternal("harmoid.photos", provider)
        val plugin = TestPhoto(app)
        ReflectionHelpers.setField(plugin, "mResolver", app.contentResolver)
        fun resolve(): String = ReflectionHelpers.callInstanceMethod(plugin, "getImagePath",
            ClassParameter.from(Uri::class.java, Uri.parse("content://harmoid.photos/1")))

        val empty = MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA))
        provider.result = empty
        assertEquals("", resolve())
        assertTrue(empty.isClosed)

        val noData = MatrixCursor(arrayOf("display_name")).apply { addRow(arrayOf("photo.jpg")) }
        provider.result = noData
        assertEquals("", resolve())
        assertTrue(noData.isClosed)

        val valid = MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply {
            addRow(arrayOf("/storage/emulated/0/Pictures/photo.jpg"))
        }
        provider.result = valid
        assertEquals("file:///storage/emulated/0/Pictures/photo.jpg", resolve())
        assertTrue(valid.isClosed)

        provider.deny = true
        assertEquals("", resolve())
    }
}
