package com.jarvis.assistant.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite database helper for persistent JARVIS memory, user preferences,
 * app aliases, and learned command corrections.
 */
class JarvisDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                key_text TEXT NOT NULL,
                value_text TEXT NOT NULL,
                confidence REAL DEFAULT 1.0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS aliases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trigger_phrase TEXT UNIQUE NOT NULL,
                target_name TEXT NOT NULL,
                alias_type TEXT NOT NULL DEFAULT 'app',
                created_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS command_corrections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_phrase TEXT UNIQUE NOT NULL,
                corrected_action TEXT NOT NULL,
                usage_count INTEGER DEFAULT 1,
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        // Prepopulate standard Indian English / common aliases
        db.execSQL("INSERT OR IGNORE INTO aliases (trigger_phrase, target_name, alias_type, created_at) VALUES ('yt', 'YouTube', 'app', ${System.currentTimeMillis()});")
        db.execSQL("INSERT OR IGNORE INTO aliases (trigger_phrase, target_name, alias_type, created_at) VALUES ('insta', 'Instagram', 'app', ${System.currentTimeMillis()});")
        db.execSQL("INSERT OR IGNORE INTO aliases (trigger_phrase, target_name, alias_type, created_at) VALUES ('fb', 'Facebook', 'app', ${System.currentTimeMillis()});")
        db.execSQL("INSERT OR IGNORE INTO aliases (trigger_phrase, target_name, alias_type, created_at) VALUES ('wp', 'WhatsApp', 'app', ${System.currentTimeMillis()});")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe incremental migrations if needed in future
    }

    companion object {
        const val DATABASE_NAME = "jarvis_memory.db"
        const val DATABASE_VERSION = 1
    }
}
