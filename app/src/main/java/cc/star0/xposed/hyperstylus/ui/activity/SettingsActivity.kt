package cc.star0.xposed.hyperstylus.ui.activity

import android.os.Bundle

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.config.EnhancePreferences
import cc.star0.xposed.hyperstylus.ui.navigation.SettingsNavigator
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import cc.star0.xposed.hyperstylus.ui.state.ModuleServices
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*

/** The activity owns one validated snapshot; every screen edits the same persisted contract. */
class SettingsActivity : ComponentActivity() {
    private var settings by mutableStateOf(EnhanceConfig.defaults())
    private var catalogRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = EnhancePreferences.load(this)
        ModuleServices.configure(settings)
        ModuleServices.initialize(this)
        setContent {
            FocusTheme {
                SettingsNavigator(settings, catalogRevision, ::persist)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settings = EnhancePreferences.load(this)
        catalogRevision++
        ModuleServices.refresh()
        // Re-publish the stored snapshot when a host could not reach the provider at boot.
        try {
            EnhancePreferences.publish(this, settings)
            ModuleServices.configure(settings)
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun persist(values: Bundle) {
        try {
            settings = EnhancePreferences.save(this, values)
            ModuleServices.configure(settings)
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

