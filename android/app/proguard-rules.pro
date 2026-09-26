-keep class dev.remotectl.core.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# xterm.js bridge is called from JavaScript by name
-keepclassmembers class dev.remotectl.app.ui.TerminalBridge { @android.webkit.JavascriptInterface <methods>; }
