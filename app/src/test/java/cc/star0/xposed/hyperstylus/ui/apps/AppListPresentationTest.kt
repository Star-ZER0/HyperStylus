package cc.star0.xposed.hyperstylus.ui.apps

import org.junit.Assert.*

import org.junit.Test

class AppListPresentationTest {
    @Test
    fun configuredSystemAppsStayDiscoverableWhenSystemAppsAreHidden() {
        val apps = listOf(AppEntry("android.saved", "Saved", system = true),
            AppEntry("android.hidden", "Hidden", system = true), AppEntry("user.app", "User"))
        val shown = AppListPresentation.visible(apps, setOf("android.saved"), emptySet(), "", false,
            AppSortOrder.NAME, false, true)
        assertEquals(listOf("android.saved", "user.app"), shown.map(AppEntry::packageName))
    }

    @Test
    fun reverseSortingKeepsActiveAndSavedConfigurationsAheadOfOtherApps() {
        val apps = listOf(AppEntry("normal", "Z"), AppEntry("saved", "A"),
            AppEntry("active.a", "Alpha"), AppEntry("active.z", "Zeta"))
        val shown = AppListPresentation.visible(apps, setOf("saved", "active.a", "active.z"),
            setOf("active.a", "active.z"), "", true, AppSortOrder.NAME, true, true)
        assertEquals(listOf("active.z", "active.a", "saved", "normal"), shown.map(AppEntry::packageName))
    }

    @Test
    fun searchMatchesNamesAndPackagesWithoutDependingOnCapitalization() {
        val apps = listOf(AppEntry("com.notes.editor", "手写笔记"), AppEntry("com.browser", "Browser"))
        assertEquals(listOf(apps.first()), AppListPresentation.visible(apps, emptySet(), emptySet(), "手写", false,
            AppSortOrder.NAME, false, true))
        assertEquals(listOf(apps.first()), AppListPresentation.visible(apps, emptySet(), emptySet(), "NOTES", false,
            AppSortOrder.NAME, false, true))
    }

    @Test
    fun missingPackagesAreMarkedUninstalledOnlyAfterACompleteCatalogRead() {
        val complete = AppListPresentation.entries(AppCatalogResult(emptyList()), setOf("saved.app"))
        val denied = AppListPresentation.entries(AppCatalogResult(emptyList(), accessDenied = true), setOf("saved.app"))
        assertFalse(complete.single().installed)
        assertTrue(denied.single().installed)
        assertEquals("saved.app", denied.single().packageName)
    }

    @Test
    fun configuredPriorityCanBeDisabledWhileNameAndPackageSortingStayIndependent() {
        val apps = listOf(AppEntry("z.package", "Alpha"), AppEntry("a.package", "Zulu"))
        val configured = setOf("a.package")
        assertEquals(listOf("a.package", "z.package"), AppListPresentation.visible(apps, configured, configured, "", true,
            AppSortOrder.NAME, false, true).map(AppEntry::packageName))
        assertEquals(listOf("z.package", "a.package"), AppListPresentation.visible(apps, configured, configured, "", true,
            AppSortOrder.NAME, false, false).map(AppEntry::packageName))
        assertEquals(listOf("a.package", "z.package"), AppListPresentation.visible(apps, configured, configured, "", true,
            AppSortOrder.PACKAGE, false, false).map(AppEntry::packageName))
    }
}
