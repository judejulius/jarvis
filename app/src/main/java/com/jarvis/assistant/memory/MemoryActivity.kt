package com.jarvis.assistant.memory

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
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
            (row.getChildAt(0) as TextView).text = item.title
            (row.getChildAt(1) as TextView).text = item.detail
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
            text = "Memories"
            textSize = 28f
            setTextColor(color(R.color.text))
        })
        root.addView(TextView(this).apply {
            text = "Things you asked Jarvis to remember, and app names you taught it. Tap a note to edit it, hold any row to delete it. They stay on this phone."
            textSize = 13f
            setTextColor(color(R.color.text_dim))
            setPadding(0, dp(6), 0, dp(12))
        })
        empty = TextView(this).apply {
            text = "Nothing saved yet. Try saying: \"Hey Jarvis, remember that I prefer short answers.\""
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
            text = "Delete everything"
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
        io.execute {
            val loaded = runCatching { store.all() }.getOrDefault(emptyList())
            runOnUiThread {
                items = loaded
                adapter.notifyDataSetChanged()
                empty.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun edit(item: MemoryStore.Item) {
        if (item.kind != MemoryStore.Kind.FACT) {
            confirmDelete(item)
            return
        }
        val input = EditText(this).apply { setText(item.title); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Edit note")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                io.execute { store.updateFact(item.id, input.text.toString()); refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(item: MemoryStore.Item) {
        AlertDialog.Builder(this)
            .setMessage("Delete this memory?\n\n${item.title}")
            .setPositiveButton("Delete") { _, _ -> io.execute { store.delete(item.kind, item.id); refresh() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Delete everything Jarvis remembers? This cannot be undone.")
            .setPositiveButton("Delete everything") { _, _ -> io.execute { store.clearAll(); refresh() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
}
