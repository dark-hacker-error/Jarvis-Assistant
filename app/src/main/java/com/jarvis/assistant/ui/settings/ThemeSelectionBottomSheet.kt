package com.jarvis.assistant.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.BottomSheetThemeSelectionBinding
import com.jarvis.assistant.ui.home.HomeFragment
import com.jarvis.assistant.ui.home.MainActivity

class ThemeSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetThemeSelectionBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetThemeSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentTheme = preferences.appTheme
        updateCheckmarks(currentTheme)

        val customHex = preferences.customThemeColor
        binding.etCustomHex.setText(customHex)
        try {
            binding.viewCustomPreview.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(customHex))
        } catch (_: Exception) {}

        binding.cardThemeLight.setOnClickListener { selectTheme("light", "Light") }
        binding.cardThemeDark.setOnClickListener { selectTheme("dark", "Dark") }
        binding.cardThemeRed.setOnClickListener { selectTheme("red", "Red") }
        binding.cardThemeSunset.setOnClickListener { selectTheme("sunset", "Sunset") }

        binding.btnApplyCustom.setOnClickListener {
            val hex = binding.etCustomHex.text.toString().trim()
            applyCustomColor(hex)
        }

        // Quick Swatches
        binding.swatch1.setOnClickListener { applyCustomColor("#000000") }
        binding.swatch2.setOnClickListener { applyCustomColor("#1E1B4B") }
        binding.swatch3.setOnClickListener { applyCustomColor("#042F2E") }
        binding.swatch4.setOnClickListener { applyCustomColor("#451A03") }
        binding.swatch5.setOnClickListener { applyCustomColor("#F0FDF4") }
        binding.swatch6.setOnClickListener { applyCustomColor("#FAF5FF") }
    }

    private fun applyCustomColor(hexInput: String) {
        val hex = if (hexInput.startsWith("#")) hexInput else "#$hexInput"
        try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Invalid HEX color format (e.g. #1E1B4B)", Toast.LENGTH_SHORT).show()
            return
        }

        preferences.customThemeColor = hex
        preferences.appTheme = "custom"
        updateCheckmarks("custom")

        Toast.makeText(requireContext(), "Custom theme set to $hex", Toast.LENGTH_SHORT).show()

        // Sync with Orb on HomeFragment
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbTheme("custom", hex)

        dismiss()
        activity?.recreate()
    }

    private fun selectTheme(themeKey: String, themeName: String) {
        preferences.appTheme = themeKey
        updateCheckmarks(themeKey)
        Toast.makeText(requireContext(), "Theme set to $themeName", Toast.LENGTH_SHORT).show()

        // Sync with Orb on HomeFragment
        val mainActivity = activity as? MainActivity
        val homeFrag = mainActivity?.supportFragmentManager?.fragments
            ?.filterIsInstance<HomeFragment>()
            ?.firstOrNull()
        homeFrag?.setOrbTheme(themeKey, null)

        dismiss()
        activity?.recreate()
    }

    private fun updateCheckmarks(activeTheme: String) {
        binding.ivCheckLight.visibility = if (activeTheme == "light") View.VISIBLE else View.GONE
        binding.ivCheckDark.visibility = if (activeTheme == "dark") View.VISIBLE else View.GONE
        binding.ivCheckRed.visibility = if (activeTheme == "red") View.VISIBLE else View.GONE
        binding.ivCheckSunset.visibility = if (activeTheme == "sunset") View.VISIBLE else View.GONE
        binding.ivCheckCustom.visibility = if (activeTheme == "custom") View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ThemeSelectionBottomSheet"
        fun newInstance() = ThemeSelectionBottomSheet()
    }
}
