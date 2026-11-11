package com.jarvis.assistant.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Connectivity {
    /** True on a metered connection (mobile data, or Wi-Fi marked as metered). */
    fun isMetered(context: Context): Boolean = try {
        context.applicationContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    } catch (_: SecurityException) {
        false
    }

    /** True if the phone has a working internet connection. If that cannot be determined, assumes yes. */
    fun isOnline(context: Context): Boolean = try {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = if (cm != null && network != null) cm.getNetworkCapabilities(network) else null
        when {
            cm == null -> true
            caps == null -> false
            else -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    } catch (_: SecurityException) {
        true
    } catch (_: RuntimeException) {
        true
    }
}
