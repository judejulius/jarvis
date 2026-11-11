package com.jarvis.assistant.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager

/** Reads contacts and phone state for the call feature. Everything stays on the phone. Call from a worker thread. */
class ContactResolver(context: Context) {
    private val app = context.applicationContext

    @Volatile private var cache: Pair<Long, List<ContactEntry>>? = null

    fun hasContactsPermission() = granted(Manifest.permission.READ_CONTACTS)
    fun hasCallPermission() = granted(Manifest.permission.CALL_PHONE)
    fun hasPhoneStatePermission() = granted(Manifest.permission.READ_PHONE_STATE)

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** All contacts with a phone number, cached for a minute. Empty without the contacts permission. */
    fun contacts(): List<ContactEntry> {
        if (!hasContactsPermission()) return emptyList()
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - it.first < CACHE_MS) return it.second }
        val list = query()
        cache = now to list
        return list
    }

    private fun query(): List<ContactEntry> {
        val byId = LinkedHashMap<Long, Pair<String, MutableList<PhoneNumber>>>()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_PRIMARY)
        app.contentResolver.query(Phone.CONTENT_URI, projection, null, null, "${Phone.DISPLAY_NAME} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = c.getString(2)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val type = when (c.getInt(3)) {
                    Phone.TYPE_MOBILE -> "mobile"
                    Phone.TYPE_HOME -> "home"
                    Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "work"
                    else -> "other"
                }
                val entry = byId.getOrPut(id) { name to ArrayList() }
                if (entry.second.none { digits(it.number) == digits(number) }) entry.second += PhoneNumber(number, type, c.getInt(4) != 0)
            }
        }
        return byId.map { (id, v) -> ContactEntry(id, v.first, v.second) }
    }

    /** True while a phone call is in progress. Needs the phone-state permission; false if it cannot be read. */
    fun inCall(): Boolean = try {
        app.getSystemService(TelecomManager::class.java)?.isInCall == true
    } catch (_: SecurityException) {
        false
    }

    private fun digits(s: String) = s.filter { it.isDigit() }.takeLast(10)

    companion object {
        private const val CACHE_MS = 60_000L
    }
}
