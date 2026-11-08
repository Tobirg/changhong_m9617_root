package com.tobirg.rootmate

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

        adbStatus = findViewById(R.id.permAdbStatus)
        adbButton = findViewById(R.id.permAdbButton)
        // until the port check answers (onResume)
        adbButton.text = getString(R.string.perm_adb_open_dev)

        storageButton.setOnClickListener { openStorageSettings() }
        installButton.setOnClickListener { openInstallSettings() }
        adbButton.setOnClickListener { openAdbSettings() }
        findViewById<Button>(R.id.permDone).setOnClickListener { finish() }
        TvFocus.apply(storageButton, 10f)
        TvFocus.apply(installButton, 10f)
        TvFocus.apply(adbButton, 10f)
        TvFocus.apply(findViewById(R.id.permDone))
    }

    private lateinit var adbStatus: TextView
    private lateinit var adbButton: Button
    private var adb: AdbLocal.Status? = null

    override fun onResume() {
        super.onResume()
        refresh()
        // the port check blocks briefly: off the UI thread
        Thread {
            val s = AdbLocal.status(this)
            runOnUiThread { adb = s; refreshAdb() }
        }.start()
    }

    private fun refreshAdb() {
        val s = adb ?: return
        adbStatus.text = getString(
            when {
                s.ready -> R.string.perm_status_granted
                !s.developer -> R.string.perm_adb_need_dev
                !s.usbDebugging -> R.string.perm_adb_need_usb
                else -> R.string.perm_adb_no_port
            }
        )
        adbStatus.setTextColor(resources.getColor(if (s.ready) R.color.accent else R.color.warning, theme))
        adbButton.text = getString(if (!s.developer) R.string.perm_adb_open_about else R.string.perm_adb_open_dev)
    }

    /**
     * Developer options off: the "About" screen, where "Build" is pressed 7 times. On: the developer options
     * with "USB debugging" (on this TV it also makes adbd listen on tcp 5555). A toast says what to press.
     */
    private fun openAdbSettings() {
        val dev = adb?.developer ?: false
        startFirst(
            Intent(if (dev) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        android.widget.Toast.makeText(
            this, if (dev) R.string.perm_adb_hint_usb else R.string.perm_adb_hint_dev, android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun refresh() {
        val hasStorage = Perms.hasStorage(this)
        storageStatus.text = getString(if (hasStorage) R.string.perm_status_granted else R.string.perm_status_not_granted)
        storageStatus.setTextColor(resources.getColor(if (hasStorage) R.color.accent else R.color.warning, theme))
        storageButton.text = getString(if (hasStorage) R.string.perm_manage_button else R.string.perm_grant_button)

        val hasInstall = packageManager.canRequestPackageInstalls()
        installStatus.text = getString(if (hasInstall) R.string.perm_status_granted else R.string.perm_status_not_granted)
        installStatus.setTextColor(resources.getColor(if (hasInstall) R.color.accent else R.color.warning, theme))
        installButton.text = getString(if (hasInstall) R.string.perm_manage_button else R.string.perm_grant_button)
    }

    /** The per-app screen first; firmware without it gets the general list, then the app details. */
    private fun openStorageSettings() {
        startFirst(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun openInstallSettings() {
        startFirst(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    /** ActivityNotFoundException comes from startActivity, not from building the Intent: try them in turn. */
    private fun startFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
    }
}
