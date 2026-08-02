package com.jobai.hunter.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jobai.hunter.ui.compose.JobHunterApp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // JobHunterApp ya aplica JobHunterTheme (y controla claro/oscuro
            // desde el panel lateral), por eso aqui no se envuelve otra vez.
            JobHunterApp(viewModel = viewModel)
        }
    }
}
