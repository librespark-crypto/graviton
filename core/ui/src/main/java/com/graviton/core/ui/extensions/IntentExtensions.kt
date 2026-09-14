package com.graviton.core.ui.extensions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.graviton.core.ui.R

/**
 * Opens [url] with a standard Android view intent, resolving the system browser / default handler.
 *
 * This is deliberately an explicit [Intent.ACTION_VIEW] launch rather than a share sheet or a
 * custom tab: the About screen links to the project repository and donation pages, and the
 * platform-default handler is what users expect. Failures (no browser installed, handler blocked
 * by the OS, activity start restrictions) are reported as a toast instead of crashing.
 *
 * @return true when an activity was successfully started.
 */
fun Context.openInBrowser(url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }
}

/** Opens [url] and shows a localized error toast when no handler is available. */
fun Context.openInBrowserOrToast(url: String) {
    if (!openInBrowser(url)) {
        Toast.makeText(this, getString(R.string.error_opening_link), Toast.LENGTH_SHORT).show()
    }
}
