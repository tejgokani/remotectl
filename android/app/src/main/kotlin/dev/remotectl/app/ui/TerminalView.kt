package dev.remotectl.app.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.remotectl.app.AppViewModel
import dev.remotectl.app.DeviceUi

/**
 * Bridge between the xterm.js page and the ViewModel. Only exposed to our own bundled page;
 * the WebView refuses to navigate anywhere else.
 */
class TerminalBridge(private val vm: AppViewModel) {
    @JavascriptInterface
    fun onReady(cols: Int, rows: Int) = vm.openTerminal(cols, rows)

    @JavascriptInterface
    fun onInput(base64: String) = vm.termInput(Base64.decode(base64, Base64.DEFAULT))

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) = vm.termResize(cols, rows)
}

private data class Key(val label: String, val bytes: String)

private val keys = listOf(
    Key("Esc", "\u001b"), Key("Tab", "\t"), Key("^C", "\u0003"), Key("^D", "\u0004"), Key("^Z", "\u001a"),
    Key("↑", "\u001b[A"), Key("↓", "\u001b[B"), Key("←", "\u001b[D"), Key("→", "\u001b[C"),
    Key("|", "|"), Key("~", "~"), Key("/", "/"), Key("-", "-"),
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(ui: DeviceUi, vm: AppViewModel) {
    val webView = remember { arrayOfNulls<WebView>(1) }

    // Laptop output -> xterm
    LaunchedEffect(Unit) {
        vm.termOutput.collect { data ->
            val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
            webView[0]?.post { webView[0]?.evaluateJavascript("writeB64('$b64')", null) }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.closeTerminal()
            webView[0]?.destroy()
            webView[0] = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF000000.toInt())
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    addJavascriptInterface(TerminalBridge(vm), "Android")
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.d("remotectl-term", "${m.messageLevel()} ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        // Never leave the bundled page.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    }
                    loadUrl("file:///android_asset/terminal.html")
                    webView[0] = this
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            keys.forEach { k ->
                OutlinedButton(onClick = { vm.termInput(k.bytes.toByteArray()) }, modifier = Modifier.padding(end = 6.dp)) {
                    Text(k.label, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
