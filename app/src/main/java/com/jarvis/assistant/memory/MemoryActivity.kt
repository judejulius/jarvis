package com.jarvis.assistant.memory

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.padForSystemBars
import java.util.concurrent.Executors

/**
 * Everything Jarvis has been asked to remember: notes and taught app names. Tap a note to edit it, hold any row to
 * delete it. All of it is stored on this phone only.
 */
class MemoryActivity : AppCompatActivity() {
    private val historyOnly by lazy { intent.getBooleanExtra(EXTRA_HISTORY, false) }
    private lateinit var store: MemoryStore
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var items: List<MemoryStore.Item> = emptyList()
    private val io = Executors.newSingleThreadExecutor()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MemoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
                addView(TextView(context).apply { setTextColor(color(R.color.text)); textSize = 16f })
                addView(TextView(context).apply { setTextColor(color(R.color.text_dim)); textSize = 12f })
            }
            (row.getChildAt(0) as TextView).text = MemoryLabels.title(this@MemoryActivity, item)
            (row.getChildAt(1) as TextView).text = MemoryLabels.detail(this@MemoryActivity, item)
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MemoryStore.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
            setPadding(dp(18), dp(24), dp(18), dp(12))
        }
        root.addView(TextView(this).apply {
            text = getString(if (historyOnly) R.string.task_history else R.string.memories)
            textSize = 28f
            setTextColor(color(R.color.text))
        })
        root.addView(TextView(this).apply {
            text = getString(if (historyOnly) R.string.history_intro else R.string.memories_intro)
            textSize = 13f
            setTextColor(color(R.color.text_dim))
            setPadding(0, dp(6), 0, dp(12))
        })
        empty = TextView(this).apply {
            text = getString(if (historyOnly) R.string.history_empty else R.string.memories_empty)
            setTextColor(color(R.color.text_dim))
            visibility = View.GONE
        }
        root.addView(empty)
        list = ListView(this).apply {
            adapter = this@MemoryActivity.adapter
            divider = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(list)
        root.addView(TextView(this).apply {
            text = getString(if (historyOnly) R.string.clear_history else R.string.delete_everything)
            setTextColor(color(R.color.danger))
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { confirmClear() }
        })
        setContentView(root)
        root.padForSystemBars()

        list.setOnItemClickListener { _, _, position, _ -> edit(items[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> confirmDelete(items[position]); true }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        io.execute {
            val result = runCatching { if (historyOnly) store.history() else store.all() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val loaded = result.getOrElse {
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                items = loaded
                adapter.notifyDataSetChanged()
                empty.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun edit(item: MemoryStore.Item) {
        if (item.kind == MemoryStore.Kind.TASK) {
            TaskReplayDialog.forTask(item).show(supportFragmentManager, "task-replay")
            return
        }
        if (item.kind != MemoryStore.Kind.FACT) {
            confirmDelete(item)
            return
        }
        val input = EditText(this).apply { setText(item.title); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_note)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString()
                updateStore { store.updateFact(item.id, text) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(item: MemoryStore.Item) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_memory, MemoryLabels.title(this, item)))
            .setPositiveButton(R.string.delete) { _, _ -> updateStore { store.delete(item.kind, item.id) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(if (historyOnly) R.string.confirm_clear_history else R.string.confirm_clear_all)
            .setPositiveButton(R.string.delete) { _, _ ->
                updateStore { if (historyOnly) store.clearHistory() else store.clearAll() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateStore(change: () -> Unit) {
        io.execute {
            val result = runCatching(change)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isSuccess) refresh()
                else Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object { const val EXTRA_HISTORY = "task_history" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
}
