package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.DisplayPolicy;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Track icon ownership weakly and apply the independent status-bar/control-center policies. */
public final class IconVisibility {
    private enum Region { STATUS_BAR, CONTROL_CENTER }
    private static final String RIGHT = "RIGHT_BLOCK_LIST";
    private static final String MINI = "MINI_RIGHT_BLOCK_LIST";
    private static final String CONTROL = "CONTROL_CENTER_BLOCK_LIST";
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final Map<ViewGroup, Region> containers = new WeakHashMap<>();
    private final Set<ImageView> icons = Collections.newSetFromMap(new WeakHashMap<>());
    private int mode = DisplayPolicy.BOTH;
    private boolean enabled = true;
    private boolean updating;

    public IconVisibility(FocusStylusModule module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
    }

    public void install() {
        Class<?> manager = Reflect.find(loader, "com.android.systemui.statusbar.phone.ui.IconManager");
        for (String method : List.of("addIcon", "onIconAdded")) {
            module.afterMethods(manager, method, (receiver, args, result) -> {
                capture(receiver);
                if (result instanceof ImageView image && owns(image)) apply(image);
            });
        }
        Class<?> iconType = Reflect.find(loader, "com.android.systemui.statusbar.StatusBarIconView");
        module.afterMethods(iconType, "setBlocked", (receiver, args, result) -> {
            if (!updating && receiver instanceof ImageView image && owns(image)) apply(image);
        });
        Class<?> container = Reflect.find(loader, "com.android.systemui.statusbar.views.MiuiStatusIconContainer");
        module.afterMethods(container, "setIgnoredSlots", (receiver, args, result) -> {
            if (receiver instanceof ViewGroup group) refreshChildren(group);
        });
    }

    public int mode() { return mode; }
    public boolean enabled() { return enabled; }

    public void setEnabled(boolean value, Object controller) {
        enabled = value;
        setMode(mode, controller);
    }

    public void setMode(int value, Object controller) {
        if (!DisplayPolicy.valid(value)) return;
        discover(controller);
        mode = value;
        modifyBlockList(RIGHT, blocked(Region.STATUS_BAR));
        modifyBlockList(MINI, blocked(Region.STATUS_BAR));
        modifyBlockList(CONTROL, blocked(Region.CONTROL_CENTER));
        for (ImageView icon : new ArrayList<>(icons)) apply(icon);
    }

    private boolean blocked(Region region) {
        return !enabled || !DisplayPolicy.allows(mode, region == Region.CONTROL_CENTER);
    }

    private void discover(Object controller) {
        if (controller == null) return;
        try {
            Object owners = Reflect.field(controller, "mIconGroups");
            if (owners instanceof Map<?, ?> map) {
                map.keySet().forEach(this::capture);
            }
        } catch (ReflectiveOperationException absent) {
            // Addition hooks also discover managers when a controller keeps no public owner list.
        }
    }

    private void capture(Object manager) {
        if (manager == null) return;
        try {
            Object target = Reflect.field(manager, "mGroup");
            if (!(target instanceof ViewGroup group)) return;
            if (!containers.containsKey(group)) containers.put(group, classify(manager));
            refreshChildren(group);
        } catch (ReflectiveOperationException absent) {
            // Map entries may be tokens rather than icon managers.
        }
    }

    private Region classify(Object manager) {
        try {
            Object location = Reflect.field(manager, "mLocation");
            if (location instanceof Enum<?> value && value.name().equals("QS")) return Region.CONTROL_CENTER;
            Object value = Reflect.field(manager, "mBlockList");
            if (value instanceof Collection<?> collection) {
                Set<Object> identity = identity(collection);
                boolean control = identity.equals(identity(blockList(CONTROL)));
                boolean status = identity.equals(identity(blockList(RIGHT))) || identity.equals(identity(blockList(MINI)));
                if (control && !status) return Region.CONTROL_CENTER;
            }
        } catch (ReflectiveOperationException absent) { }
        return Region.STATUS_BAR;
    }

    private void refreshChildren(ViewGroup group) {
        for (int index = 0; index < group.getChildCount(); index++) {
            if (group.getChildAt(index) instanceof ImageView image && owns(image)) apply(image);
        }
    }

    private Collection<?> blockList(String field) {
        Class<?> utilities = Reflect.find(loader, "com.android.systemui.statusbar.phone.MiuiIconManagerUtils");
        if (utilities == null) return List.of();
        try {
            Object value = Reflect.field(utilities, field);
            return value instanceof Collection<?> collection ? collection : List.of();
        } catch (ReflectiveOperationException absent) { return List.of(); }
    }

    private static Set<Object> identity(Collection<?> collection) {
        Set<Object> identity = new HashSet<>(collection);
        identity.remove(SystemUiController.SLOT);
        return identity;
    }

    @SuppressWarnings("unchecked")
    private void modifyBlockList(String field, boolean block) {
        Collection<?> items = blockList(field);
        if (!(items instanceof ArrayList<?>)) return;
        if (block && !items.contains(SystemUiController.SLOT)) ((Collection<Object>) items).add(SystemUiController.SLOT);
        else if (!block) items.removeIf(SystemUiController.SLOT::equals);
    }

    private Region region(ImageView icon) {
        for (ViewParent parent = icon.getParent(); parent instanceof ViewGroup group; parent = group.getParent()) {
            if (containers.get(group) == Region.CONTROL_CENTER) return Region.CONTROL_CENTER;
        }
        return Region.STATUS_BAR;
    }

    public void apply(ImageView icon) {
        icons.add(icon);
        if (updating) return;
        updating = true;
        try {
            boolean hidden = blocked(region(icon));
            Reflect.call(icon, "setBlocked", hidden);
            if (!hidden && icon.getParent() instanceof ViewGroup group) restoreIgnoredSlot(group);
        } catch (Throwable error) { module.error("Apply icon region policy", error); }
        finally { updating = false; }
    }

    private void restoreIgnoredSlot(ViewGroup group) throws Throwable {
        Object value;
        try { value = Reflect.field(group, "ignoredSlots"); }
        catch (NoSuchFieldException absent) { return; }
        if (value instanceof Collection<?> ignored && ignored.contains(SystemUiController.SLOT)) {
            List<Object> remaining = new ArrayList<>(ignored);
            remaining.removeIf(SystemUiController.SLOT::equals);
            Reflect.call(group, "setIgnoredSlots", remaining);
            group.requestLayout();
        }
    }

    private static boolean owns(ImageView icon) {
        try { return SystemUiController.SLOT.equals(Reflect.call(icon, "getSlot")); }
        catch (Throwable absent) { return false; }
    }
}
