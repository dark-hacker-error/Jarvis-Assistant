package com.jarvis.assistant.vision

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlin.math.abs

/**
 * Structured representation of an interactive or visible UI element
 * extracted directly from the Android Accessibility Tree with exact pixel bounds.
 */
data class UiElementInfo(
    val id: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
) {
    val center: PointF
        get() = PointF(bounds.exactCenterX(), bounds.exactCenterY())

    val primaryLabel: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            viewId.isNotBlank() -> viewId.substringAfterLast("/")
            else -> className.substringAfterLast(".")
        }

    fun isSwitchOrToggle(): Boolean {
        return isCheckable || className.contains("Switch", ignoreCase = true) ||
                className.contains("CheckBox", ignoreCase = true) ||
                className.contains("ToggleButton", ignoreCase = true)
    }

    val hasTextOrDesc: Boolean
        get() = text.isNotBlank() || contentDescription.isNotBlank()

    val isImageOrThumbnail: Boolean
        get() {
            val simpleClass = className.substringAfterLast(".").lowercase(Locale.ROOT)
            val simpleVid = viewId.substringAfterLast("/").lowercase(Locale.ROOT)
            val lbl = primaryLabel.lowercase(Locale.ROOT)

            val isImgClass = simpleClass.contains("image") || simpleClass.contains("photo") || simpleClass.contains("thumb") || simpleClass.contains("picture")
            val isImgVid = simpleVid.contains("thumb") || simpleVid.contains("image") || simpleVid.contains("photo") || simpleVid.contains("pic") || simpleVid.contains("icon_thumbnail")
            val isImgLabel = lbl.endsWith(".jpg") || lbl.endsWith(".jpeg") || lbl.endsWith(".png") || lbl.endsWith(".webp") ||
                    lbl.startsWith("img_") || lbl.startsWith("pxl_") || lbl.startsWith("photo") || lbl == "image" || lbl == "photo"

            return (isImgClass || isImgVid || isImgLabel) && bounds.width() >= 60 && bounds.height() >= 60
        }

    val isGenericContainer: Boolean
        get() {
            if (hasTextOrDesc) return false
            if (isImageOrThumbnail) return false
            val simple = className.substringAfterLast(".").lowercase(Locale.ROOT)
            return simple in GENERIC_CONTAINERS
        }

    companion object {
        val GENERIC_CONTAINERS = setOf(
            "framelayout", "linearlayout", "relativelayout", "viewgroup",
            "view", "viewpager", "recyclerview", "scrollview", "nestedscrollview",
            "cardview", "coordinatorlayout", "constraintlayout"
        )
    }
}

/**
 * Dual-Anchor UI Grounding Engine.
 * Traverses Android's native Accessibility hierarchy and maps high-level
 * visual intents and spoken targets directly to hardware-accurate pixel bounding boxes.
 */
class UiGroundingEngine {

    companion object {
        private const val TAG = "UiGroundingEngine"
    }

    /**
     * Extracts all interactive or descriptive elements currently visible on the screen.
     */
    fun scanScreen(root: AccessibilityNodeInfo?): List<UiElementInfo> {
        if (root == null) return emptyList()
        val elements = mutableListOf<UiElementInfo>()
        var nextId = 1

        fun traverse(node: AccessibilityNodeInfo) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            var text = node.text?.toString()?.trim() ?: ""
            var desc = node.contentDescription?.toString()?.trim() ?: ""
            val cls = node.className?.toString() ?: ""
            val vid = node.viewIdResourceName ?: ""
            var p = node.parent
            var parentClickable = false
            var depth = 0
            while (p != null && depth < 3) {
                if (p.isClickable) {
                    parentClickable = true
                    break
                }
                p = p.parent
                depth++
            }
            val isClickable = node.isClickable || parentClickable
            val isCheckable = node.isCheckable
            val hasSetTextAction = node.actionList.any { 
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE 
            }
            val isEditable = node.isEditable || cls.contains("EditText", ignoreCase = true) || hasSetTextAction
            val isScrollable = node.isScrollable

            // If a clickable row/card has no direct text, extract combined text from its descendants
            if (text.isBlank() && desc.isBlank() && node.childCount > 0 && isClickable) {
                val childTexts = collectNodeText(node)
                if (childTexts.isNotEmpty()) {
                    text = childTexts.joinToString(" • ")
                }
            }

            // Filter out 0-sized or offscreen invisible elements
            val isVisible = bounds.width() > 5 && bounds.height() > 5

            val isInteractiveOrMeaningful = isVisible && (
                isClickable || isCheckable || isEditable || isScrollable ||
                text.isNotBlank() || desc.isNotBlank()
            )

            if (isInteractiveOrMeaningful) {
                elements.add(
                    UiElementInfo(
                        id = nextId++,
                        text = text,
                        contentDescription = desc,
                        className = cls,
                        viewId = vid,
                        bounds = bounds,
                        isClickable = isClickable,
                        isCheckable = isCheckable,
                        isChecked = node.isChecked,
                        isEditable = isEditable,
                        isScrollable = isScrollable
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child)
            }
        }

        try {
            traverse(root)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree: ${e.message}")
        }

        return elements
    }

