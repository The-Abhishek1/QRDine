package com.xcloak.qrdine.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TableRestaurant
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
        Icon(
            Icons.Default.Restaurant,
            contentDescription = "QR Dine",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size((size * 0.54f).dp),
        )
    }
}

@Composable
fun AuthScreen(vm: AuthViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var signUp by rememberSaveable { mutableStateOf(false) }
    var forgotPassword by rememberSaveable { mutableStateOf(false) }
    var show by rememberSaveable { mutableStateOf(false) }
    var customerAccount by rememberSaveable { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Top),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(54)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("QR Dine", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Restaurant tools and customer ordering", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    when {
                        forgotPassword -> "Reset your password"
                        signUp && customerAccount -> "Create your customer account"
                        signUp -> "Get your restaurant online"
                        customerAccount -> "Order from your favourites"
                        else -> "Welcome back"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        forgotPassword -> "We’ll send a secure reset link to the email on your account."
                        signUp && customerAccount -> "Create an account to explore restaurants and place orders."
                        signUp -> "Create an owner account, then add your restaurant details for review."
                        customerAccount -> "Sign in to browse restaurants and track your orders."
                        else -> "Sign in to manage your restaurant, menu, and orders."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }

            if (!forgotPassword) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose how you use QR Dine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Card(
                            onClick = { customerAccount = false; vm.clearMessages() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = if (!customerAccount) colors.primaryContainer else colors.surfaceContainerLow),
                            border = BorderStroke(1.dp, if (!customerAccount) colors.primary else colors.outlineVariant),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Restaurant, null, tint = if (!customerAccount) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(23.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Restaurant", style = MaterialTheme.typography.labelLarge)
                                    Text("Owner or staff", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                        Card(
                            onClick = { customerAccount = true; vm.clearMessages() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = if (customerAccount) colors.primaryContainer else colors.surfaceContainerLow),
                            border = BorderStroke(1.dp, if (customerAccount) colors.primary else colors.outlineVariant),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TableRestaurant, null, tint = if (customerAccount) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(23.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Customer", style = MaterialTheme.typography.labelLarge)
                                    Text("Browse and order", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                    if (!forgotPassword) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceContainerHigh)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(false to "Sign in", true to "Create account").forEach { (createAccount, label) ->
                                val selected = signUp == createAccount
                                Surface(
                                    onClick = { signUp = createAccount; vm.clearMessages() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(11.dp),
                                    color = if (selected) colors.surface else Color.Transparent,
                                    shadowElevation = if (selected) 1.dp else 0.dp,
                                ) {
                                    Text(
                                        label,
                                        Modifier.padding(vertical = 11.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        when {
                            forgotPassword -> "PASSWORD RESET"
                            signUp && customerAccount -> "NEW CUSTOMER ACCOUNT"
                            signUp -> "NEW RESTAURANT ACCOUNT"
                            customerAccount -> "CUSTOMER SIGN IN"
                            else -> "RESTAURANT SIGN IN"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email address") },
                        placeholder = { Text("you@example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp)
                    )
                    if (!forgotPassword) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(if (signUp) "Create a password" else "Password") },
                            supportingText = if (signUp) ({ Text("Use at least 8 characters.") }) else null,
                            singleLine = true,
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show") } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(15.dp)
                        )
                    }
                    Messages(s)
                    if (s.verificationPending) {
                        OutlinedButton(
                            onClick = { vm.resendConfirmation(email.trim()) },
                            enabled = !s.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Resend confirmation email")
                        }
                    }
                    Button(
                        onClick = {
                            if (forgotPassword) vm.requestPasswordReset(email.trim())
                            else vm.submit(email.trim(), password, signUp, customerAccount)
                        },
                        enabled = !s.loading,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        if (s.loading) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                when {
                                    forgotPassword -> "Email me a reset link"
                                    signUp && customerAccount -> "Create customer account"
                                    signUp -> "Create restaurant account"
                                    customerAccount -> "Sign in as customer"
                                    else -> "Sign in to restaurant"
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
            }
            if (forgotPassword) {
                TextButton(
                    onClick = { forgotPassword = false; vm.clearMessages() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Back to sign in") }
            } else if (!signUp) {
                TextButton(
                    onClick = { forgotPassword = true; vm.clearMessages() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Forgot password?") }
            } else {
                Text(
                    if (customerAccount) "Already have an account? Choose Sign in above."
                    else "After creating your account, add your restaurant details for review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { openLegalPage(context, "privacy") }) { Text("Privacy") }
                Text("·", color = colors.onSurfaceVariant)
                TextButton(onClick = { openLegalPage(context, "terms") }) { Text("Terms") }
            }
        }
    }
}

@Composable
fun PasswordRecoveryScreen(vm: AuthViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }

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
            Text("Reset your password", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            GlassCard(Modifier.fillMaxWidth()) {
                if (!s.recoveryComplete) {
                    Text("Choose a new password for your account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("New password (8+ characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Messages(s)
                    Button(
                        onClick = { vm.updateRecoveryPassword(password, confirmation) },
                        enabled = !s.loading,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (s.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text("Update password", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Messages(s)
                    Button(
                        onClick = vm::finishRecovery,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Continue to QR Dine") }
                }
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
            OnboardingStepsHero()
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Let’s set up your restaurant", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Add your details first. After review, we’ll guide you through menu, tables and QR codes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                    Text("YOUR RESTAURANT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("These details appear to guests on your digital menu. Your free trial starts after your application is approved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (s.loading) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                                Text("Continue", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                }

            TextButton(onClick = vm::signOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Sign out")
            }

        }
    }
}

@Composable
private fun OnboardingStepsHero() {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box {
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Restaurant, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(38.dp))
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).size(25.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.QrCode2, null, tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Your digital restaurant", style = MaterialTheme.typography.titleLarge)
                Text("A few simple steps to start taking QR orders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OnboardingStepIcon("Details", Icons.Default.Restaurant, active = true)
            OnboardingStepIcon("Menu", Icons.AutoMirrored.Filled.MenuBook, active = false)
            OnboardingStepIcon("Tables", Icons.Default.TableRestaurant, active = false)
            OnboardingStepIcon("QR", Icons.Default.QrCode2, active = false)
        }
    }
}

@Composable
private fun OnboardingStepIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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

            GlassCard(Modifier.fillMaxWidth()) {
                    Text("ACCOUNT STATUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
