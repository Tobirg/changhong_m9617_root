package com.tobirg.rootmate

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Minimal in-app file browser, no SAF / no third-party DocumentsProvider
 * (Fluffy) involved: plain java.io.File access over the whole shared
 * storage, requires MANAGE_EXTERNAL_STORAGE (checked by the caller before
 * launching this). Returns the picked file's absolute path as EXTRA_PATH.
 */
class FilePickerActivity : Activity() {

    companion object {
        const val EXTRA_START_DIR = "start_dir"
        const val EXTRA_PATH = "path"
    }

    /** null = the list of storage volumes (internal storage, USB drives, SD cards). */
    private var currentDir: File? = null
    private var roots: List<Pair<String, File>> = emptyList()
    private lateinit var listView: ListView
    private lateinit var pathLabel: TextView
    private var entries: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always open on the volume list so a USB drive is one click away.
        currentDir = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        pathLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 16)
            textSize = 13f
        }
        root.addView(pathLabel)

        listView = ListView(this).apply {
            setBackgroundColor(Color.BLACK)
            divider = null
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            choiceMode = AbsListView.CHOICE_MODE_SINGLE
            selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(Color.parseColor("#2E7D32")))
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor("#2E7D32")))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ ->
            val dir = currentDir
            if (dir == null) {
                roots.getOrNull(position)?.let { currentDir = it.second; refresh() }
                return@setOnItemClickListener
            }
            if (position == 0) {
                goUp()
                return@setOnItemClickListener
            }
            val file = entries.getOrNull(position - 1) ?: return@setOnItemClickListener
            if (file.isDirectory) {
                currentDir = file
                refresh()
            } else {
                val data = android.content.Intent().putExtra(EXTRA_PATH, file.absolutePath)
                setResult(RESULT_OK, data)
                finish()
            }
        }

        refresh()
    }

    private fun goUp() {
        val dir = currentDir ?: return
        val parent = dir.parentFile
        val isRoot = roots.any { it.second.canonicalPath == dir.canonicalPath }
        currentDir = if (isRoot || parent == null) null else parent
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentDir != null) goUp() else super.onBackPressed()
    }

    /** Internal storage plus every mounted removable volume (USB flash drives, SD cards). */
    private fun findRoots(): List<Pair<String, File>> {
        val result = LinkedHashMap<String, Pair<String, File>>()
        fun add(label: String, f: File) {
            if (!f.isDirectory || f.listFiles() == null) return
            val key = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
            if (!result.containsKey(key)) result[key] = label to f
        }

        add(getString(R.string.picker_internal), Environment.getExternalStorageDirectory())

        // Volumes known to the system (labels like "Kingston", "USB drive").
        try {
            val sm = getSystemService(STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                val dir: File = (if (android.os.Build.VERSION.SDK_INT >= 30) {
                    v.directory
                } else {
                    @Suppress("DiscouragedPrivateApi")
                    (v.javaClass.getMethod("getPath").invoke(v) as? String)?.let { File(it) }
                }) ?: continue
                if (v.isPrimary) continue
                add(getString(R.string.picker_drive, v.getDescription(this)), dir)
            }
        } catch (_: Throwable) {
        }

        // Fallback for firmwares that don't report USB drives as storage volumes.
        for (base in listOf("/storage", "/mnt/media_rw", "/mnt/usb", "/mnt")) {
            File(base).listFiles()?.forEach { f ->
                val n = f.name
                if (n == "self" || n == "emulated" || n == "sdcard0" || n == "runtime" || n == "user" ||
                    n == "asec" || n == "obb" || n == "secure" || n == "shell" || n == "sdcard"
                ) return@forEach
                add(getString(R.string.picker_drive, n), f)
            }
        }
        return result.values.toList()
    }

    private fun refresh() {
        val dir = currentDir
        val rows = mutableListOf<String>()
        if (dir == null) {
            roots = findRoots()
            entries = emptyList()
            pathLabel.text = getString(R.string.picker_choose_drive)
            rows.addAll(roots.map { (label, f) -> "💾 $label  (${f.absolutePath})" })
            if (rows.isEmpty()) rows.add(getString(R.string.picker_no_drives))
        } else {
            pathLabel.text = dir.absolutePath
            // Only folders and boot images (.img); hidden entries are skipped.
            entries = (dir.listFiles()?.toList() ?: emptyList())
                .filter { !it.name.startsWith(".") && (it.isDirectory || it.name.endsWith(".img", ignoreCase = true)) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            rows.add(getString(R.string.picker_up))
            rows.addAll(
                entries.map { f ->
                    if (f.isDirectory) "📁 ${f.name}" else "📄 ${f.name}  (${f.length() / 1024 / 1024} MB)"
                }
            )
        }

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.background = null
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.setPadding(32, 28, 32, 28)
                view.textSize = 15f
                view.gravity = Gravity.CENTER_VERTICAL
                return view
            }
        }
        listView.adapter = adapter
        listView.post {
            listView.requestFocus()
            if (rows.isNotEmpty()) listView.setSelection(0)
        }
    }
}
