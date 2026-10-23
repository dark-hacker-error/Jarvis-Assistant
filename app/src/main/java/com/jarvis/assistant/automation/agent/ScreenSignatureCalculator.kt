package com.jarvis.assistant.automation.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Calculates lightweight, stable screen signatures to detect state transitions,
 * scrolling progress, end-of-list conditions, and verify UI mutations.
 */
object ScreenSignatureCalculator {

    fun computeSignature(root: AccessibilityNodeInfo?): ScreenSignature {
        if (root == null) {
            return ScreenSignature(
                packageName = "",
                className = "",
                elementCount = 0,
                contentHash = 0L,
                primaryTexts = emptyList()
            )
        }

        val pkg = root.packageName?.toString() ?: ""
        val cls = root.className?.toString() ?: ""
        val texts = mutableListOf<String>()
        var count = 0
        var hash = 1125899906842597L // FNV-like seed

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val tempRect = Rect()

        while (queue.isNotEmpty() && count < 300) {
            val node = queue.removeFirst()
            count++

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.toString() ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable

            node.getBoundsInScreen(tempRect)

            if (text.isNotEmpty() && texts.size < 15) {
                texts.add(text)
            } else if (desc.isNotEmpty() && texts.size < 15) {
                texts.add(desc)
            }

            // Mix into hash
            hash = 31 * hash + text.hashCode()
            hash = 31 * hash + desc.hashCode()
            hash = 31 * hash + viewId.hashCode()
            hash = 31 * hash + (if (isClickable) 1 else 0)
            hash = 31 * hash + (if (isEditable) 2 else 0)
            hash = 31 * hash + tempRect.centerX()
            hash = 31 * hash + tempRect.centerY()

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        return ScreenSignature(
            packageName = pkg,
            className = cls,
            elementCount = count,
            contentHash = hash,
            primaryTexts = texts
        )
    }

    /**
     * Generates a stable key for an individual interactive element (e.g. for bulk tracking).
     */
    fun computeElementKey(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        return "${id}_${text}_${desc}_(${rect.left},${rect.top})"
    }
}
