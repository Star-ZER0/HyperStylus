package cc.star0.xposed.hyperstylus.ui.apps

import java.text.Collator


internal enum class AppSortOrder { NAME, PACKAGE }

/** Keep configured packages discoverable while applying the same rank-before-sort rule as KernelSU. */
internal object AppListPresentation {
    fun entries(catalog: AppCatalogResult?, saved: Set<String>): List<AppEntry> {
        val installed = catalog?.apps.orEmpty()
        val packages = installed.mapTo(HashSet()) { it.packageName }
        val installationKnown = catalog != null && !catalog.accessDenied && !catalog.failed
        return installed + saved.filter { it !in packages }.sorted().map { AppEntry(it, it, installed = !installationKnown) }
    }

    fun visible(
        apps: List<AppEntry>,
        saved: Set<String>,
        selected: Set<String>,
        query: String,
        showSystem: Boolean,
        order: AppSortOrder,
        reversed: Boolean,
        configuredFirst: Boolean,
    ): List<AppEntry> {
        val collator = Collator.getInstance()
        val alphabetical = Comparator<AppEntry> { left, right -> collator.compare(left.label, right.label) }
        val byValue = when (order) {
            AppSortOrder.NAME -> alphabetical
            AppSortOrder.PACKAGE -> compareBy(AppEntry::packageName)
        }.thenBy(AppEntry::packageName)
        val secondary = if (reversed) byValue.reversed() else byValue
        fun rank(app: AppEntry): Int = when (app.packageName) {
            in selected -> 0
            in saved -> 1
            else -> 2
        }
        return apps.filter { app ->
            (showSystem || !app.system || app.packageName in saved) &&
                (app.label.contains(query.trim(), true) || app.packageName.contains(query.trim(), true))
        }.sortedWith(Comparator { left, right ->
            val difference = if (configuredFirst) rank(left) - rank(right) else 0
            if (difference != 0) difference else secondary.compare(left, right)
        })
    }
}
