package com.tobirg.rootmate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.system.Os
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Patches the "skip_initramfs" string inside magiskinit (the "init" entry of
 * the ramdisk cpio) to "skip_initramfz" in an already Magisk-patched boot
 * image, using the magiskboot binary bundled inside the installed Magisk
 * app. Replaces scripts/magisk_skipfix.py - runs entirely on-device.
 *
 * File selection uses this app's own FilePickerActivity (plain java.io.File
 * over MANAGE_EXTERNAL_STORAGE) instead of the system SAF picker, since on
 * this firmware that picker is effectively provided by a third-party app
 * (Fluffy) rather than the OS - this removes that dependency entirely for
 * everything RootMate itself does. The patched result is always written
 * right next to the input file, so there's only one thing to pick.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logCard: com.google.android.material.card.MaterialCardView
    private lateinit var progressPanel: View
    private lateinit var progressText: TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var defaultFocusView: View
    private var pendingPickTarget: String = ""

    /** True while a long operation (patching, Magisk download) runs: all buttons are locked. */
    @Volatile
    private var busy = false
    private var installReceiver: android.content.BroadcastReceiver? = null

    private val allButtonIds = listOf(
        R.id.btnUartMenu, R.id.btnInstallMagisk, R.id.btnFullPatch, R.id.btnPickInput,
        R.id.btnOpenFactoryMenu, R.id.btnUninstall
    )

    /** Must be called on the UI thread. Locks the UI and runs [block] on a worker thread. */
    private fun runBusy(block: () -> Unit) {
        if (busy) return
        busy = true
        applyBusyState(true)
        Thread {
            try {
                block()
            } catch (e: Throwable) {
                log(getString(R.string.error_fmt, e.message ?: e.javaClass.simpleName))
            } finally {
                runOnUiThread {
                    busy = false
                    applyBusyState(false)
                }
            }
        }.start()
    }

    /** Progress panel under the log: [percent] 0..100, or -1 for "working, duration unknown". */
    private fun setProgress(percent: Int, text: String) {
        runOnUiThread {
            progressPanel.visibility = View.VISIBLE
            progressText.text = if (percent >= 0) "$text  ($percent%)" else text
            progressBar.isIndeterminate = percent < 0
            if (percent >= 0) progressBar.progress = percent
        }
    }

    /** Logs [text] and shows it as the current progress step. */
    private fun step(percent: Int, text: String) {
        log(text)
        setProgress(percent, text)
    }

    private fun applyBusyState(value: Boolean) {
        allButtonIds.forEach { findViewById<View>(it).isEnabled = !value }
        if (value) {
            progressPanel.visibility = View.VISIBLE
            progressText.text = getString(R.string.progress_preparing)
            progressBar.isIndeterminate = true
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            progressPanel.visibility = View.GONE
            defaultFocusView.requestFocus()
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(FilePickerActivity.EXTRA_PATH) ?: return@registerForActivityResult
        when (pendingPickTarget) {
            "manual" -> {
                appendLog(getString(R.string.picked_file, path))
                runBusy { patch(File(path)) }
            }
            "full" -> {
                appendLog(getString(R.string.picked_original, path))
                runBusy { fullPatch(path) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        logCard = findViewById(R.id.logCard)
        progressPanel = findViewById(R.id.progressPanel)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)

        defaultFocusView = findViewById(R.id.btnOpenFactoryMenu)
        logCard.strokeColor = android.graphics.Color.WHITE
        logScroll.setOnFocusChangeListener { _, focused ->
            logCard.strokeWidth = if (focused) (3 * resources.displayMetrics.density).toInt() else 0
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!busy) {
                    finish()
                    return
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.busy_exit_title)
                    .setMessage(R.string.busy_exit_message)
                    .setPositiveButton(R.string.busy_exit_stay, null)
                    .setNegativeButton(R.string.busy_exit_leave) { _, _ -> finish() }
                    .show()
            }
        })
        findViewById<Button>(R.id.btnUartMenu).setOnClickListener { openUartMenu() }
        findViewById<Button>(R.id.btnOpenFactoryMenu).setOnClickListener { openFactoryPage(0) }
        findViewById<Button>(R.id.btnInstallMagisk).setOnClickListener {
            withInstallPermission { downloadAndInstall("topjohnwu/Magisk", "Magisk") }
        }
        findViewById<Button>(R.id.btnFullPatch).setOnClickListener {
            withMagisk { withStoragePermission { launchPicker("full") } }
        }
        findViewById<Button>(R.id.btnPickInput).setOnClickListener {
            withMagisk { withStoragePermission { launchPicker("manual") } }
        }
        findViewById<Button>(R.id.btnUninstall).setOnClickListener { uninstallSelf() }

        val gatedByStorage = listOf(R.id.btnFullPatch, R.id.btnPickInput)
        allButtonIds.forEach { id ->
            val gated = when (id) {
                R.id.btnInstallMagisk -> { { !hasInstallPermission() } }
                in gatedByStorage -> { { !hasStoragePermission() || !isMagiskInstalled() } }
                else -> { { false } }
            }
            TvFocus.apply(findViewById(id), dimmed = gated)
        }
    }

    private var permissionsIntroShown = false

    /**
     * Returning from the factory app (after opening its menu), or from the
     * permissions screen, can leave this activity with no focused view, so
     * the TV remote's D-pad does nothing visible. Force focus back onto a
     * known button whenever this activity comes back to the foreground.
     * Also where the two permission-gated button groups get their
     * enabled/disabled look refreshed, and where the permissions screen is
     * opened automatically the first time either permission is missing.
     */
    override fun onDestroy() {
        installReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        installReceiver = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        defaultFocusView.post { defaultFocusView.requestFocus() }
        updateGatedButtons()
        updateSlotStatus()
        if (!permissionsIntroShown && (!hasStoragePermission() || !hasInstallPermission())) {
            permissionsIntroShown = true
            openPermissionsScreen()
        }
    }

    private fun magiskInfo() = try {
        packageManager.getPackageInfo("com.topjohnwu.magisk", 0)
    } catch (e: Exception) {
        null
    }

    private fun isMagiskInstalled() = magiskInfo() != null && resolveMagiskboot() != null

    /** Shows whether Magisk (which all patch tools depend on) is installed, and its version. */
    private fun updateMagiskStatus() {
        val tv = findViewById<TextView>(R.id.magiskStatus)
        val info = magiskInfo()
        when {
            info == null -> {
                tv.text = getString(R.string.status_magisk_missing)
                tv.setTextColor(resources.getColor(R.color.warning, theme))
            }
            resolveMagiskboot() == null -> {
                tv.text = getString(R.string.status_magisk_no_boot, info.versionName)
                tv.setTextColor(resources.getColor(R.color.warning, theme))
            }
            else -> {
                tv.text = getString(R.string.status_magisk_ok, info.versionName)
                tv.setTextColor(resources.getColor(R.color.accent, theme))
            }
        }
    }

    /** Active A/B boot slot suffix ("_a" / "_b") or null if the firmware does not expose it. */
    private fun activeSlotSuffix(): String? {
        val v = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.boot.slot_suffix", "") as? String
        } catch (e: Exception) {
            null
        } ?: try {
            ProcessBuilder("/system/bin/getprop", "ro.boot.slot_suffix").redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            null
        }
        return v?.trim()?.lowercase()?.takeIf { it == "_a" || it == "_b" }
    }

    /** Shows which boot slot the TV is running from: that is the image that must be patched and flashed. */
    private fun updateSlotStatus() {
        val tv = findViewById<TextView>(R.id.slotStatus)
        val slot = activeSlotSuffix()
        if (slot == null) {
            tv.text = getString(R.string.status_slot_unknown)
            tv.setTextColor(resources.getColor(R.color.warning, theme))
        } else {
            tv.text = getString(R.string.status_slot, slot.substring(1).uppercase())
            tv.setTextColor(resources.getColor(R.color.accent, theme))
        }
    }

    /** Runs [action] only if Magisk is installed; otherwise tells the user to install it. */
    private fun withMagisk(action: () -> Unit) {
        if (isMagiskInstalled()) action()
        else appendLog(getString(R.string.msg_need_magisk))
    }

    private fun hasStoragePermission() = Environment.isExternalStorageManager()
    private fun hasInstallPermission() = packageManager.canRequestPackageInstalls()

    /** Runs [action] if the storage permission is granted, otherwise opens the explainer screen instead. */
    private fun withStoragePermission(action: () -> Unit) {
        if (hasStoragePermission()) action() else openPermissionsScreen()
    }

    /** Runs [action] if the install permission is granted, otherwise opens the explainer screen instead. */
    private fun withInstallPermission(action: () -> Unit) {
        if (hasInstallPermission()) action() else openPermissionsScreen()
    }

    private fun openPermissionsScreen() {
        startActivity(Intent(this, PermissionsActivity::class.java))
    }

    /**
     * Dims buttons whose permission is missing and shows a "Требуется
     * разрешение" caption under their section, without actually disabling
     * them - a tap on a dimmed button still works, it just opens the
     * permissions screen instead of running the action (see
     * withStoragePermission/withInstallPermission above).
     */
    private fun updateGatedButtons() {
        val hasStorage = hasStoragePermission()
        val hasMagisk = isMagiskInstalled()
        setGated(listOf(R.id.btnFullPatch), R.id.captionFullPatch, hasStorage, hasMagisk)
        setGated(listOf(R.id.btnPickInput), R.id.captionFullPatch, hasStorage, hasMagisk)
        updateMagiskStatus()

        val hasInstall = hasInstallPermission()
        setGated(listOf(R.id.btnInstallMagisk), R.id.captionInstallMagisk, hasInstall)
    }

    private fun setGated(buttonIds: List<Int>, captionId: Int, granted: Boolean, magiskOk: Boolean = true) {
        for (id in buttonIds) {
            TvFocus.refreshAlpha(findViewById(id), !granted || !magiskOk)
        }
        findViewById<TextView>(captionId).visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun launchPicker(target: String) {
        pendingPickTarget = target
        val intent = Intent(this, FilePickerActivity::class.java)
            .putExtra(FilePickerActivity.EXTRA_START_DIR, Environment.getExternalStorageDirectory().absolutePath)
        filePicker.launch(intent)
    }

    /**
     * ACTION_DELETE is normally handled by the system package installer's
     * own confirmation UI, but this TV firmware's launcher may not resolve
     * it; fall back to the app-details settings screen (which always has
     * an "Uninstall" button) instead of silently doing nothing.
     */
    private fun uninstallSelf() {
        val uri = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            appendLog(getString(R.string.uninstall_no_dialog))
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                appendLog(getString(R.string.uninstall_failed, e2.message))
            }
        }
    }

    /**
     * Opens the factory/engineering menu directly at the given PageIndex,
     * same as:
     *   adb shell am start -W -n com.homwee.factory/.FactoryActivity --ei PageIndex <index>
     */
    private fun openFactoryPage(index: Int, hint: String? = null) {
        val intent = Intent().apply {
            component = ComponentName("com.homwee.factory", "com.homwee.factory.FactoryActivity")
            putExtra("PageIndex", index)
        }
        try {
            startActivity(intent)
            appendLog(getString(R.string.factory_opening, index) + (hint?.let { getString(R.string.factory_hint_suffix, it) } ?: ""))
            if (hint != null) Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            appendLog(getString(R.string.factory_not_found))
        } catch (e: SecurityException) {
            appendLog(getString(R.string.factory_denied, e.message))
        }
    }

    /** Opens the engineering menu at "Design Mode -> Uart"; the switch is flipped by hand. */
    private fun openUartMenu() {
        openFactoryPage(4, "Design Mode -> Uart -> On")
    }

    private val logBuf = android.text.SpannableStringBuilder()

    private fun appendLog(msg: String) {
        logBuf.append(msg).append("\n\n")
        log.text = logBuf
        scrollLogToEnd()
    }

    /** Shows a transient last line (download progress) that the next real log entry replaces. */
    private fun showProgress(msg: String) {
        runOnUiThread {
            log.text = android.text.SpannableStringBuilder(logBuf).append(msg)
            scrollLogToEnd()
        }
    }

    // scrollTo (not fullScroll) so the log never takes focus away from the buttons
    private fun scrollLogToEnd() {
        logScroll.post { logScroll.scrollTo(0, log.bottom) }
    }

    /** Big, bold, amber block at the end of the log: cannot be missed. */
    private fun appendWarning(msg: String) {
        val bar = "!".repeat(24)
        val start = logBuf.length
        logBuf.append(bar).append("
").append(msg.uppercase()).append("
").append(bar)
        val end = logBuf.length
        logBuf.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, 0)
        logBuf.setSpan(android.text.style.ForegroundColorSpan(resources.getColor(R.color.warning, theme)), start, end, 0)
        logBuf.setSpan(android.text.style.RelativeSizeSpan(1.25f), start, end, 0)
        logBuf.append("

")
        log.text = logBuf
        scrollLogToEnd()
    }

    /** After a patch: warns (last in the log) if the image is named for the other slot than the active one. */
    private fun logSlotMismatch(file: File) {
        val slot = activeSlotSuffix()?.substring(1)?.uppercase() ?: return
        val picked = Regex("BOOT_([AB])", RegexOption.IGNORE_CASE).find(file.name)
            ?.groupValues?.get(1)?.uppercase() ?: return
        if (picked != slot) runOnUiThread { appendWarning(getString(R.string.warn_slot_mismatch, picked, slot)) }
    }

    private fun log(msg: String) {
        runOnUiThread { appendLog(msg) }
    }

    // ---------------------------------------------------------------------
    // Downloading and installing Magisk
    // ---------------------------------------------------------------------

    private fun downloadAndInstall(repo: String, label: String) {
        runBusy {
            log(getString(R.string.dl_searching, label))
            val (apkUrl, name, tag) = fetchLatestApkUrl(repo)
            val installed = magiskInfo()?.versionName
            if (installed != null && installed == tag.removePrefix("v")) {
                log(getString(R.string.dl_up_to_date, installed, tag))
                return@runBusy
            }
            if (installed != null) log(getString(R.string.dl_update_available, installed, tag))
            val dest = File(cacheDir, name)
            // The file name carries the version, so an existing valid APK is the current release.
            val cached = dest.isFile && dest.length() > 0 &&
                packageManager.getPackageArchiveInfo(dest.absolutePath, 0) != null
            if (cached) {
                log(getString(R.string.dl_cached, label, name, dest.length()))
            } else {
                log(getString(R.string.dl_found, label, name))
                // Download to a temp file first so an interrupted download is never mistaken for a cached APK.
                val part = File(cacheDir, "$name.part")
                downloadFile(apkUrl, part)
                dest.delete()
                if (!part.renameTo(dest)) throw IllegalStateException(getString(R.string.err_save, name))
                log(getString(R.string.dl_done, label, dest.length()))
            }
            setProgress(-1, getString(R.string.progress_install))
            installApk(dest)
        }
    }

    /**
     * Finds the latest release APK without api.github.com (its anonymous rate
     * limit - HTTP 403 - is shared per IP, easy to hit): /releases/latest
     * redirects to /releases/tag/<tag>, and the APK has the stable name
     * "Magisk-<tag>.apk" under /releases/download/<tag>/. The expanded-assets
     * HTML is tried first for the exact file name; the built name is the fallback.
     */
    private fun fetchLatestApkUrl(repo: String): Triple<String, String, String> {
        val conn = URL("https://github.com/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "RootMate")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val location = conn.getHeaderField("Location")
            ?: throw IllegalStateException(getString(R.string.err_gh_no_redirect, conn.responseCode))
        val tag = location.substringAfterLast("/tag/", "").substringBefore('?')
        if (tag.isEmpty()) throw IllegalStateException(getString(R.string.err_gh_no_version, location))
        conn.disconnect()

        try {
            val html = URL("https://github.com/$repo/releases/expanded_assets/$tag").openConnection()
                .apply {
                    setRequestProperty("User-Agent", "RootMate")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                .getInputStream().bufferedReader().use { it.readText() }
            val hrefs = Regex("href=\"([^\"]+\\.apk)\"").findAll(html).map { it.groupValues[1] }.toList()
            val best = hrefs.firstOrNull { it.substringAfterLast('/').startsWith("Magisk", true) && !it.contains("stub", true) }
                ?: hrefs.firstOrNull()
            if (best != null) {
                val url = if (best.startsWith("http")) best else "https://github.com$best"
                return Triple(url, url.substringAfterLast('/'), tag)
            }
        } catch (e: Exception) {
            log(getString(R.string.dl_no_listing, e.message))
        }
        val name = "Magisk-$tag.apk"
        return Triple("https://github.com/$repo/releases/download/$tag/$name", name, tag)
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "RootMate")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (conn.responseCode != 200) throw IllegalStateException(getString(R.string.err_dl_http, conn.responseCode, conn.responseMessage))
        val total = conn.contentLengthLong
        if (total > 0 && cacheDir.usableSpace < total + 4L * 1024 * 1024) {
            throw IllegalStateException(getString(R.string.err_dl_space, total / 1024 / 1024))
        }
        var done = 0L
        var lastShown = 0L
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 300) {
                        lastShown = now
                        val mb = done / 1024 / 1024
                        showProgress(if (total > 0) getString(R.string.dl_progress, mb, total / 1024 / 1024, done * 100 / total) else getString(R.string.dl_progress_unknown, mb))
                        setProgress(if (total > 0) (done * 100 / total).toInt() else -1, getString(R.string.progress_downloading, mb))
                    }
                }
            }
        }
        if (total > 0 && done != total) {
            dest.delete()
            throw IllegalStateException(getString(R.string.err_dl_incomplete, done, total))
        }
    }

    /**
     * Installs through PackageInstaller (works even when the TV firmware has no
     * activity that handles ACTION_VIEW for APKs); falls back to ACTION_VIEW.
     */
    private fun installApk(file: File) {
        if (!hasInstallPermission()) {
            openPermissionsScreen()
            return
        }
        try {
            val installer = packageManager.packageInstaller
            val action = "$packageName.INSTALL_RESULT"
            installReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: Intent) {
                    val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
                    when (status) {
                        android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                            log(getString(R.string.install_ok))
                            unregisterReceiver(this)
                            installReceiver = null
                            updateGatedButtons()
                        }
                        else -> {
                            log(getString(R.string.install_failed, status, intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)))
                            unregisterReceiver(this)
                            installReceiver = null
                        }
                    }
                }
            }
            installReceiver = receiver
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, android.content.IntentFilter(action), RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, android.content.IntentFilter(action))
            }
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("magisk.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val pi = android.app.PendingIntent.getBroadcast(
                    this, id, Intent(action).setPackage(packageName),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                session.commit(pi.intentSender)
            }
            log(getString(R.string.install_started))
        } catch (e: Throwable) {
            log(getString(R.string.install_fallback, e.javaClass.simpleName, e.message))
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Throwable) {
                log(getString(R.string.install_unavailable, e2.javaClass.simpleName, e2.message))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Manual fix: an image already patched by Magisk itself (pick -> patch -> save next to it)
    // ---------------------------------------------------------------------

    private fun resolveMagiskboot(): File? {
        val info = try {
            packageManager.getApplicationInfo("com.topjohnwu.magisk", 0)
        } catch (e: Exception) {
            return null
        }
        val dir = info.nativeLibraryDir ?: return null
        val candidate = File(dir, "libmagiskboot.so")
        return if (candidate.exists()) candidate else null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** Copies [src] next to the original through a .part file, so a half-written image never has the final name. */
    private fun saveAtomically(src: File, dest: File) {
        val part = File(dest.parentFile, dest.name + ".part")
        src.copyTo(part, overwrite = true)
        dest.delete()
        if (!part.renameTo(dest)) throw IllegalStateException(getString(R.string.err_save, dest.absolutePath))
    }

    /** Fails early (before any heavy work) if the result can't be stored next to the input or work space is short. */
    private fun checkSpace(inputFile: File) {
        val size = inputFile.length()
        val dir = inputFile.parentFile ?: throw IllegalStateException(getString(R.string.err_no_parent))
        if (!dir.canWrite()) {
            throw IllegalStateException(getString(R.string.err_not_writable, dir.absolutePath))
        }
        if (dir.usableSpace < size + 8L * 1024 * 1024) {
            throw IllegalStateException(getString(R.string.err_low_space, size / 1024 / 1024 + 8, dir.usableSpace / 1024 / 1024))
        }
        if (filesDir.usableSpace < size * 4) {
            throw IllegalStateException(getString(R.string.err_low_internal, size * 4 / 1024 / 1024))
        }
    }

    /** name.img, then name_2.img, name_3.img... - an earlier result is never overwritten. */
    private fun uniqueDest(dir: File, base: String): File {
        var f = File(dir, "$base.img")
        var i = 2
        while (f.exists()) {
            f = File(dir, "${base}_$i.img")
            i++
        }
        return f
    }

    private fun patch(inputFile: File) {
        val work = File(filesDir, "work").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            patchIn(inputFile, work)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun patchIn(inputFile: File, work: File) {
        checkSpace(inputFile)

        step(5, getString(R.string.step_copy_input))
        val workInput = File(work, "input.img")
        inputFile.copyTo(workInput, overwrite = true)
        log(getString(R.string.info_input_size, workInput.length()))

        step(15, getString(R.string.step_find_magiskboot))
        val magiskboot = resolveMagiskboot()
            ?: throw IllegalStateException(getString(R.string.err_no_magiskboot))
        log("magiskboot: ${magiskboot.absolutePath}")

        applySkipInitramfsFix(magiskboot, work, "input.img", "output.img", 20, 90)

        val outputFile = File(work, "output.img")
        log(getString(R.string.info_output_size, outputFile.length(), workInput.length()))

        val dest = uniqueDest(inputFile.parentFile!!, "${inputFile.nameWithoutExtension}_skipfix")
        step(95, getString(R.string.step_saving))
        saveAtomically(outputFile, dest)
        log(getString(R.string.done_saved, dest.absolutePath))
        logSlotMismatch(inputFile)
    }

    /**
     * unpack -> extract init from the ramdisk cpio -> replace skip_initramfs
     * with skip_initramfz (same length, byte patch) -> add it back -> repack.
     * Shared by the manual two-step flow and the full one-click patch.
     */
    private fun applySkipInitramfsFix(
        magiskboot: File, work: File, inputName: String, outputName: String, p0: Int, p1: Int
    ) {
        fun stage(i: Int, text: String) = setProgress(p0 + (p1 - p0) * i / 6, text)
        fun run(vararg cmd: String): String {
            val pb = ProcessBuilder(*cmd)
            pb.directory(work)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) {
                throw RuntimeException(getString(R.string.err_cmd_failed, cmd.joinToString(" "), code, out))
            }
            return out
        }

        stage(0, getString(R.string.stage_unpack))
        log(run(magiskboot.absolutePath, "unpack", inputName))

        val ramdisk = File(work, "ramdisk.cpio")
        if (!ramdisk.exists()) {
            throw IllegalStateException(getString(R.string.err_no_ramdisk))
        }

        stage(1, getString(R.string.stage_extract_init))
        run(magiskboot.absolutePath, "cpio", "ramdisk.cpio", "extract init init_bin")

        stage(2, getString(R.string.stage_replace))
        val initBin = File(work, "init_bin")
        val bytes = initBin.readBytes()
        val needle = "skip_initramfs".toByteArray(Charsets.US_ASCII)
        val replacement = "skip_initramfz".toByteArray(Charsets.US_ASCII)
        var count = 0
        var idx = 0
        while (true) {
            idx = indexOf(bytes, needle, idx)
            if (idx < 0) break
            for (i in needle.indices) bytes[idx + i] = replacement[i]
            count++
            idx += needle.size
        }
        if (count == 0) {
            // magiskboot unpacks any boot image without checking who patched it, so this is the real check:
            // only a Magisk-patched ramdisk has magiskinit (with this string) as /init.
            run(magiskboot.absolutePath, "cleanup")
            throw IllegalStateException(getString(R.string.err_not_magisk_image))
        }
        log(getString(R.string.info_replaced, count))
        initBin.writeBytes(bytes)

        stage(3, getString(R.string.stage_put_back))
        run(magiskboot.absolutePath, "cpio", "ramdisk.cpio", "add 0750 init init_bin")
        stage(4, getString(R.string.stage_repack))
        run(magiskboot.absolutePath, "repack", inputName, outputName)
        stage(5, getString(R.string.stage_cleanup))
        run(magiskboot.absolutePath, "cleanup")
    }

    // ---------------------------------------------------------------------
    // Full patch: straight from an original, unpatched boot image
    // ---------------------------------------------------------------------

    /**
     * Reuses Magisk's own assets/boot_patch.sh + assets/util_functions.sh
     * and the magisk/magiskinit/magiskboot/init-ld binaries, extracted from
     * the installed Magisk app's own APK (nothing copied into this repo),
     * run without LEGACYSAR (it was a no-op on this device: its kernel
     * hex-patch target, the string "skip_initramfs", isn't in the kernel).
     * The same magiskinit fix as the manual flow is applied automatically
     * right after, before saving - still a single click, no Magisk UI.
     */
    private fun fullPatch(path: String) {
        val work = File(filesDir, "fullpatch").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            fullPatchIn(File(path), work)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun fullPatchIn(inputFile: File, work: File) {
        checkSpace(inputFile)

        step(5, getString(R.string.step_copy_original))
        val bootImg = File(work, "boot.img")
        inputFile.copyTo(bootImg, overwrite = true)
        log(getString(R.string.info_image_size, bootImg.length()))

        val magiskInfo = try {
            packageManager.getApplicationInfo("com.topjohnwu.magisk", 0)
        } catch (e: Exception) {
            throw IllegalStateException(getString(R.string.err_magisk_missing))
        }

        step(10, getString(R.string.step_extract_script))
        ZipFile(magiskInfo.sourceDir).use { zip ->
            for (name in listOf("boot_patch.sh", "util_functions.sh")) {
                val entry = zip.getEntry("assets/$name")
                    ?: throw IllegalStateException(getString(R.string.err_asset_missing, name))
                zip.getInputStream(entry).use { input ->
                    File(work, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        step(15, getString(R.string.step_extract_bins))
        val nativeDir = magiskInfo.nativeLibraryDir
            ?: throw IllegalStateException(getString(R.string.err_no_nativedir))
        val binaries = mapOf(
            "libmagisk.so" to "magisk",
            "libmagiskinit.so" to "magiskinit",
            "libmagiskboot.so" to "magiskboot",
            "libinit-ld.so" to "init-ld"
        )
        for ((lib, target) in binaries) {
            val src = File(nativeDir, lib)
            if (!src.exists()) throw IllegalStateException(getString(R.string.err_lib_missing, lib, nativeDir))
            // Symlink, not copy: Android blocks executing a binary freshly
            // written into this app's own data dir (W^X), even with chmod +x.
            // Magisk's own nativeLibraryDir copy is package-manager-managed
            // and exempt, so point at it instead of duplicating the file.
            val link = File(work, target)
            link.delete()
            Os.symlink(src.absolutePath, link.absolutePath)
        }
        File(work, "boot_patch.sh").setExecutable(true)

        log(getString(R.string.step_run_bootpatch))
        setProgress(-1, getString(R.string.progress_bootpatch))
        val pb = ProcessBuilder("/system/bin/sh", "boot_patch.sh", "boot.img")
        pb.directory(work)
        // util_functions.sh tries to detect "booted system" via `ps | grep zygote`,
        // which doesn't see other UIDs' processes from an ordinary app - set it
        // explicitly, same as what Magisk's own app does when it runs this script.
        pb.environment()["BOOTMODE"] = "true"
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        log(out)
        if (code != 0) {
            throw RuntimeException(getString(R.string.err_bootpatch_failed, code))
        }

        val legacysarOutput = File(work, "new-boot.img")
        if (!legacysarOutput.exists()) {
            throw IllegalStateException(getString(R.string.err_no_newboot))
        }
        log(getString(R.string.info_after_bootpatch, legacysarOutput.length()))
        setProgress(60, getString(R.string.progress_bootpatch_done))

        log(getString(R.string.step_apply_fix))
        val magiskboot = File(work, "magiskboot")
        applySkipInitramfsFix(magiskboot, work, "new-boot.img", "final-boot.img", 60, 90)

        val finalOutput = File(work, "final-boot.img")
        log(getString(R.string.info_final_size, finalOutput.length(), bootImg.length()))

        val dest = uniqueDest(inputFile.parentFile!!, "${inputFile.nameWithoutExtension}_magisk_patched")
        step(95, getString(R.string.step_saving))
        saveAtomically(finalOutput, dest)
        log(getString(R.string.done_saved, dest.absolutePath))
        logSlotMismatch(inputFile)
    }
}
