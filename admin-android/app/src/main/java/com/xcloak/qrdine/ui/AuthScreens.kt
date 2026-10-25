package com.xcloak.qrdine.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xcloak.qrdine.QrDineApp
import com.xcloak.qrdine.data.HotelStatus
import kotlinx.coroutines.delay

@Composable
private fun Messages(state: AuthUiState) {
    state.error?.let {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Text(
                it,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
    state.info?.let {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                it,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/** The app's mark: a rounded teal tile with a QR-style glyph. */
@Composable
fun BrandMark(size: Int = 72) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.5).dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "QR",
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Black,
            fontSize = (size / 2.5).sp
        )
    }
}

@Composable
fun AuthScreen(vm: AuthViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var signUp by rememberSaveable { mutableStateOf(false) }
    var show by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(80)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("QR Dine Admin", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (signUp) "Create your account to get started" else "Sign in to manage your restaurant",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (8+ characters)") },
                        singleLine = true,
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show") } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Messages(s)
                    Button(
                        onClick = { vm.submit(email, password, signUp) },
                        enabled = !s.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (s.loading) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(if (signUp) "Create account" else "Sign in", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                }
            }

            TextButton(
                onClick = { signUp = !signUp; vm.clearMessages() },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (signUp) "Already have an account? Sign in" else "New restaurant? Create an account")
            }
        }
    }
}

/** Step two for a new hotel: tell us about the restaurant. It then waits for the platform admin's approval. */
@Composable
fun SetupRestaurantScreen(vm: AuthViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(64)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Setup Restaurant", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Submit your hotel details for review. Your free trial starts upon approval.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(120) },
                        label = { Text("Restaurant name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20) },
                        label = { Text("Phone") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.take(200) },
                        label = { Text("Address") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Messages(s)
                    Button(
                        onClick = { vm.createRestaurant(name, phone, address) },
                        enabled = !s.loading && name.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (s.loading) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Submit application", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                }
            }

            TextButton(onClick = vm::signOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Sign out")
            }
        }
    }
}

/** Shown while a hotel is waiting for approval, was rejected, or is on hold. It checks by itself and moves on once allowed. */
@Composable
fun BlockedScreen(initial: String, vm: AuthViewModel = viewModel()) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<HotelStatus?>(null) }
    var checking by remember { mutableStateOf(false) }
    val state = status?.state ?: initial

    suspend fun check() {
        checking = true
        val st = QrDineApp.container.owner.hotelStatus()
        if (st != null) {
            status = st
            if (st.state !in setOf("pending", "rejected", "hold", "disabled")) runCatching { QrDineApp.container.auth.refresh() }
        }
        checking = false
    }
    LaunchedEffect(Unit) { while (true) { check(); delay(10_000) } }

    val (title, body) = when (state) {
        "rejected" -> "Application not approved" to "Your application was not approved this time."
        "hold" -> "Your account is on hold" to "Ordering is paused until the hold is lifted."
        "disabled" -> "Your login is turned off" to "Ask the restaurant owner to turn your login back on."
        else -> "Application submitted" to "Your hotel is waiting for admin approval. You can start taking orders as soon as it is approved. This screen updates automatically."
    }
    val support = status?.support

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(64)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                status?.hotelName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                    status?.note?.takeIf { it.isNotBlank() }?.let {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Text("Message from admin: $it", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                    if (support?.phone != null || support?.email != null) {
                        Text("Questions? Contact support.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            support.phone?.let { p ->
                                OutlinedButton({ context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                    Text("Call Support")
                                }
                            }
                            support.email?.let { e ->
                                OutlinedButton({ context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$e")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                    Text("Email Support")
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { vm.signOut() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors()
            ) {
                Text("Sign out")
            }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
