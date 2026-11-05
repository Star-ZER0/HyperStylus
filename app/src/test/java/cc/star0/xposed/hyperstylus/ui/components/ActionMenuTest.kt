package cc.star0.xposed.hyperstylus.ui.components

import androidx.test.core.app.ApplicationProvider

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.ActionConfig
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionMenuTest {
    @Test
    fun labelsCorrespondToCurrentActionValuesAndTheKeyboardPicker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val labels = context.resources.getStringArray(R.array.action_labels)
        val actions = ActionConfig.ACTIONS
        assertEquals(actions.size, labels.size)
        assertEquals("键盘自选按键", labels[actions.indexOf(ActionConfig.KEYBOARD)])
        assertEquals("无操作", labels[actions.indexOf(ActionConfig.NONE)])
        assertEquals("系统默认", labels[actions.indexOf(ActionConfig.DEFAULT)])
        assertEquals(24, actions.size)
    }
}
