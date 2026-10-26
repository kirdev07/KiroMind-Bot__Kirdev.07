package com.kirdev.kiromindbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kirdev.kiromindbot.core.designsystem.KiroMindTheme
import com.kirdev.kiromindbot.core.navigation.KiroMindApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { KiroMindTheme { KiroMindApp() } }
    }

    /**
     * On MIUI the window stops drawing after returning from the system file picker until it is
     * touched, so results (e.g. "Сохранено") stayed invisible. Asking for a redraw when focus
     * comes back, and shortly after for state that settles a moment later, wakes it up.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val decor = window.decorView
        decor.postInvalidateOnAnimation()
        decor.postDelayed({ decor.invalidate(); decor.requestLayout() }, 150)
        decor.postDelayed({ decor.invalidate(); decor.requestLayout() }, 600)
    }
}
