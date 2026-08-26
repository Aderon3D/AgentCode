package com.agent.code.ui

// §6.2 Development_Doc.md — Geometric Layout Oracle (M4 UI Testing Oracle).
// Pure Kotlin; no Android deps, fully host-testable.

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun intersects(other: Rect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun isOutOfBounds(container: Rect): Boolean =
        left < container.left || right > container.right || top < container.top || bottom > container.bottom
}

data class UiElementNode(
    val id: String?,
    val bounds: Rect,
    val parentBounds: Rect,
    val isDecorative: Boolean
)

sealed interface LayoutBug {
    data class Clipping(val elementId: String, val bounds: Rect, val parentBounds: Rect) : LayoutBug
    data class Overlap(
        val elementA: String?,
        val elementB: String?,
        val boundsA: Rect,
        val boundsB: Rect
    ) : LayoutBug
}

class GeometricLayoutOracle {
    fun verifyLayoutCorrectness(elements: List<UiElementNode>): List<LayoutBug> {
        val detectedBugs = mutableListOf<LayoutBug>()

        for (element in elements) {
            if (element.bounds.isOutOfBounds(element.parentBounds)) {
                detectedBugs.add(
                    LayoutBug.Clipping(
                        element.id ?: "anonymous",
                        element.bounds,
                        element.parentBounds
                    )
                )
            }
        }

        for (i in elements.indices) {
            for (j in i + 1 until elements.size) {
                val a = elements[i]
                val b = elements[j]
                if (!a.isDecorative && !b.isDecorative && a.bounds.intersects(b.bounds)) {
                    detectedBugs.add(LayoutBug.Overlap(a.id, b.id, a.bounds, b.bounds))
                }
            }
        }
        return detectedBugs
    }
}
