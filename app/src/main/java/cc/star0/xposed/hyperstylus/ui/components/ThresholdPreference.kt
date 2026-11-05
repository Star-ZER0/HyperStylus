package cc.star0.xposed.hyperstylus.ui.components

import androidx.compose.runtime.*

import cc.star0.xposed.hyperstylus.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.RangeSliderPreference

/** Integer snapping is relative to the lower bound, including negative RSSI values. */
internal class ThresholdSelection(private val bounds: IntRange, private val gap: Int, private val step: Int) {
    fun constrain(previous: IntRange, proposed: ClosedFloatingPointRange<Float>): IntRange {
        fun snap(value: Float) = bounds.first + ((value - bounds.first) / step).roundToInt() * step
        var low = snap(proposed.start).coerceIn(bounds.first, bounds.last - gap)
        var high = snap(proposed.endInclusive).coerceIn(bounds.first + gap, bounds.last)
        if (high - low < gap) {
            if (low != previous.first) low = high - gap else high = low + gap
        }
        return low..high
    }

    fun adjust(value: IntRange, start: Boolean, direction: Int): IntRange {
        val old = if (start) value.first else value.last
        val position = (old - bounds.first).toFloat() / step
        val index = if (direction > 0) floor(position).toInt() + 1 else ceil(position).toInt() - 1
        val next = (bounds.first + index * step).toFloat()
        return constrain(value, if (start) next..value.last.toFloat() else value.first.toFloat()..next)
    }
}

/** A drag stays local; only the complete validated pair is persisted when interaction ends. */
@Composable
internal fun ThresholdPreference(
    value: IntRange,
    bounds: IntRange,
    gap: Int,
    step: Int,
    title: String,
    startLabel: String,
    endLabel: String,
    unit: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCommit: (IntRange) -> Unit,
) {
    val selection = remember(bounds, gap, step) { ThresholdSelection(bounds, gap, step) }
    var draft by remember(value, enabled) { mutableStateOf(value) }
    val description = "$startLabel ${draft.first}$unit · $endLabel ${draft.last}$unit"
    fun commit(pair: IntRange): Boolean {
        draft = value
        if (!enabled || pair == value) return false
        onCommit(pair)
        return true
    }
    val accessible = if (!enabled) emptyList() else listOf(
        CustomAccessibilityAction(stringResource(R.string.range_decrease, startLabel)) { commit(selection.adjust(value, true, -1)) },
        CustomAccessibilityAction(stringResource(R.string.range_increase, startLabel)) { commit(selection.adjust(value, true, 1)) },
        CustomAccessibilityAction(stringResource(R.string.range_decrease, endLabel)) { commit(selection.adjust(value, false, -1)) },
        CustomAccessibilityAction(stringResource(R.string.range_increase, endLabel)) { commit(selection.adjust(value, false, 1)) },
    )
    RangeSliderPreference(title = title, summary = description,
        value = draft.first.toFloat()..draft.last.toFloat(),
        valueRange = bounds.first.toFloat()..bounds.last.toFloat(), steps = (bounds.last - bounds.first) / step - 1,
        enabled = enabled, onValueChange = { if (enabled) draft = selection.constrain(draft, it) },
        onValueChangeFinished = { commit(draft) },
        modifier = modifier.semantics(mergeDescendants = true) {
            stateDescription = description
            customActions = accessible
            if (!enabled) disabled()
        },
    )
}
