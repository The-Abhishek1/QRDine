package com.xcloak.qrdine.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xcloak.qrdine.QrDineApp

@Composable
fun AppRoot() {
    if (!QrDineApp.configured) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(
                    "Missing Supabase config.\nCopy local.properties.example to local.properties, fill in SUPABASE_URL and SUPABASE_ANON_KEY, then rebuild.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    val root: RootViewModel = viewModel()
    val state by root.state.collectAsStateWithLifecycle()
    when (val s = state) {
        RootState.Loading -> Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() } }
        RootState.SignedOut -> AuthScreen()
        RootState.NeedsRestaurant -> SetupRestaurantScreen()
        RootState.Platform -> PlatformApp()
        is RootState.Blocked -> BlockedScreen(s.status)
        is RootState.Ready -> MainScreen(s.restaurantId, s.role)
    }
}
