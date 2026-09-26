package dev.remotectl.app.ui

import java.util.Locale

fun bytes(n: Long): String {
    val gb = n / 1e9
    return when {
        gb >= 100 -> String.format(Locale.US, "%.0f GB", gb)
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        else -> String.format(Locale.US, "%.0f MB", n / 1e6)
    }
}

fun uptime(secs: Long): String {
    val d = secs / 86_400
    val h = secs % 86_400 / 3_600
    val m = secs % 3_600 / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun pct(f: Float): String = String.format(Locale.US, "%.0f%%", f)
