package com.kirdev.kiromindbot.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kirdev.kiromindbot.core.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After this app is updated, starts the bot again if the user had left it on.
 * MY_PACKAGE_REPLACED is one of the broadcasts allowed to start a foreground service.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(8_000) { AppGraph.get(context).restoreBot("после обновления") }
            } finally {
                pending.finish()
            }
        }
    }
}
