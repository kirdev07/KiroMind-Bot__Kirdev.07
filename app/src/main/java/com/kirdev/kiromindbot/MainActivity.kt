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
}
