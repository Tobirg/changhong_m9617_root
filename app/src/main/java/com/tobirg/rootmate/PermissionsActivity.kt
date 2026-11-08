package com.tobirg.rootmate

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Explains why RootMate needs MANAGE_EXTERNAL_STORAGE and
 * REQUEST_INSTALL_PACKAGES and lets the user jump to the matching system
 * settings screen for each one. MainActivity opens this automatically the
 * first time either permission is missing, and again whenever a gated
 * button is tapped while still missing its permission.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var storageStatus: TextView
    private lateinit var storageButton: Button
    private lateinit var installStatus: TextView
    private lateinit var installButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        storageStatus = findViewById(R.id.permStorageStatus)
        storageButton = findViewById(R.id.permStorageButton)
        installStatus = findViewById(R.id.permInstallStatus)
        installButton = findViewById(R.id.permInstallButton)

        storageButton.setOnClickListener { openStorageSettings() }
        installButton.setOnClickListener { openInstallSettings() }
        findViewById<Button>(R.id.permDone).setOnClickListener { finish() }
        TvFocus.apply(storageButton, 10f)
        TvFocus.apply(installButton, 10f)
        TvFocus.apply(findViewById(R.id.permDone))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hasStorage = Environment.isExternalStorageManager()
        storageStatus.text = getString(if (hasStorage) R.string.perm_status_granted else R.string.perm_status_not_granted)
        storageStatus.setTextColor(resources.getColor(if (hasStorage) R.color.accent else R.color.warning, theme))
        storageButton.text = getString(if (hasStorage) R.string.perm_manage_button else R.string.perm_grant_button)

        val hasInstall = packageManager.canRequestPackageInstalls()
        installStatus.text = getString(if (hasInstall) R.string.perm_status_granted else R.string.perm_status_not_granted)
        installStatus.setTextColor(resources.getColor(if (hasInstall) R.color.accent else R.color.warning, theme))
        installButton.text = getString(if (hasInstall) R.string.perm_manage_button else R.string.perm_grant_button)
    }

    private fun openStorageSettings() {
        val intent = try {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } catch (e: ActivityNotFoundException) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        startActivity(intent)
    }

    private fun openInstallSettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }
}
