package com.jellydirect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jellydirect.app.data.Account
import com.jellydirect.app.data.AuthResult
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.data.PublicInfo
import com.jellydirect.app.data.PublicUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Normalises what the person typed: adds http:// if missing, strips trailing slash and /web. */
fun normalizeServerUrl(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return s
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    s = s.trimEnd('/')
    if (s.endsWith("/web", true)) s = s.dropLast(4)
    return s
}

@Composable
fun AuthScreen(onDone: () -> Unit, onCancel: (() -> Unit)? = null, presetUrl: String? = null) {
    val app = currentApp()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<PublicInfo?>(null) }
    var api by remember { mutableStateOf<JellyApi?>(null) }
    var users by remember { mutableStateOf<List<PublicUser>>(emptyList()) }
    var qcEnabled by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qcCode by remember { mutableStateOf<String?>(null) }

    fun finish(res: AuthResult, base: String, i: PublicInfo) {
        app.signIn(Account(base, i.serverName.ifBlank { base }, i.id, res.user.id, res.user.name, res.token))
        onDone()
    }

    fun connect() {
        val base = normalizeServerUrl(url)
        if (base.isEmpty()) return
        busy = true; error = null
        scope.launch {
            try {
                val a = JellyApi(base, app.store.deviceId)
                info = a.publicInfo()
                users = try { a.publicUsers() } catch (_: Exception) { emptyList() }
                qcEnabled = a.quickConnectEnabled()
                api = a
                url = base
            } catch (e: Exception) {
                error = "Can't reach the server: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun signIn() {
        val a = api ?: return
        val i = info ?: return
        busy = true; error = null
        scope.launch {
            try {
                finish(a.login(user.trim(), pass), url, i)
            } catch (e: Exception) {
                error = if ((e as? com.jellydirect.app.data.ApiException)?.code == 401) "Wrong username or password" else "Sign-in failed: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun quickConnect() {
        val a = api ?: return
        val i = info ?: return
        busy = true; error = null
        scope.launch {
            try {
                val st = a.quickConnectInitiate()
                qcCode = st.code
                busy = false
                // Poll for up to 5 minutes while the person approves the code on another device.
                repeat(150) {
                    delay(2000)
                    if (qcCode == null) return@launch
                    if (a.quickConnectCheck(st.secret).authenticated) {
                        finish(a.quickConnectLogin(st.secret), url, i)
                        return@launch
                    }
                }
                qcCode = null
                error = "Quick Connect timed out"
            } catch (e: Exception) {
                qcCode = null
                busy = false
                error = "Quick Connect failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (presetUrl != null) { url = presetUrl; connect() }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("JellyDirect", style = MaterialTheme.typography.titleLarge, color = Accent)
            if (info == null) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Server address") },
                    placeholder = { Text("192.168.1.10:8096 or https://jelly.example.com") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                )
                Button(onClick = { connect() }, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            } else {
                Text(info!!.serverName.ifBlank { url }, style = MaterialTheme.typography.titleMedium)
                if (users.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        users.take(4).forEach { u -> Chip(u.name, user == u.name) { user = u.name } }
                    }
                }
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { signIn() }),
                )
                Button(onClick = { signIn() }, enabled = !busy && user.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
                if (qcEnabled) {
                    OutlinedButton(onClick = { quickConnect() }, enabled = !busy && qcCode == null, modifier = Modifier.fillMaxWidth()) { Text("Quick Connect") }
                }
                TextButton(onClick = { info = null; api = null; qcCode = null; error = null }) { Text("Change server") }
            }
            if (busy) CircularProgressIndicator(color = Accent)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    qcCode?.let { code ->
        AlertDialog(
            onDismissRequest = { qcCode = null },
            title = { Text("Quick Connect") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("On a device that is already signed in, open Jellyfin → Quick Connect and enter:")
                    Spacer(Modifier.height(12.dp))
                    Text(code, style = MaterialTheme.typography.titleLarge, color = Accent)
                }
            },
            confirmButton = { TextButton(onClick = { qcCode = null }) { Text("Cancel") } },
            containerColor = Surface2,
        )
    }
}
