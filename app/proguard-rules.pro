# The framework loads this class by its literal name in META-INF/xposed/java_init.list.
# API 102 callbacks override library methods; Manifest components use AGP's generated rules.
-keep,allowoptimization class cc.star0.xposed.hyperstylus.module.FocusStylusModule {
    public <init>();
}
