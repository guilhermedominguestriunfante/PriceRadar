package com.dedonervoso.app.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object Links {
    /** The project owner's / developer's LinkedIn profile (shown on the home screen). */
    const val DEVELOPER_LINKEDIN = "https://www.linkedin.com/in/guilhermekawe/"

    /** Opens [url] in the browser (or LinkedIn app). Never crashes if nothing can handle it. */
    fun open(activity: Activity, url: String, failMessage: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, failMessage, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(activity, failMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