    /**
     * Resolves the target element with 100% precision:
     * 1. If explicit element ID is supplied, finds that element.
     * 2. If target is a toggle/switch for a specific setting (e.g. "Wireless Debugging"),
     *    locates the label first and snaps to the adjacent switch on the same row.
     * 3. Performs fuzzy semantic match across text, content description, and view IDs.
     */
    /**
     * True when the element's visible label plausibly matches what the model asked for.
     * A blank label (icon-only button) cannot be disproven, so it is accepted.
     */
    private fun labelMatchesName(el: UiElementInfo, queryLower: String): Boolean {
        val labels = listOf(el.text, el.contentDescription, el.viewId.substringAfterLast("/"))
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) return true
        val qTokens = queryLower.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        return labels.any { label ->
            label.contains(queryLower) ||
                (label.length >= 3 && queryLower.contains(label)) ||
                qTokens.any { t -> label.contains(t) }
        }
    }

    fun findTargetElement(
        elements: List<UiElementInfo>,
        targetName: String,
        isToggleIntent: Boolean = false,
        elementId: Int? = null
    ): UiElementInfo? {
        if (elementId != null && elementId > 0) {
            val byId = elements.firstOrNull { it.id == elementId }
            if (byId != null) {
                val q = targetName.trim().lowercase(Locale.ROOT)
                if (q.isBlank() || labelMatchesName(byId, q)) return byId
                // The number now points at something else: the screen changed since inspect_screen.
                // Do NOT blindly tap it. Fall through to a match by label instead.
                Log.w(TAG, "element_id=$elementId now points to '${byId.primaryLabel}' but target_name='$targetName'. " +
                    "Screen changed since inspect_screen; matching by label instead of tapping the id.")
            }
        }

        val query = targetName.trim().lowercase(Locale.ROOT)
        if (query.isBlank()) return null

        // On Flow itself, brand text is page copy, not a navigation result. Never route
        // a request for Google Flow to the page's own banner/help text.
        if (isGoogleFlowSurface(elements) && query.contains("google flow") &&
            !query.contains("new project") && !query.contains("open project")) {
            return null
        }

        val cleanQuery = query.replace("[^a-zA-Z0-9]".toRegex(), "")
        val strippedQuery = query
            .replace("\\b(channel|group|folder|chat|app|page|tab|telegram|whatsapp|file)\\b".toRegex(RegexOption.IGNORE_CASE), "")
            .trim()
        val cleanStrippedQuery = strippedQuery.replace("[^a-zA-Z0-9]".toRegex(), "")

        // Explicit button clicks (send/generate/submit/enter) — NEVER route to input fields
        val isExplicitButtonQuery = query.contains("send button") || query.contains("generate button") ||
                query.contains("submit button") || query.contains("enter button") ||
                query.contains("create button") || query.contains("go button") ||
                query == "send" || query == "generate" || query == "submit" ||
                query == "enter" || query == "done" || query == "run" ||
                query.contains("click send") || query.contains("press send") ||
                query.contains("press enter") || query.contains("press generate")

        val isExplicitSearchBox = !isExplicitButtonQuery && (
                query == "search" || query == "search bar" || query == "search box" ||
                query == "search input" || query == "search field" || query == "type" || query == "url bar" || query == "address bar" ||
                query == "input box" || query == "search query" || query == "text box" ||
                query.contains("prompt") || query.contains("creation") || query.contains("describe") || query.contains("input")
        )

        // Case 0: Explicit input/search/prompt intent (ONLY when targeting an input field, exclude URL bar)
        if (isExplicitSearchBox) {
            // 1. Direct match on web prompt placeholders (like Google Flow: "What do you want to create?")
            val webPromptPlaceholder = elements.firstOrNull {
                val label = it.primaryLabel.lowercase(Locale.ROOT)
                (label.contains("what do you want to create") ||
                 label.contains("describe your") ||
                 label.contains("type a prompt") ||
                 label.contains("enter a prompt") ||
                 label.contains("enter prompt") ||
                 label.contains("ask gemini") ||
                 label.contains("search or type")) &&
                !it.viewId.contains("url_bar", ignoreCase = true) &&
                !it.viewId.contains("omnibox", ignoreCase = true)
            }
            if (webPromptPlaceholder != null) {
                Log.i(TAG, "Grounded web prompt placeholder input: '${webPromptPlaceholder.primaryLabel}' (id=${webPromptPlaceholder.id})")
                return webPromptPlaceholder
            }

            val nonUrlInputs = elements.filter {
                (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                !it.viewId.contains("url_bar", ignoreCase = true) &&
                !it.viewId.contains("omnibox", ignoreCase = true)
            }
            if (nonUrlInputs.isNotEmpty()) {
                val promptInput = nonUrlInputs.firstOrNull {
                    it.text.contains("prompt", ignoreCase = true) ||
                    it.contentDescription.contains("prompt", ignoreCase = true) ||
                    it.text.contains("describe", ignoreCase = true) ||
                    it.contentDescription.contains("describe", ignoreCase = true) ||
                    it.viewId.contains("prompt", ignoreCase = true) ||
                    it.viewId.contains("input", ignoreCase = true)
                }
                return promptInput ?: nonUrlInputs.first()
            }
            elements.firstOrNull { it.isEditable && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }?.let { return it }
        }

        // Case 1: Toggle/Switch intent for a labeled item (e.g. "enable Wireless Debugging")
        if (isToggleIntent) {
            // Find the label element first
            val labelElement = elements.filter {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                text.contains(query) || desc.contains(query) || 
                (cleanQuery.isNotEmpty() && cleanText.contains(cleanQuery)) ||
                query.contains(text.takeIf { t -> t.length > 3 } ?: "____")
            }.minByOrNull {
                abs(it.primaryLabel.length - query.length)
            }

            if (labelElement != null) {
                val adjacentSwitch = elements.filter {
                    it.isSwitchOrToggle() &&
                    abs(it.bounds.centerY() - labelElement.bounds.centerY()) < 150 &&
                    it.bounds.centerX() >= labelElement.bounds.centerX()
                }.minByOrNull {
                    abs(it.bounds.centerY() - labelElement.bounds.centerY())
                }

                if (adjacentSwitch != null) {
                    Log.i(TAG, "Grounded toggle intent: matched adjacent switch ${adjacentSwitch.id} for label '${labelElement.text}'")
                    return adjacentSwitch
                }

                if (labelElement.isClickable || labelElement.isCheckable) {
                    return labelElement
                }
            }
        }

        // Case 1.5: Specialized Web Search Result Links Priority
        // When searching on Google / Web / Store, search result links below the search box (bounds.top > 200)
        // MUST take absolute precedence over the search bar or address bar!
        val resultLinks = if (isGoogleSearchResults(elements)) elements.filter {
            !it.isEditable && it.bounds.top > 200 && (it.isClickable || it.bounds.top > 250) &&
                (it.text.isNotBlank() || it.contentDescription.isNotBlank())
        } else emptyList()
        if (resultLinks.isNotEmpty()) {
            // Exact / normalized match on result links
            resultLinks.firstOrNull {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                val cleanDesc = desc.replace("[^a-zA-Z0-9]".toRegex(), "")

                text == query || desc == query ||
                (cleanQuery.isNotEmpty() && (cleanText == cleanQuery || cleanDesc == cleanQuery)) ||
                (cleanStrippedQuery.isNotEmpty() && (cleanText == cleanStrippedQuery || cleanDesc == cleanStrippedQuery))
            }?.let {
                Log.i(TAG, "Grounded search result link exact match: '${it.primaryLabel}' (id=${it.id})")
                return it
            }

            // Substring containment on result links
            resultLinks.filter {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                val cleanDesc = desc.replace("[^a-zA-Z0-9]".toRegex(), "")

                (cleanQuery.length >= 3 && (cleanText.contains(cleanQuery) || cleanQuery.contains(cleanText.takeIf { t -> t.length >= 3 } ?: "____"))) ||
                (cleanStrippedQuery.length >= 3 && (cleanText.contains(cleanStrippedQuery) || cleanStrippedQuery.contains(cleanText.takeIf { t -> t.length >= 3 } ?: "____")))
            }.minByOrNull { it.primaryLabel.length }?.let {
                Log.i(TAG, "Grounded search result link substring match: '${it.primaryLabel}' (id=${it.id})")
                return it
            }

            // Token overlap on result links
            val queryTokens = query.split("\\s+".toRegex()).filter { it.length > 2 }
            if (queryTokens.isNotEmpty()) {
                val bestResultLink = resultLinks.filter { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.any { token -> elText.contains(token) }
                }.maxByOrNull { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.count { token -> elText.contains(token) }
                }
                if (bestResultLink != null) {
                    Log.i(TAG, "Grounded search result link token overlap: '${bestResultLink.primaryLabel}' (id=${bestResultLink.id})")
                    return bestResultLink
                }
            }
        }

        // Tiered matching candidate lists:
        // Tier 1: Clickable non-editable interactive items (links, buttons, list cards, icons)
        // Tier 2: Non-editable content items (headings, labels, descriptions)
        // Tier 3: Editable inputs (search bars, text fields - ONLY as a last resort)
        val tiers = listOf(
            elements.filter { !it.isEditable && (it.isClickable || it.isCheckable) },
            elements.filter { !it.isEditable },
            elements.filter { it.isEditable }
        )

        // Case 2: Exact text or Normalized match with non-editable priority
        for (tier in tiers) {
            tier.firstOrNull {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                val cleanDesc = desc.replace("[^a-zA-Z0-9]".toRegex(), "")

                text == query || desc == query ||
                (cleanQuery.isNotEmpty() && (cleanText == cleanQuery || cleanDesc == cleanQuery)) ||
                (cleanStrippedQuery.isNotEmpty() && (cleanText == cleanStrippedQuery || cleanDesc == cleanStrippedQuery))
            }?.let { return it }
        }

        // Case 3: Normalized Substring Containment with non-editable priority
        for (tier in tiers) {
            tier.filter {
                val text = it.text.lowercase(Locale.ROOT)
                val desc = it.contentDescription.lowercase(Locale.ROOT)
                val cleanText = text.replace("[^a-zA-Z0-9]".toRegex(), "")
                val cleanDesc = desc.replace("[^a-zA-Z0-9]".toRegex(), "")

                (cleanQuery.length >= 3 && (cleanText.contains(cleanQuery) || cleanQuery.contains(cleanText.takeIf { t -> t.length >= 3 } ?: "____"))) ||
                (cleanStrippedQuery.length >= 3 && (cleanText.contains(cleanStrippedQuery) || cleanStrippedQuery.contains(cleanText.takeIf { t -> t.length >= 3 } ?: "____")))
            }.minWithOrNull(
                compareBy<UiElementInfo> { element ->
                    val text = element.text.lowercase(Locale.ROOT).replace("[^a-zA-Z0-9]".toRegex(), "")
                    val desc = element.contentDescription.lowercase(Locale.ROOT).replace("[^a-zA-Z0-9]".toRegex(), "")
                    if (text == cleanQuery || desc == cleanQuery) 0 else element.primaryLabel.length
                }.thenBy { it.bounds.width().toLong() * it.bounds.height().toLong() }
            )?.let { return it }
        }

        // Case 4: Token/Word Overlap with non-editable priority
        val queryTokens = query.split("\\s+".toRegex()).filter { it.length > 2 }
        if (queryTokens.isNotEmpty()) {
            for (tier in tiers) {
                val bestTokenMatch = tier.filter { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.any { token -> elText.contains(token) }
                }.maxByOrNull { el ->
                    val elText = el.primaryLabel.lowercase(Locale.ROOT)
                    queryTokens.count { token -> elText.contains(token) }
                }
                if (bestTokenMatch != null) {
                    return bestTokenMatch
                }
            }
        }

        // Case 6: Spatial position or generic image matching (e.g. "image at top-center", "first photo", "photo at top right")
        val spatialTerms = listOf(
            "top-left", "top left", "top-center", "top center", "top-right", "top right",
            "mid-left", "mid left", "mid-center", "mid center", "mid-right", "mid right",
            "bottom-left", "bottom left", "bottom-center", "bottom center", "bottom-right", "bottom right"
        )
        val combinedText = "$query $cleanStrippedQuery".lowercase(Locale.ROOT)
        for (spatial in spatialTerms) {
            if (combinedText.contains(spatial)) {
                val normSpatial = spatial.replace(" ", "-")
                val matched = elements.filter { it.isImageOrThumbnail }.firstOrNull { el ->
                    val elSpatial = getSpatialPosition(el.bounds, 1080, 2400).lowercase(Locale.ROOT)
                    elSpatial == normSpatial
                }
                if (matched != null) return matched
            }
        }

        if (query == "image" || query == "photo" || query == "thumbnail" || query == "picture" ||
            query.contains("first photo") || query.contains("first image")) {
            elements.firstOrNull { it.isImageOrThumbnail }?.let { return it }
        }

        return null
    }

    fun extractScreenContext(root: AccessibilityNodeInfo?, elements: List<UiElementInfo>): Pair<String, String> {
        val pkg = root?.packageName?.toString() ?: ""
        val titleCandidate = elements
            .filter { it.text.isNotBlank() && it.bounds.top in 60..380 && it.bounds.height() in 20..220 && it.text.length in 3..60 }
            .minByOrNull { it.bounds.top }
            ?.text ?: ""
        return Pair(pkg, titleCandidate)
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 3) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val ct = child.text?.toString()?.trim() ?: child.contentDescription?.toString()?.trim() ?: ""
            if (ct.isNotBlank() && !list.contains(ct)) {
                list.add(ct)
            }
            if (child.childCount > 0) {
                list.addAll(collectNodeText(child, depth + 1))
            }
        }
        return list
    }

    fun getSpatialPosition(bounds: Rect, screenWidth: Int, screenHeight: Int): String {
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        val vPos = when {
            cy < screenHeight * 0.35f -> "Top"
            cy < screenHeight * 0.65f -> "Mid"
            else -> "Bottom"
        }

        val hPos = when {
            cx < screenWidth * 0.33f -> "Left"
            cx < screenWidth * 0.67f -> "Center"
            else -> "Right"
        }

        return "$vPos-$hPos"
    }

    /**
     * Compact summary of visible interactive elements to attach to prompt or context.
     */
    fun formatElementsSummary(elements: List<UiElementInfo>, root: AccessibilityNodeInfo? = null): String {
        val sb = StringBuilder()
        val (pkg, title) = extractScreenContext(root, elements)
        if (pkg.isNotBlank()) sb.append("App: $pkg | ")
        if (title.isNotBlank()) sb.append("Screen Title: \"$title\"\n") else if (pkg.isNotBlank()) sb.append("\n")

        sb.append("Visible Elements:\n")
        val meaningful = elements.filter {
            (it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.isEditable || it.isCheckable || (it.isClickable && it.isImageOrThumbnail)) &&
            !it.isGenericContainer
        }

        val screenBounds = Rect()
        root?.getBoundsInScreen(screenBounds)
        val screenWidth = if (screenBounds.width() > 0) screenBounds.width() else elements.maxOfOrNull { it.bounds.right } ?: 1080
        val screenHeight = if (screenBounds.height() > 0) screenBounds.height() else elements.maxOfOrNull { it.bounds.bottom } ?: 2400

        val isWebSearchPage = (pkg.contains("chrome", ignoreCase = true) || pkg.contains("browser", ignoreCase = true)) &&
            isGoogleSearchResults(elements)

        if (isWebSearchPage) {
            sb.append("⚠️ [CURRENT VIEW: WEB SEARCH RESULTS]\n")
            sb.append("RULE: Do NOT type your generation/creative prompt into the Google search bar! Look at the clickable website result links below and click the matching result to enter the website.\n")
        }

        for (el in meaningful.take(75)) {
            val label = if (el.isImageOrThumbnail && (el.primaryLabel.isBlank() || el.primaryLabel.contains("ImageView", ignoreCase = true))) {
                "Image Thumbnail"
            } else {
                el.primaryLabel
            }

            if (isWebSearchPage && el.isEditable) {
                sb.append("[#${el.id}] Search / URL Bar (InputField - DO NOT TYPE CREATIVE PROMPTS HERE)\n")
                continue
            } else if (isWebSearchPage && el.isClickable && !el.isEditable && el.bounds.top > 250 && el.text.isNotBlank()) {
                sb.append("[#${el.id}] Search Result Link: \"$label\" (Clickable - Tap to open site)\n")
                continue
            }

            sb.append("[#${el.id}] $label")
            if (el.isSwitchOrToggle()) {
                sb.append(" (Switch: ${if (el.isChecked) "ON" else "OFF"})")
            } else if (el.isEditable) {
                sb.append(" (InputField)")
            } else if (el.isImageOrThumbnail) {
                val spatial = getSpatialPosition(el.bounds, screenWidth, screenHeight)
                sb.append(" (Position: $spatial, bounds=[${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}])")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    /** Flow pages contain their own search and project controls; they are not web search results. */
    fun isGoogleFlowSurface(elements: List<UiElementInfo>): Boolean {
        val urlText = elements.asSequence()
            .filter { it.viewId.contains("url_bar", ignoreCase = true) || it.viewId.contains("omnibox", ignoreCase = true) }
            .joinToString(" ") { "${it.text} ${it.contentDescription}" }
            .lowercase(Locale.ROOT)
        if (urlText.contains("flow.google") || urlText.contains("labs.google/fx/tools/flow")) return true

        val textDump = elements.joinToString(" ") {
            "${it.text.lowercase(Locale.ROOT)} ${it.contentDescription.lowercase(Locale.ROOT)}"
        }
        val flowMarkers = listOf(
            "what do you want to create", "start creating or drop media", "long live the process",
            "google flow can make mistakes", "omni 1.1 flash", "generating will use", "new project"
        )
        return flowMarkers.count(textDump::contains) >= 2
    }

    /** Only call a page search results when its actual URL identifies a search query. */
    fun isGoogleSearchResults(elements: List<UiElementInfo>): Boolean {
        if (isGoogleFlowSurface(elements)) return false
        val urlText = elements.asSequence()
            .filter { it.viewId.contains("url_bar", ignoreCase = true) || it.viewId.contains("omnibox", ignoreCase = true) }
            .joinToString(" ") { "${it.text} ${it.contentDescription}" }
            .lowercase(Locale.ROOT)
        val explicitSearchUrl = urlText.contains("google.") &&
            (urlText.contains("/search") || urlText.contains("?q=") || urlText.contains("&q="))
        if (explicitSearchUrl || urlText.contains("google.com/search")) return true
        val textDump = elements.joinToString(" ") {
            "${it.text.lowercase(Locale.ROOT)} ${it.contentDescription.lowercase(Locale.ROOT)}"
        }
        return textDump.contains("google search results") || textDump.contains("search results for")
    }
}
