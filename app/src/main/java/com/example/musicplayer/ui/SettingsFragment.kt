package com.example.musicplayer.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.musicplayer.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences("melodify_prefs", Context.MODE_PRIVATE)

        // Ses kalitesi
        val quality = prefs.getString("quality", "best")
        when (quality) {
            "best" -> binding.rbBest.isChecked = true
            "medium" -> binding.rbMedium.isChecked = true
            "low" -> binding.rbLow.isChecked = true
        }
        binding.rgQuality.setOnCheckedChangeListener { _, checkedId ->
            val selectedQuality = when (checkedId) {
                binding.rbBest.id -> "best"
                binding.rbMedium.id -> "medium"
                else -> "low"
            }
            prefs.edit().putString("quality", selectedQuality).apply()
            Toast.makeText(requireContext(), "Kalite ayarı kaydedildi", Toast.LENGTH_SHORT).show()
        }

        // Arama sonucu sayısı
        val searchCount = prefs.getInt("search_count", 20)
        when (searchCount) {
            10 -> binding.rbCount10.isChecked = true
            20 -> binding.rbCount20.isChecked = true
            30 -> binding.rbCount30.isChecked = true
            50 -> binding.rbCount50.isChecked = true
        }
        binding.rgSearchCount.setOnCheckedChangeListener { _, checkedId ->
            val count = when (checkedId) {
                binding.rbCount10.id -> 10
                binding.rbCount20.id -> 20
                binding.rbCount30.id -> 30
                binding.rbCount50.id -> 50
                else -> 20
            }
            prefs.edit().putInt("search_count", count).apply()
            Toast.makeText(requireContext(), "$count sonuç ayarlandı", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
