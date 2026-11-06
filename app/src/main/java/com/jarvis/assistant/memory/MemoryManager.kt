package com.jarvis.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log

data class MemoryItem(
    val id: Long,
    val category: String,
    val key: String,
    val value: String,
    val confidence: Float,
    val createdAt: Long,
    val updatedAt: Long
)

data class AliasItem(
    val id: Long,
    val trigger: String,
    val target: String,
    val type: String
)

data class CorrectionItem(
    val id: Long,
    val phrase: String,
    val action: String,
    val usageCount: Int
)

/**
 * Manages persistent local memories, user preferences, aliases, and learned corrections
 * without requiring model weight modifications or network connectivity.
 */
class MemoryManager(context: Context) {

    private val helper = JarvisDatabaseHelper(context.applicationContext)

    // In-memory cache for ultra-fast lookup (<1ms) on the voice execution path
    @Volatile private var cachedAliases = mutableMapOf<String, String>()
    @Volatile private var cachedCorrections = mutableMapOf<String, String>()

    init {
        refreshCache()
    }

    private fun refreshCache() {
        try {
            val aliases = mutableMapOf<String, String>()
            val corrections = mutableMapOf<String, String>()
            val db = helper.readableDatabase

            db.rawQuery("SELECT trigger_phrase, target_name FROM aliases", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val trigger = cursor.getString(0).lowercase().trim()
                    val target = cursor.getString(1).trim()
                    aliases[trigger] = target
                }
            }

            db.rawQuery("SELECT original_phrase, corrected_action FROM command_corrections", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val orig = cursor.getString(0).lowercase().trim()
                    val action = cursor.getString(1).trim()
                    corrections[orig] = action
                }
            }

            cachedAliases = aliases
            cachedCorrections = corrections
        } catch (e: Exception) {
            Log.e(TAG, "failed to refresh memory cache", e)
        }
    }

    fun savePreference(key: String, value: String) {
        saveItem("preference", key.trim(), value.trim())
    }

    fun saveFact(key: String, value: String) {
        saveItem("fact", key.trim(), value.trim())
    }

    private fun saveItem(category: String, key: String, value: String) {
        try {
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("category", category)
                put("key_text", key)
                put("value_text", value)
                put("confidence", 1.0f)
                put("updated_at", now)
            }
            val updated = db.update("memories", cv, "category = ? AND LOWER(key_text) = ?", arrayOf(category, key.lowercase()))
            if (updated == 0) {
                cv.put("created_at", now)
                db.insert("memories", null, cv)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveItem failed", e)
        }
    }

    fun saveAlias(trigger: String, target: String, type: String = "app") {
        try {
            val cleanTrigger = trigger.lowercase().trim()
            val cleanTarget = target.trim()
            val db = helper.writableDatabase
            val cv = ContentValues().apply {
                put("trigger_phrase", cleanTrigger)
                put("target_name", cleanTarget)
                put("alias_type", type)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("aliases", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            cachedAliases[cleanTrigger] = cleanTarget
        } catch (e: Exception) {
            Log.e(TAG, "saveAlias failed", e)
        }
    }

    fun saveCorrection(phrase: String, action: String) {
        try {
            val cleanPhrase = phrase.lowercase().trim()
            val cleanAction = action.trim()
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()

            db.rawQuery("SELECT id, usage_count FROM command_corrections WHERE original_phrase = ?", arrayOf(cleanPhrase)).use { cursor ->
                if (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val count = cursor.getInt(1) + 1
                    val cv = ContentValues().apply {
                        put("corrected_action", cleanAction)
                        put("usage_count", count)
                        put("updated_at", now)
                    }
                    db.update("command_corrections", cv, "id = ?", arrayOf(id.toString()))
                } else {
                    val cv = ContentValues().apply {
                        put("original_phrase", cleanPhrase)
                        put("corrected_action", cleanAction)
                        put("usage_count", 1)
                        put("updated_at", now)
                    }
                    db.insert("command_corrections", null, cv)
                }
            }
            cachedCorrections[cleanPhrase] = cleanAction
        } catch (e: Exception) {
            Log.e(TAG, "saveCorrection failed", e)
        }
    }

    fun findAlias(trigger: String): String? {
        val q = trigger.lowercase().trim()
        return cachedAliases[q]
    }

    fun resolveAppAlias(name: String): String {
        val q = name.lowercase().trim()
        return cachedAliases[q] ?: name
    }

    fun findCorrection(phrase: String): String? {
        val q = phrase.lowercase().trim()
        return cachedCorrections[q]
    }

    fun getAllMemories(): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        try {
            val db = helper.readableDatabase
            db.rawQuery("SELECT id, category, key_text, value_text, confidence, created_at, updated_at FROM memories ORDER BY updated_at DESC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list += MemoryItem(
                        id = cursor.getLong(0),
                        category = cursor.getString(1),
                        key = cursor.getString(2),
                        value = cursor.getString(3),
                        confidence = cursor.getFloat(4),
                        createdAt = cursor.getLong(5),
                        updatedAt = cursor.getLong(6)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllMemories failed", e)
        }
        return list
    }

    fun getAllAliases(): List<AliasItem> {
        val list = mutableListOf<AliasItem>()
        try {
            val db = helper.readableDatabase
            db.rawQuery("SELECT id, trigger_phrase, target_name, alias_type FROM aliases ORDER BY trigger_phrase ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list += AliasItem(
                        id = cursor.getLong(0),
                        trigger = cursor.getString(1),
                        target = cursor.getString(2),
                        type = cursor.getString(3)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllAliases failed", e)
        }
        return list
    }

    fun deleteMemory(id: Long): Boolean {
        return try {
            helper.writableDatabase.delete("memories", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteAlias(trigger: String): Boolean {
        return try {
            val q = trigger.lowercase().trim()
            cachedAliases.remove(q)
            helper.writableDatabase.delete("aliases", "LOWER(trigger_phrase) = ?", arrayOf(q)) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun clearAll() {
        try {
            val db = helper.writableDatabase
            db.execSQL("DELETE FROM memories")
            db.execSQL("DELETE FROM aliases")
            db.execSQL("DELETE FROM command_corrections")
            cachedAliases.clear()
            cachedCorrections.clear()
        } catch (e: Exception) {
            Log.e(TAG, "clearAll failed", e)
        }
    }

    /**
     * Builds a concise prompt context with stored preferences and facts
     * to inject into the LLM system prompt without overflowing the context.
     */
    fun getPromptContext(): String {
        val items = getAllMemories().take(10)
        if (items.isEmpty()) return ""
        val sb = StringBuilder("USER PREFERENCES & LEARNED FACTS:\n")
        items.forEach { item ->
            sb.append("- ").append(item.key).append(": ").append(item.value).append("\n")
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TAG = "MemoryManager"

        @Volatile private var instance: MemoryManager? = null

        fun get(context: Context): MemoryManager =
            instance ?: synchronized(this) {
                instance ?: MemoryManager(context.applicationContext).also { instance = it }
            }
    }
}
