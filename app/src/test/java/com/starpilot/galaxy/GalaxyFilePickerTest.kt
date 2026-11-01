package com.starpilot.galaxy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class GalaxyFilePickerTest {
    class PickerActivity : Activity() {
        var request: Intent? = null
        var code = 0
        var available = true
        var readable = true
        override fun startActivityForResult(intent: Intent, requestCode: Int) {
            if (!available) throw ActivityNotFoundException()
            request = intent; code = requestCode
        }
        override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) =
            if (readable) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    private lateinit var activity: PickerActivity
    private lateinit var view: WebView
    private lateinit var picker: GalaxyFilePicker
    private var current = true
    private val values = mutableListOf<List<Uri>?>()
    private val errors = mutableListOf<String>()
    private val uri = Uri.parse("content://com.android.providers.downloads.documents/document/42")
    private val params = object : FileChooserParams() {
        override fun getMode() = MODE_OPEN
        override fun getAcceptTypes() = arrayOf(".apk", ".xapk", ".apkm", ".zip")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(PickerActivity::class.java).setup().get()
        view = WebView(activity).apply { loadUrl("http://172.31.254.1:8082/#/android-auto") }
        picker = GalaxyFilePicker(activity, { current && it === view }, errors::add)
    }

    private fun choose() = picker.onShowFileChooser(view, { values.add(it?.toList()) }, params)
    private fun result(value: Uri? = uri, status: Int = Activity.RESULT_OK) =
        picker.result(activity.code, status, value?.let { Intent().setData(it) })

    @Test fun opensAllDocumentsSoUnknownXapkMimeTypesRemainSelectable() {
        assertTrue(choose())
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.request?.action)
        assertEquals("*/*", activity.request?.type)
        assertTrue(activity.request!!.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(result())
        assertEquals(listOf(listOf(uri)), values)
        assertTrue(errors.isEmpty())
        assertFalse(result()) // A duplicate activity result must not repeat the upload.
    }

    @Test fun cancellationAllowsTheUserToChooseAgain() {
        choose(); result(null, Activity.RESULT_CANCELED)
        choose(); result()
        assertEquals(listOf(null, listOf(uri)), values)
        assertTrue(errors.isEmpty())
    }

    @Test fun lateResultFromOldConnectionCannotSatisfyNewPicker() {
        choose()
        val oldCode = activity.code
        picker.cancel()
        choose()
        assertFalse(picker.result(oldCode, Activity.RESULT_OK, Intent().setData(uri)))
        assertEquals(listOf<List<Uri>?>(null), values)
        result()
        assertEquals(listOf(null, listOf(uri)), values)
    }

    @Test fun pageChangesOrDisconnectedViewsCancelSelection() {
        choose()
        view.loadUrl("http://172.31.254.1:8082/#/navigation")
        result()
        choose(); current = false; result()
        assertEquals(listOf<List<Uri>?>(null, null), values)
    }

    @Test fun rejectsPrivateFilePathsAndDocumentsWithoutReadPermission() {
        choose(); result(Uri.parse("file:///data/data/link.firestar.companion/files/key"))
        choose(); activity.readable = false; result()
        assertEquals(listOf<List<Uri>?>(null, null), values)
        assertEquals(2, errors.size)
    }

    @Test fun missingPickerCompletesCallbackAndExplainsHowToRetry() {
        activity.available = false
        choose()
        assertEquals(listOf<List<Uri>?>(null), values)
        assertEquals(1, errors.size)
        activity.available = true
        choose(); result()
        assertEquals(listOf(null, listOf(uri)), values)
    }
}
