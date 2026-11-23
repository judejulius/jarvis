package com.jarvis.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.databinding.FragmentModelsBinding
import com.jarvis.assistant.databinding.ItemModelBinding

class ModelsFragment : Fragment() {

    private var _ui: FragmentModelsBinding? = null
    private val ui get() = _ui!!
    private lateinit var settings: Settings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentModelsBinding.inflate(inflater, container, false)
        settings = Settings(requireContext())
        ui.addButton.setOnClickListener { startActivity(Intent(requireContext(), ModelEditActivity::class.java)) }
        ui.fallbackSwitch.isChecked = settings.fallbackEnabled
        ui.fallbackSwitch.setOnCheckedChangeListener { _, v -> settings.fallbackEnabled = v }
        return ui.root
    }

    override fun onResume() {
        super.onResume()
        if (_ui != null) render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }

    private fun render() {
        val ctx = requireContext()
        val models = settings.modelsInOrder()
        val primaryId = settings.primaryId
        ui.list.removeAllViews()
        ui.empty.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        for (profile in models) {
            val row = ItemModelBinding.inflate(layoutInflater, ui.list, true)
            val isPrimary = profile.id == primaryId
            row.name.text = profile.name
            row.badge.text = profile.name.firstOrNull()?.uppercase() ?: "?"
            row.detail.text = "${profile.preset.label} · ${profile.model}"
            row.primaryTag.visibility = if (isPrimary) View.VISIBLE else View.GONE
            row.star.setIconResource(if (isPrimary) R.drawable.ic_star else R.drawable.ic_star_outline)
            row.star.contentDescription = getString(if (isPrimary) R.string.primary_model else R.string.make_primary)
            row.card.strokeColor = ctx.color(if (isPrimary) R.color.accent else R.color.stroke)
            row.star.setOnClickListener {
                settings.primaryId = profile.id
                render()
            }
            row.card.setOnClickListener {
                startActivity(Intent(ctx, ModelEditActivity::class.java).putExtra(ModelEditActivity.EXTRA_ID, profile.id))
            }
        }
        ui.addButton.text = getString(if (models.isEmpty()) R.string.add_first_model else R.string.add_another_model)
    }
}
