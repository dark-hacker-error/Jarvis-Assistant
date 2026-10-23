package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import android.widget.RadioGroup
import android.widget.Spinner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

class OverlayAvatarActivity : AppCompatActivity() {
    private lateinit var avatarGallery: WebView
    private lateinit var overlaySwitch: SwitchCompat
    private val preferences get() = JarvisApp.instance.preferences
    private lateinit var assetLoader: WebViewAssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_avatar)
        ThemeManager.applyDynamicBackground(findViewById(R.id.overlayAvatarRoot))

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        overlaySwitch = findViewById(R.id.switchOverlayEnabled)
        overlaySwitch.isChecked = preferences.isTaskOverlayEnabled
        val modeGroup = findViewById<RadioGroup>(R.id.radioOverlayMode)
        modeGroup.check(if (preferences.taskOverlayMode == "working") R.id.rbOverlayWorking else R.id.rbOverlayAlways)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            preferences.taskOverlayMode = if (checkedId == R.id.rbOverlayWorking) "working" else "always"
        }
        val edgeSpinner = findViewById<Spinner>(R.id.spinnerOverlayEdge)
        val edgeValues = listOf("right", "left", "top", "bottom")
        var edgeSelectionReady = false
        edgeSpinner.setSelection(edgeValues.indexOf(preferences.taskOverlayEdge).coerceAtLeast(0))
        edgeSpinner.post { edgeSelectionReady = true }
        modeGroup.isEnabled = preferences.isTaskOverlayEnabled
        edgeSpinner.isEnabled = preferences.isTaskOverlayEnabled
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = preferences.isTaskOverlayEnabled
        edgeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (edgeSelectionReady) edgeValues.getOrNull(position)?.let {
                    preferences.taskOverlayFreePosition = false
                    preferences.taskOverlayEdge = it
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        overlaySwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.isTaskOverlayEnabled = enabled
            modeGroup.isEnabled = enabled
            edgeSpinner.isEnabled = enabled
            for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
        }
        avatarGallery = findViewById(R.id.avatarGallery)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        setupGallery()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupGallery() {
        avatarGallery.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        avatarGallery.settings.javaScriptEnabled = true
        avatarGallery.settings.domStorageEnabled = true
        avatarGallery.settings.allowFileAccess = false
        avatarGallery.addJavascriptInterface(AvatarPickerBridge(), "JarvisAvatarPicker")
        avatarGallery.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                selectInGallery(preferences.taskOverlayAvatar)
                avatarGallery.evaluateJavascript(
                    "window.setGalleryTheme && window.setGalleryTheme('#${themeColor(R.attr.themeTextPrimary)}', '#${themeColor(R.attr.themeAccent)}')",
                    null
                )
            }
        }
        avatarGallery.loadUrl("https://appassets.androidplatform.net/assets/overlay-toys/gallery.html")
    }

    private fun selectInGallery(name: String) {
        avatarGallery.evaluateJavascript("window.setSelectedAvatar && window.setSelectedAvatar('$name')", null)
    }

    private fun themeColor(attribute: Int): String {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return String.format(java.util.Locale.US, "%06X", 0xFFFFFF and value.data)
    }

    private inner class AvatarPickerBridge {
        @JavascriptInterface
        fun pageReady() {
            runOnUiThread { selectInGallery(preferences.taskOverlayAvatar) }
        }

        @JavascriptInterface
        fun selectAvatar(name: String) {
            runOnUiThread {
                preferences.taskOverlayAvatar = name
                selectInGallery(preferences.taskOverlayAvatar)
            }
        }
    }

    override fun onDestroy() {
        if (::avatarGallery.isInitialized) {
            avatarGallery.removeJavascriptInterface("JarvisAvatarPicker")
            avatarGallery.stopLoading()
            avatarGallery.destroy()
        }
        super.onDestroy()
    }
}
