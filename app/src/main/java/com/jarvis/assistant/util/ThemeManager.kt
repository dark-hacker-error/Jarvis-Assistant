package com.jarvis.assistant.util

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

object ThemeManager {

    fun isLight(colorInt: Int): Boolean {
        val r = Color.red(colorInt) / 255.0
        val g = Color.green(colorInt) / 255.0
        val b = Color.blue(colorInt) / 255.0
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum > 0.55
    }

    fun getThemeBgColor(themeKey: String, customHex: String?): Int {
        return when (themeKey) {
            "dark" -> Color.parseColor("#070914")
            "red" -> Color.parseColor("#0E0307")
            "sunset" -> Color.parseColor("#FFF0EA")
            "custom" -> try { Color.parseColor(customHex ?: "#FFFFFF") } catch (_: Exception) { Color.WHITE }
            else -> Color.parseColor("#EEF2F9")
        }
    }

    fun applyTheme(activity: Activity) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"

        val (themeRes, isLightStatusBar, bgColor) = when (themeKey) {
            "dark" -> Triple(R.style.Theme_JarvisAssistant_Dark, false, Color.parseColor("#070914"))
            "red" -> Triple(R.style.Theme_JarvisAssistant_Red, false, Color.parseColor("#0E0307"))
            "sunset" -> Triple(R.style.Theme_JarvisAssistant_Sunset, true, Color.parseColor("#FFF0EA"))
            "custom" -> {
                val customHex = prefs?.customThemeColor ?: "#FFFFFF"
                val parsedColor = try { Color.parseColor(customHex) } catch (_: Exception) { Color.WHITE }
                val light = isLight(parsedColor)
                val res = if (light) R.style.Theme_JarvisAssistant_CustomLight else R.style.Theme_JarvisAssistant_CustomDark
                Triple(res, light, parsedColor)
            }
            else -> Triple(R.style.Theme_JarvisAssistant_Light, true, Color.parseColor("#EEF2F9"))
        }

        activity.setTheme(themeRes)
        activity.window.setBackgroundDrawable(ColorDrawable(bgColor))
        activity.window.decorView.setBackgroundColor(bgColor)
        activity.window.statusBarColor = bgColor
        activity.window.navigationBarColor = bgColor

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = isLightStatusBar
        controller.isAppearanceLightNavigationBars = isLightStatusBar

        try {
            activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor)
        } catch (_: Exception) {}
        activity.window.decorView.post {
            try {
                activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor)
            } catch (_: Exception) {}
        }
    }

    fun applyDynamicBackground(view: View) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"
        val customHex = prefs?.customThemeColor ?: "#FFFFFF"
        val color = getThemeBgColor(themeKey, customHex)
        view.setBackgroundColor(color)
    }
}
