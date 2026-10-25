package com.xcloak.qrdine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.xcloak.qrdine.ui.AppRoot
import com.xcloak.qrdine.ui.GlassBackdrop
import com.xcloak.qrdine.ui.QrDineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QrDineTheme { GlassBackdrop { AppRoot() } } }
    }
}
