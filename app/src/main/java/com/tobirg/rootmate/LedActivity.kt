package com.tobirg.rootmate

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.concurrent.Executors

/**
 * Power LED settings on top of the ledctl kernel module (Led.kt): install/update/remove the module, the LED mode in
 * standby and while the TV is on, brightness for the "on" modes. Every change is applied at once and kept for the
 * next boots. Root work runs on one background thread, in order.
 */
class LedActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var installBtn: MaterialButton
    private lateinit var removeBtn: MaterialButton
    private lateinit var sleepGroup: MaterialButtonToggleGroup
    private lateinit var onGroup: MaterialButtonToggleGroup
    private lateinit var sleepLevel: TextView
    private lateinit var onLevel: TextView
    private lateinit var sleepLevelRow: View
    private lateinit var onLevelRow: View

    /** button id -> module value */
    private val sleepModes = mapOf(R.id.ledSleepOff to 0, R.id.ledSleepOn to 1, R.id.ledSleepBlink to 2)
    private val onModes = mapOf(R.id.ledOnStock to -1, R.id.ledOnOff to 0, R.id.ledOnOn to 1, R.id.ledOnBlink to 2)
    private val levels = listOf(5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)

    private var state: Led.State? = null
    private val values = Led.DEFAULTS.toMutableMap()
    private var lastInstallError: String? = null
    /** true while the groups are being set from the module state, so their listeners do not write it back */
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_led)

        status = findViewById(R.id.ledStatus)
        installBtn = findViewById(R.id.ledInstall)
        removeBtn = findViewById(R.id.ledRemove)
        sleepGroup = findViewById(R.id.ledSleepGroup)
        onGroup = findViewById(R.id.ledOnGroup)
        sleepLevel = findViewById(R.id.ledSleepLevel)
        onLevel = findViewById(R.id.ledOnLevel)
        sleepLevelRow = findViewById(R.id.ledSleepLevelRow)
        onLevelRow = findViewById(R.id.ledOnLevelRow)

        installBtn.setOnClickListener { install() }
        removeBtn.setOnClickListener { confirmRemove() }
        findViewById<View>(R.id.ledDone).setOnClickListener { finish() }

        bindGroup(sleepGroup, sleepModes, "sleep_led")
        bindGroup(onGroup, onModes, "on_led")
        bindStepper(R.id.ledSleepMinus, R.id.ledSleepPlus, "sleep_level")
        bindStepper(R.id.ledOnMinus, R.id.ledOnPlus, "on_level")

        listOf(R.id.ledInstall, R.id.ledRemove, R.id.ledDone, R.id.ledSleepMinus, R.id.ledSleepPlus,
            R.id.ledOnMinus, R.id.ledOnPlus).forEach { TvFocus.apply(findViewById(it), 10f) }
        focusSegments(listOf(R.id.ledSleepOff, R.id.ledSleepOn, R.id.ledSleepBlink))
        focusSegments(listOf(R.id.ledOnStock, R.id.ledOnOff, R.id.ledOnOn, R.id.ledOnBlink))

        setControlsEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun focusSegments(ids: List<Int>) = ids.forEachIndexed { i, id ->
        val corners = when (i) {
            0 -> TvFocus.Corners.START
            ids.lastIndex -> TvFocus.Corners.END
            else -> TvFocus.Corners.NONE
        }
        TvFocus.apply(findViewById(id), corners = corners)
    }

    private fun bindGroup(group: MaterialButtonToggleGroup, modes: Map<Int, Int>, key: String) {
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updating) return@addOnButtonCheckedListener
            val v = modes[checkedId] ?: return@addOnButtonCheckedListener
            values[key] = v
            showLevels()
            apply(key, v)
        }
    }

    private fun bindStepper(minusId: Int, plusId: Int, key: String) {
        fun step(dir: Int) {
            val cur = values.getValue(key)
            val next = if (dir > 0) levels.firstOrNull { it > cur } ?: levels.last()
            else levels.lastOrNull { it < cur } ?: levels.first()
            if (next == cur) return
            values[key] = next
            showLevels()
            apply(key, next)
        }
        findViewById<View>(minusId).setOnClickListener { step(-1) }
        findViewById<View>(plusId).setOnClickListener { step(1) }
    }

    private fun apply(key: String, value: Int) {
        worker.execute {
            val ok = Led.set(key, value)
            if (!ok) runOnUiThread {
                Toast.makeText(this, R.string.led_set_failed, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun refresh() {
        status.setText(R.string.led_status_checking)
        status.setTextColor(getColor(R.color.text_secondary))
        worker.execute {
            val s = Led.read()
            runOnUiThread { if (!isDestroyed) show(s) }
        }
    }

    private fun show(s: Led.State) {
        state = s
        val (text, color) = when {
            !s.root -> getString(R.string.led_status_no_root) to R.color.warning
            !s.installed && lastInstallError != null -> getString(R.string.led_status_failed, lastInstallError) to R.color.danger
            !s.installed -> getString(R.string.led_status_not_installed) to R.color.warning
            !s.loaded -> getString(R.string.led_status_not_loaded, s.error ?: "?") to R.color.danger
            s.needsUpdate -> getString(R.string.led_status_update, s.installedVersion ?: "?") to R.color.warning
            else -> getString(R.string.led_status_ok, s.installedVersion ?: Led.BUNDLED_VERSION) to R.color.accent
        }
        status.text = text
        status.setTextColor(getColor(color))

        installBtn.visibility = if (s.root && (!s.installed || !s.loaded || s.needsUpdate)) View.VISIBLE else View.GONE
        installBtn.setText(
            when {
                !s.installed -> R.string.led_install
                s.needsUpdate -> R.string.led_update
                else -> R.string.led_reload
            }
        )
        removeBtn.visibility = if (s.root && s.installed) View.VISIBLE else View.GONE

        values.putAll(s.values)
        updating = true
        sleepModes.entries.firstOrNull { it.value == values["sleep_led"] }?.let { sleepGroup.check(it.key) }
        onModes.entries.firstOrNull { it.value == values["on_led"] }?.let { onGroup.check(it.key) }
        updating = false
        showLevels()
        setControlsEnabled(s.root && s.installed && s.loaded)
    }

    /** Brightness only means something for "on": blink keeps the stock (slow) PWM, off and stock ignore it. */
    private fun showLevels() {
        sleepLevel.text = getString(R.string.led_level, values.getValue("sleep_level"))
        onLevel.text = getString(R.string.led_level, values.getValue("on_level"))
        sleepLevelRow.visibility = if (values["sleep_led"] == 1) View.VISIBLE else View.GONE
        onLevelRow.visibility = if (values["on_led"] == 1) View.VISIBLE else View.GONE
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val ids = sleepModes.keys + onModes.keys +
            listOf(R.id.ledSleepMinus, R.id.ledSleepPlus, R.id.ledOnMinus, R.id.ledOnPlus)
        ids.forEach { findViewById<View>(it).isEnabled = enabled }
        sleepGroup.alpha = if (enabled) 1f else 0.5f
        onGroup.alpha = if (enabled) 1f else 0.5f
    }

    private fun install() {
        installBtn.isEnabled = false
        status.setText(R.string.led_status_installing)
        status.setTextColor(getColor(R.color.text_secondary))
        worker.execute {
            val err = Led.install(this)
            runOnUiThread {
                installBtn.isEnabled = true
                lastInstallError = err
                refresh()
            }
        }
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setMessage(R.string.led_remove_confirm)
            .setPositiveButton(R.string.led_remove_yes) { _, _ ->
                worker.execute {
                    val ok = Led.remove()
                    runOnUiThread {
                        if (!ok) Toast.makeText(this, R.string.led_set_failed, Toast.LENGTH_SHORT).show()
                        lastInstallError = null
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.led_cancel, null)
            .show()
    }
}
