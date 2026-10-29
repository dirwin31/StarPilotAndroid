package com.starpilot.galaxy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** Gives Galaxy access only to a document the user selects, without storage permissions. */
class GalaxyFilePicker(private val activity: Activity, private val current: (WebView) -> Boolean,
                       private val error: (String) -> Unit) : WebChromeClient() {
    private data class Selection(val code: Int, val view: WebView, val page: String?, val callback: ValueCallback<Array<Uri>>)
    private var pending: Selection? = null
    private var nextCode = 4100

    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        cancel()
        if (!current(view) || params.mode != FileChooserParams.MODE_OPEN) {
            callback.onReceiveValue(null)
            return true
        }
        // XAPK/APKM often have no registered MIME type. Galaxy checks the selected
        // file's name, size and ZIP signature before uploading it to comma.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val code = nextCode
        nextCode = if (nextCode == 65535) 4100 else nextCode + 1
        pending = Selection(code, view, view.url, callback)
        try {
            activity.startActivityForResult(intent, code)
        } catch (_: ActivityNotFoundException) {
            cancel()
            error("No file picker is available. Enable the Files app, then try again.")
        } catch (_: SecurityException) {
            cancel()
            error("The file picker couldn't open. Try again using the Files app.")
        }
        return true
    }

    fun result(code: Int, result: Int, data: Intent?): Boolean {
        val selection = pending?.takeIf { it.code == code } ?: return false
        pending = null
        val uri = data?.data
        val active = current(selection.view) && selection.view.url == selection.page
        val readable = result == Activity.RESULT_OK && uri?.scheme == "content" &&
            activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        selection.callback.onReceiveValue(if (active && readable) arrayOf(uri!!) else null)
        if (active && result == Activity.RESULT_OK && !readable) error("That file couldn't be opened. Choose it again from Downloads in the Files app.")
        return true
    }

    /** Cancel before destroying/replacing a WebView; late results cannot satisfy a new request. */
    fun cancel() {
        val selection = pending
        pending = null
        selection?.callback?.onReceiveValue(null)
    }
}
