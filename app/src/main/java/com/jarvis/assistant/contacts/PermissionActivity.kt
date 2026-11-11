package com.jarvis.assistant.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * A see-through screen that shows Android's permission dialog for contacts and calling, then closes. Jarvis opens it
 * the first time you ask it to call someone, and the "Allow contacts and calling" button in Settings opens it too.
 */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: DEFAULT_PERMISSIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) finish() else requestPermissions(missing.toTypedArray(), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        private const val EXTRA_PERMISSIONS = "permissions"
        private const val REQUEST = 41

        /** Contacts to find the person, call to place the call, phone state to confirm that the call started. */
        val DEFAULT_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        )

        fun intent(context: Context): Intent = Intent(context, PermissionActivity::class.java)
            .putExtra(EXTRA_PERMISSIONS, DEFAULT_PERMISSIONS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
