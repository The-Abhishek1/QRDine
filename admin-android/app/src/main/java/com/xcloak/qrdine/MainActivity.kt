package com.xcloak.qrdine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.xcloak.qrdine.QrDineApp
import io.github.jan.supabase.auth.handleDeeplinks
import com.xcloak.qrdine.ui.AppRoot
import com.xcloak.qrdine.ui.GlassBackdrop
import com.xcloak.qrdine.ui.QrDineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAuthLink(intent)
        setContent { QrDineTheme { GlassBackdrop { AppRoot() } } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthLink(intent)
    }

    private fun handleAuthLink(intent: android.content.Intent?) {
        if (!QrDineApp.configured || intent?.data?.scheme != "qrdine" || intent.data?.host != "auth") return
        val fragment = intent.data?.fragment.orEmpty()
        val type = android.net.Uri.parse("https://qrdine.invalid/?$fragment").getQueryParameter("type")
        if (type == "recovery") QrDineApp.passwordRecoveryPending.value = true
        QrDineApp.container.supabase.handleDeeplinks(intent)
    }
}
