/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.auramusic.app.LocalPlayerAwareWindowInsets
import com.auramusic.app.R
import com.auramusic.app.auth.GoogleAccountLogin
import com.auramusic.app.constants.AccountChannelHandleKey
import com.auramusic.app.constants.AccountEmailKey
import com.auramusic.app.constants.AccountNameKey
import com.auramusic.app.constants.InnerTubeCookieKey
import com.auramusic.app.ui.component.IconButton as AppIconButton
import com.auramusic.app.ui.utils.backToMain
import com.auramusic.app.utils.rememberPreference
import com.auramusic.innertube.YouTube
import com.auramusic.innertube.YouTubeEmailLogin
import kotlinx.coroutines.launch

/**
 * Sign-in, with the three ways in actually offered.
 *
 * The screen used to be nothing but the browser flow, so signing in meant typing an
 * email and a password into a web page rendered inside the app - slow, and it cannot
 * show a Google account already on the device. Now the account the user already has is
 * one tap, an email and password are typed straight into the app, and the browser flow
 * stays available for the accounts that need a second factor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    var accountName by rememberPreference(AccountNameKey, "")
    var accountEmail by rememberPreference(AccountEmailKey, "")
    var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")

    var showEmailDialog by remember { mutableStateOf(false) }
    var showBrowserSignIn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val accounts = remember { GoogleAccountLogin.deviceAccounts(context) }

    fun onSignedIn(cookie: String, name: String, email: String, handle: String) {
        YouTube.cookie = cookie
        innerTubeCookie = cookie
        accountName = name
        accountEmail = email
        accountChannelHandle = handle
        navController.navigateUp()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.login)) },
            navigationIcon = {
                AppIconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.login_choose_method),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Google accounts already on the device ---
            if (accounts.isNotEmpty()) {
                SignInOption(
                    icon = Icons.Filled.AccountCircle,
                    title = stringResource(R.string.login_with_google_account),
                    subtitle = stringResource(R.string.login_google_account_hint),
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = GoogleAccountLogin.completeSignIn(
                                context = context,
                                account = accounts.first(),
                                activity = context.findActivity(),
                            )
                            busy = false
                            result
                                .onSuccess {
                                    onSignedIn(
                                        cookie = "",
                                        name = accountName,
                                        email = accountEmail,
                                        handle = accountChannelHandle,
                                    )
                                }
                                .onFailure {
                                    message = it.message ?: "Google sign-in failed"
                                }
                        }
                    },
                )
                accounts.forEach { account ->
                    SignInOption(
                        icon = Icons.Filled.AccountCircle,
                        title = account.name,
                        subtitle = stringResource(R.string.login_use_account),
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = GoogleAccountLogin.completeSignIn(
                                    context = context,
                                    account = account,
                                    activity = context.findActivity(),
                                )
                                busy = false
                                result
                                    .onSuccess {
                                        onSignedIn("", accountName, accountEmail, accountChannelHandle)
                                    }
                                    .onFailure { message = it.message ?: "Google sign-in failed" }
                            }
                        },
                    )
                }
            }

            // --- Email and password, typed into the app ---
            SignInOption(
                icon = Icons.Filled.AlternateEmail,
                title = stringResource(R.string.login_with_email),
                subtitle = stringResource(R.string.login_email_hint),
                onClick = { showEmailDialog = true },
            )

            // --- The browser flow, for anything that needs a second step ---
            SignInOption(
                icon = Icons.Filled.OpenInBrowser,
                title = stringResource(R.string.login_with_browser),
                subtitle = stringResource(R.string.login_browser_hint),
                onClick = { showBrowserSignIn = true },
            )

            if (busy) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }

            if (showBrowserSignIn) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showBrowserSignIn = false }) {
                    Text(stringResource(R.string.login_hide_browser))
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    LoginWebView(
                        modifier = Modifier.fillMaxSize(),
                        onSignedIn = { navController.navigateUp() },
                    )
                }
            }
        }
    }

    if (showEmailDialog) {
        EmailPasswordDialog(
            busy = busy,
            onDismiss = { if (!busy) showEmailDialog = false },
            onSubmit = { email, password ->
                busy = true
                message = null
                scope.launch {
                    val result = YouTubeEmailLogin.signIn(email, password)
                    busy = false
                    result
                        .onSuccess { summary ->
                            showEmailDialog = false
                            onSignedIn(summary.cookie, summary.name, summary.email, summary.channelHandle)
                        }
                        .onFailure {
                            message = it.message ?: "Sign-in failed"
                        }
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(android.R.string.ok)) }
            },
            title = { Text(stringResource(R.string.login_failed)) },
            text = { Text(text) },
        )
    }
}

@Composable
private fun SignInOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmailPasswordDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.login_with_email)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.password),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = { onSubmit(email, password) },
            ) {
                Text(stringResource(R.string.login))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** The Activity, needed for the one consent screen GoogleAccountLogin may show. */
private fun android.content.Context.findActivity(): Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}