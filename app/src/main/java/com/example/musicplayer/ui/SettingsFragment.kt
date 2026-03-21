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

        // İndirme Limiti
        val downloadLimit = prefs.getInt("download_limit", 3)
        when (downloadLimit) {
            1 -> binding.rbLimit1.isChecked = true
            3 -> binding.rbLimit3.isChecked = true
            5 -> binding.rbLimit5.isChecked = true
            10 -> binding.rbLimit10.isChecked = true
            20 -> binding.rbLimit20.isChecked = true
        }
        binding.rgDownloadLimit.setOnCheckedChangeListener { _, checkedId ->
            val limit = when (checkedId) {
                binding.rbLimit1.id -> 1
                binding.rbLimit3.id -> 3
                binding.rbLimit5.id -> 5
                binding.rbLimit10.id -> 10
                binding.rbLimit20.id -> 20
                else -> 3
            }
            prefs.edit().putInt("download_limit", limit).apply()
            Toast.makeText(requireContext(), "İndirme limiti $limit olarak ayarlandı", Toast.LENGTH_SHORT).show()
        }

        // Arama sonucu sayısı
        val searchCount = prefs.getInt("search_count", 20)
        when (searchCount) {
            20 -> binding.rbCount20.isChecked = true
            50 -> binding.rbCount50.isChecked = true
            100 -> binding.rbCount100.isChecked = true
        }
        binding.rgSearchCount.setOnCheckedChangeListener { _, checkedId ->
            val count = when (checkedId) {
                binding.rbCount20.id -> 20
                binding.rbCount50.id -> 50
                binding.rbCount100.id -> 100
                else -> 20
            }
            prefs.edit().putInt("search_count", count).apply()
            Toast.makeText(requireContext(), "Sayfa başına $count sonuç ayarlandı", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
