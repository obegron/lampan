package com.egron.lampan

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val PREFS_NAME = "LampanPrefs"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveIpForSsid(ssid: String, ip: String) {
        prefs.edit().putString("IP_$ssid", ip).apply()
    }

    fun getIpForSsid(ssid: String): String {
        return prefs.getString("IP_$ssid", "") ?: ""
    }
    
    fun saveLastUsedIp(ip: String) {
        prefs.edit().putString("LAST_IP", ip).apply()
    }
    
    fun getLastUsedIp(): String {
        return prefs.getString("LAST_IP", "") ?: ""
    }
}
