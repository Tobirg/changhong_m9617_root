package com.tobirg.rootmate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

/**
 * RootMate driven over adb by a program (an AI agent or a script) instead of the remote:
 *
 *   adb shell am broadcast -n com.tobirg.rootmate/.AgentReceiver -a com.tobirg.rootmate.AGENT --es cmd status
 *
 * The answer is one line of JSON in the broadcast result: `Broadcast completed: result=0, data="{...}"`
 * (result=0 means ok, 1 means an error; the JSON always has "ok" and, on an error, "error").
 * Commands are executed by MainActivity, which only has to be open (either mode); while commands come in it shows
 * no dialogs. See docs/agent.md for the commands and the whole flow. The receiver is protected by
 * android.permission.DUMP, which the adb shell holds and ordinary apps cannot get, so only adb (or root) can send.
 */
object Agent {

    const val ACTION = "com.tobirg.rootmate.AGENT"

    /** What executes the commands: the open MainActivity. */
    interface Host {
        /** Runs [cmd] with its extras [args]; called on a worker thread, may block a few seconds. */
        fun handle(cmd: String, args: Bundle): JSONObject
    }

    @Volatile
    var host: Host? = null

    fun error(message: String, extra: JSONObject? = null): JSONObject =
        (extra ?: JSONObject()).put("ok", false).put("error", message)
}

class AgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val cmd = intent.getStringExtra("cmd") ?: "status"
        val args = intent.extras ?: Bundle()
        Thread {
            val result = try {
                Agent.host?.handle(cmd, args) ?: Agent.error(
                    "RootMate is not open: adb shell am start -n com.tobirg.rootmate/.MainActivity"
                )
            } catch (e: Throwable) {
                Agent.error(e.message ?: e.javaClass.simpleName)
            }
            pending.resultCode = if (result.optBoolean("ok")) 0 else 1
            pending.resultData = result.toString()
            pending.finish()
        }.start()
    }
}
