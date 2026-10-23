package com.jarvis.assistant.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.databinding.FragmentUpdatesBinding

class UpdatesFragment : Fragment() {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUpdatesBinding.inflate(inflater, container, false)
        renderFeatureUpdates()
        return binding.root
    }

    private data class FeatureNotice(val feature: String, val title: String, val details: String, val kind: String = "FEATURE")

    private fun renderFeatureUpdates() {
        val features = listOf(
            FeatureNotice("Agent tasks", "Verified actions and recovery", "JARVIS observes the current screen, checks action results, tracks progress, and stops safely when the UI is unclear."),
            FeatureNotice("Floating avatar", "Always-on or working-only display", "Choose an avatar, double-tap for compact task progress, drag it anywhere on screen, and choose a dock edge in Settings."),
            FeatureNotice("Task progress", "Four-step compact status", "The task HUD shows the goal, recent steps, a running indicator, completed checks, and a subdued dark progress line."),
            FeatureNotice("Silent execution", "Quiet task automation", "When enabled, task narration is muted while ordinary conversation stays audible."),
            FeatureNotice("WhatsApp", "Saved contact lookup", "Contact aliases are supported. Grant Contacts permission first; JARVIS will report a missing permission separately from a name mismatch."),
            FeatureNotice("Phone calls", "Permission-aware calling", "Normal calls require Contacts and Phone permissions. WhatsApp calls use WhatsApp and do not place a cellular call."),
            FeatureNotice("Google Flow / AI Studio", "Browser input diagnostics", "The captured Flow run could not verify the prompt input in Chrome. Some editor fields are not exposed to Accessibility; screen sharing must be active and have a fresh frame for visual targeting. The captured logs did not show a Google AI Studio page, so that failure still needs a fresh log capture.", "DIAGNOSTIC"),
            FeatureNotice("Instagram", "Bulk action safety", "Bulk tasks track list progress and require end-of-list evidence. The captured run repeatedly reached the action limit and did not establish completion; avoid treating an unverified result as done.", "DIAGNOSTIC"),
            FeatureNotice("Spotify", "Song search and playback checks", "Searches can inspect results and verify playback before reporting success; physical-device behavior still depends on the Spotify screen and account."),
            FeatureNotice("Permissions", "Per-feature access status", "The System Authorizations page lists microphone, location, notifications, overlay, Accessibility, contacts, phone, camera, Bluetooth, battery, and brightness access."),
            FeatureNotice("AI persona", "Operator profile preview", "Preview your selected assistant voice and the name it will use before activating the persona."),
            FeatureNotice("Appearance", "Sunset theme by default", "Fresh installs start with the sunset theme; you can still select other themes in Settings.")
        )
        val list = binding.updatesList
        list.removeAllViews()
        features.forEach { feature ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = androidx.core.content.ContextCompat.getDrawable(requireContext(), com.jarvis.assistant.R.drawable.bg_jarvis_card)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(14)
                }
            }
            card.addView(TextView(requireContext()).apply {
                text = feature.feature.uppercase() + if (feature.kind == "DIAGNOSTIC") "  ·  DIAGNOSTIC" else ""
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeAccent))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextPrimary))
                setPadding(0, dp(8), 0, dp(5))
            })
            card.addView(TextView(requireContext()).apply {
                text = feature.details
                textSize = 13f
                setTextColor(themeColor(com.jarvis.assistant.R.attr.themeTextMuted))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            list.addView(card)
        }
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
