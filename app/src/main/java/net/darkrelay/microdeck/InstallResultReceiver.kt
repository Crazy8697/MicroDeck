package net.darkrelay.microdeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Gets the PackageInstaller session's result (not exported: only Android's callback reaches it). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_STATUS) return
        try {
            UpdateInstaller.onStatus(context, intent)
        } catch (t: Throwable) {
            Log.w("MicroDeck", "install status", t)
        }
    }
}
