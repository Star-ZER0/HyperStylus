package cc.star0.xposed.hyperstylus.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup

/** The first row owns the submenu; equal widths keep its expansion within the primary bounds. */
@Composable
internal fun AppListOptionsPopup(
    show: Boolean,
    entries: List<DropdownEntry>,
    scaffoldBounds: IntRect,
    anchorBounds: IntRect,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
    val gap = with(density) { 8.dp.roundToPx() }
    val left = maxOf(scaffoldBounds.left, insets.getLeft(density, direction)) + gap
    val top = maxOf(scaffoldBounds.top, insets.getTop(density)) + gap
    val right = minOf(scaffoldBounds.right, windowSize.width - insets.getRight(density, direction)) - gap
    val bottom = minOf(scaffoldBounds.bottom, windowSize.height - insets.getBottom(density)) - gap
    val safeBounds = IntRect(left, top, maxOf(left, right), maxOf(top, bottom))
    val popupTop = maxOf(safeBounds.top, anchorBounds.bottom + gap)
    val availableHeight = (safeBounds.bottom - popupTop).coerceAtLeast(0)
    val minimumHeight = with(density) { 50.dp.roundToPx() }
    val valid = windowSize.width > 0 && windowSize.height > 0 &&
        scaffoldBounds.width > 0 && scaffoldBounds.height > 0 &&
        anchorBounds.width > 0 && anchorBounds.height > 0 &&
        safeBounds.width > 0 && availableHeight >= minimumHeight

    // The native popup retains its row anchor while expanded. Recreate it on geometry changes,
    // preserving the caller's open intention and returning to the primary menu after a resize.
    if (!valid) return
    key(safeBounds, anchorBounds) {
        val positionProvider = remember(safeBounds, anchorBounds, popupTop) {
            AppListPopupPositionProvider(safeBounds, anchorBounds, popupTop)
        }
        val padding = with(density) {
            PaddingValues(
                start = (if (direction == LayoutDirection.Ltr) safeBounds.left - scaffoldBounds.left
                    else scaffoldBounds.right - safeBounds.right).coerceAtLeast(0).toDp(),
                top = (safeBounds.top - scaffoldBounds.top).coerceAtLeast(0).toDp(),
                end = (if (direction == LayoutDirection.Ltr) scaffoldBounds.right - safeBounds.right
                    else safeBounds.left - scaffoldBounds.left).coerceAtLeast(0).toDp(),
                bottom = (scaffoldBounds.bottom - safeBounds.bottom).coerceAtLeast(0).toDp(),
            )
        }
        OverlayCascadingListPopup(
            show = show,
            entries = entries,
            onDismissRequest = onDismiss,
            popupModifier = Modifier.padding(padding).testTag("app_options_popup"),
            popupPositionProvider = positionProvider,
            minWidth = with(density) { minOf(safeBounds.width.toDp(), 288.dp) },
            maxHeight = with(density) { availableHeight.toDp() },
            renderInRootScaffold = false,
            collapseOnSelection = false,
        )
    }
}

private class AppListPopupPositionProvider(
    private val safeBounds: IntRect,
    private val anchor: IntRect,
    private val popupTop: Int,
) : PopupPositionProvider {
    override fun getMargins(): PaddingValues = PaddingValues(0.dp)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val preferredLeft = if (layoutDirection == LayoutDirection.Ltr) {
            anchor.right - popupContentSize.width
        } else {
            anchor.left
        }
        return IntOffset(
            x = preferredLeft.coerceIn(safeBounds.left,
                maxOf(safeBounds.left, safeBounds.right - popupContentSize.width)),
            y = popupTop.coerceIn(safeBounds.top,
                maxOf(safeBounds.top, safeBounds.bottom - popupContentSize.height)),
        )
    }
}
