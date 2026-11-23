package com.jarvis.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.jarvis.assistant.router.AppMatcher

/**
 * Private SQLite storage for taught app names, notes and task history. Replay steps are read by the history
 * screen. Only eligible task titles can enter a model prompt. Call database methods from a worker thread.
 */
class MemoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    enum class Kind { ALIAS, FACT, TASK }

    /** One row as shown in the Memories screen. */
    data class Item(val kind: Kind, val id: Long, val title: String, val detail: String)

    @Volatile private var ftsReady = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE alias(id INTEGER PRIMARY KEY AUTOINCREMENT, phrase TEXT NOT NULL UNIQUE, target TEXT NOT NULL, " +
                "label TEXT NOT NULL, hits INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE fact(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, style INTEGER NOT NULL DEFAULT 0, " +
                "updated INTEGER NOT NULL)",
        )
        createTaskTable(db)
        addReplaySchema(db)
        try {
            db.execSQL("CREATE VIRTUAL TABLE fact_fts USING fts4(text)")
        } catch (_: SQLException) {
            // Full-text search is optional; searches fall back to LIKE.
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTaskTable(db)
        if (oldVersion < 3) addReplaySchema(db)
    }

    private fun createTaskTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS task(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, ok INTEGER NOT NULL, updated INTEGER NOT NULL)")
    }

    private fun addReplaySchema(db: SQLiteDatabase) {
        // Older entries have only their command and outcome. Keep them available in history.
        db.execSQL("ALTER TABLE task ADD COLUMN state TEXT")
        db.execSQL("ALTER TABLE task ADD COLUMN for_context INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE TABLE task_step(id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES task(id) ON DELETE CASCADE, label TEXT NOT NULL)")
        db.execSQL("CREATE INDEX task_step_order ON task_step(task_id, id)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        db.transaction {
            execSQL("INSERT INTO task_step(task_id,label) SELECT id,'Interrupted' FROM task WHERE state = 'Running'")
            execSQL("UPDATE task SET state = 'Interrupted' WHERE state = 'Running'")
        }
        ftsReady = db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'fact_fts'", null).use { it.moveToFirst() }
    }

    // ---- App names the user taught ----

    /** Remembers that saying [phrase] means the app with package [target]. Replaces an older meaning. */
    fun addAlias(phrase: String, target: String, label: String) {
        val key = AppMatcher.cleanQuery(phrase)
        if (key.isEmpty() || target.isBlank()) return
        val v = ContentValues().apply {
            put("phrase", key)
            put("target", target)
            put("label", label.take(60))
            put("hits", 0)
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("alias", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Teaches that [phrase] (as the recognizer hears it) means [meaning]. Stored as an alias whose target starts with "text:". */
    fun addPhraseCorrection(phrase: String, meaning: String) {
        val key = AppMatcher.cleanQuery(phrase)
        if (key.isEmpty() || meaning.isBlank()) return
        val v = ContentValues().apply {
            put("phrase", key)
            put("target", "text:" + meaning.trim().take(60))
            put("label", meaning.trim().take(60))
            put("hits", 0)
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("alias", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** All taught phrase corrections: what the recognizer hears -> what the user means. */
    fun phraseCorrections(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        readableDatabase.rawQuery("SELECT phrase, target FROM alias WHERE target LIKE 'text:%'", null).use {
            while (it.moveToNext()) out[it.getString(0)] = it.getString(1).removePrefix("text:")
        }
        return out
    }

    /** Package name taught for [phrase], or null. Counts a use. */
    fun appAlias(phrase: String): String? {
        val db = writableDatabase
        val target = db.rawQuery("SELECT target FROM alias WHERE phrase = ?", arrayOf(phrase)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        if (target.startsWith("text:")) return null // a phrase correction, not an app
        db.execSQL("UPDATE alias SET hits = hits + 1 WHERE phrase = ?", arrayOf(phrase))
        return target
    }

    // ---- Notes the user asked to keep ----

    /** Saves a note. Identical notes are not duplicated. Keeps at most [MAX_FACTS] notes (oldest plain ones go first). */
    fun addFact(text: String, style: Boolean): Long {
        val clean = MemoryPrompt.sanitize(text, 200)
        if (clean.length < 3) return -1
        val db = writableDatabase
        val existing = db.rawQuery("SELECT id FROM fact WHERE lower(text) = lower(?)", arrayOf(clean)).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
        if (existing >= 0) {
            db.execSQL("UPDATE fact SET updated = ?, style = ? WHERE id = ?", arrayOf(System.currentTimeMillis(), if (style) 1 else 0, existing))
            return existing
        }
        val v = ContentValues().apply {
            put("text", clean)
            put("style", if (style) 1 else 0)
            put("updated", System.currentTimeMillis())
        }
        val id = db.insert("fact", null, v)
        if (id >= 0) ftsPut(db, id, clean)
        prune(db)
        return id
    }

    fun updateFact(id: Long, text: String) {
        val clean = MemoryPrompt.sanitize(text, 200)
        if (clean.length < 3) return
        val db = writableDatabase
        db.execSQL("UPDATE fact SET text = ?, updated = ? WHERE id = ?", arrayOf(clean, System.currentTimeMillis(), id))
        ftsDelete(db, id)
        ftsPut(db, id, clean)
    }

    /** Notes about how to answer (short answers, language), always sent along with a request. */
    fun styleFacts(limit: Int = 5): List<MemoryFact> =
        readFacts("SELECT id, text, style FROM fact WHERE style = 1 ORDER BY updated DESC LIMIT ?", arrayOf(limit.toString()))

    /** Notes related to [query]: full-text search when available, otherwise a plain word match. */
    fun search(query: String, limit: Int = 4): List<MemoryFact> {
        val terms = MemoryPrompt.searchTerms(query)
        if (terms.isEmpty()) return emptyList()
        if (ftsReady) {
            try {
                val match = terms.joinToString(" OR ") { "$it*" }
                return readFacts(
                    "SELECT f.id, f.text, f.style FROM fact f JOIN fact_fts ON f.id = fact_fts.docid " +
                        "WHERE fact_fts MATCH ? ORDER BY f.updated DESC LIMIT ?",
                    arrayOf(match, limit.toString()),
                )
            } catch (_: SQLException) {
                // fall through to LIKE
            }
        }
        val where = terms.joinToString(" OR ") { "lower(text) LIKE ?" }
        val args = terms.map { "%$it%" } + limit.toString()
        return readFacts("SELECT id, text, style FROM fact WHERE $where ORDER BY updated DESC LIMIT ?", args.toTypedArray())
    }

    /**
     * The memory block for a prompt. Style notes always go in. Other notes go only to models running on the phone,
     * unless [shareWithCloud] is on.
     */
    fun promptContext(task: String, toLocal: Boolean, shareWithCloud: Boolean): String {
        val style = styleFacts()
        val shareable = toLocal || shareWithCloud
        val relevant = if (shareable) search(task) else emptyList()
        val tasks = if (shareable) relatedTasks(task) else emptyList()
        return MemoryPrompt.build(style, relevant, tasks)
    }

    // ---- Tasks that were done ----

    /** Remembers that a task was done (a short, cleaned line). Only the most recent [MAX_TASKS] are kept. */
    fun addTask(text: String, ok: Boolean) {
        if (MemoryPrompt.sanitize(text, 80).length >= 3) insertTask(text, ok, state = null, forContext = true)
    }

    fun startTask(text: String, forContext: Boolean): Long? =
        insertTask(text, ok = false, state = "Running", forContext = forContext)

    private fun insertTask(text: String, ok: Boolean, state: String?, forContext: Boolean): Long? {
        val clean = MemoryPrompt.sanitize(text, 80)
        if (clean.isEmpty() || SensitiveFilter.isSensitive(text) || SensitiveFilter.isSensitive(clean)) return null
        val values = ContentValues().apply {
            put("text", clean)
            put("ok", ok)
            put("updated", System.currentTimeMillis())
            put("state", state)
            put("for_context", forContext)
        }
        return writableDatabase.transaction {
            val id = insertOrThrow("task", null, values)
            if (state == "Running") appendStep(id, "Received the command.")
            execSQL("DELETE FROM task WHERE id NOT IN (SELECT id FROM task ORDER BY id DESC LIMIT $MAX_TASKS)")
            id
        }
    }

    fun appendStep(id: Long, label: String) {
        val clean = MemoryPrompt.sanitize(label, 160)
        if (clean.isBlank() || SensitiveFilter.isSensitive(label)) return
        // A user can delete an entry while its task is still running.
        writableDatabase.execSQL("INSERT INTO task_step(task_id, label) SELECT id, ? FROM task WHERE id = ? AND state = 'Running'", arrayOf(clean, id))
    }

    fun finishTask(id: Long, state: String) {
        writableDatabase.transaction {
            appendStep(id, state)
            execSQL("UPDATE task SET state = ?, ok = ? WHERE id = ? AND state = 'Running'",
                arrayOf(state, if (state == "Completed") 1 else 0, id))
        }
    }

    fun steps(id: Long): List<String> = readableDatabase.rawQuery(
        "SELECT label FROM task_step WHERE task_id = ? ORDER BY id", arrayOf(id.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun history(limit: Int = MAX_TASKS): List<Item> = readableDatabase.rawQuery(
        "SELECT id, text, COALESCE(state, CASE ok WHEN 1 THEN 'Completed' ELSE 'Failed' END), updated FROM task ORDER BY id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { cursor ->
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        buildList {
            while (cursor.moveToNext()) {
                val whenStarted = date.format(java.util.Date(cursor.getLong(3)))
                add(Item(Kind.TASK, cursor.getLong(0), cursor.getString(1), "${cursor.getString(2)} · $whenStarted"))
            }
        }
    }

    fun clearHistory() { writableDatabase.delete("task", null, null) }

    /** Earlier tasks that share words with [query], newest first. */
    fun relatedTasks(query: String, limit: Int = 3): List<String> {
        val terms = MemoryPrompt.searchTerms(query)
        if (terms.isEmpty()) return emptyList()
        val where = terms.joinToString(" OR ") { "lower(text) LIKE ?" }
        val args = terms.map { "%$it%" } + limit.toString()
        return readableDatabase.rawQuery("SELECT text, ok FROM task WHERE for_context = 1 AND (state IS NULL OR state IN ('Completed','Failed')) AND ($where) ORDER BY id DESC LIMIT ?", args.toTypedArray()).use {
            val l = ArrayList<String>()
            while (it.moveToNext()) l += it.getString(0) + if (it.getInt(1) == 1) "" else " (did not work)"
            l
        }
    }

    // ---- Viewing, editing, deleting ----

    fun all(): List<Item> {
        val db = readableDatabase
        val items = ArrayList<Item>()
        db.rawQuery("SELECT id, phrase, label FROM alias ORDER BY updated DESC", null).use {
            while (it.moveToNext()) items += Item(Kind.ALIAS, it.getLong(0), "\"${it.getString(1)}\" opens ${it.getString(2)}", "App name")
        }
        db.rawQuery("SELECT id, text, style FROM fact ORDER BY updated DESC", null).use {
            while (it.moveToNext()) items += Item(Kind.FACT, it.getLong(0), it.getString(1), if (it.getInt(2) == 1) "How to answer" else "Note")
        }
        items += history(30)
        return items
    }

    fun delete(kind: Kind, id: Long) {
        val db = writableDatabase
        when (kind) {
            Kind.ALIAS -> db.delete("alias", "id = ?", arrayOf(id.toString()))
            Kind.TASK -> db.delete("task", "id = ?", arrayOf(id.toString()))
            Kind.FACT -> {
                db.delete("fact", "id = ?", arrayOf(id.toString()))
                ftsDelete(db, id)
            }
        }
    }

    /** Deletes notes that mention [what] and app names that match it. Returns how many were removed. */
    fun forget(what: String): Int {
        val w = what.trim().lowercase()
        if (w.isEmpty()) return 0

        val db = writableDatabase
        var removed = 0

        // App aliases can be short (for example, "yt"), so remove exact
        // alias/label matches before applying the minimum length for facts.
        val phrase = AppMatcher.cleanQuery(what)
        removed += db.delete(
            "alias",
            "phrase = ? OR lower(label) = ?",
            arrayOf(phrase, w),
        )

        // Avoid deleting unrelated facts from very short search terms.
        if (w.length < 3) return removed

        val ids = db.rawQuery(
            "SELECT id FROM fact WHERE lower(text) LIKE ?",
            arrayOf("%$w%"),
        ).use {
            val l = ArrayList<Long>()
            while (it.moveToNext()) l += it.getLong(0)
            l
        }

        ids.forEach { delete(Kind.FACT, it); removed++ }
        return removed
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete("alias", null, null)
        db.delete("fact", null, null)
        db.delete("task", null, null)
        if (ftsReady) runCatching { db.execSQL("DELETE FROM fact_fts") }
    }

    // ---- helpers ----

    private fun readFacts(sql: String, args: Array<String>): List<MemoryFact> =
        readableDatabase.rawQuery(sql, args).use { c: Cursor ->
            val out = ArrayList<MemoryFact>()
            while (c.moveToNext()) out += MemoryFact(c.getLong(0), c.getString(1), c.getInt(2) == 1)
            out
        }

    private fun ftsPut(db: SQLiteDatabase, id: Long, text: String) {
        if (!ftsReady) return
        runCatching { db.execSQL("INSERT INTO fact_fts(docid, text) VALUES (?, ?)", arrayOf(id, text)) }
    }

    private fun ftsDelete(db: SQLiteDatabase, id: Long) {
        if (!ftsReady) return
        runCatching { db.execSQL("DELETE FROM fact_fts WHERE docid = ?", arrayOf(id)) }
    }

    private fun prune(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM fact", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count <= MAX_FACTS) return
        val victims = db.rawQuery(
            "SELECT id FROM fact WHERE style = 0 ORDER BY updated ASC LIMIT ?", arrayOf((count - MAX_FACTS).toString()),
        ).use {
            val l = ArrayList<Long>()
            while (it.moveToNext()) l += it.getLong(0)
            l
        }
        victims.forEach { id ->
            db.delete("fact", "id = ?", arrayOf(id.toString()))
            ftsDelete(db, id)
        }
    }

    companion object {
        private const val DB_NAME = "jarvis_memory.db"
        private const val DB_VERSION = 3
        const val MAX_FACTS = 300
        const val MAX_TASKS = 100

        @Volatile private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) { instance ?: MemoryStore(context).also { instance = it } }
    }
}
