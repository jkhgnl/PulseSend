package com.example.firstapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.firstapp.ui.theme.FirstAPPTheme
import com.example.firstapp.ui.PulseSendApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FirstAPPTheme {
                PulseSendApp()
            }
        }
    }
}
