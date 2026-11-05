package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Per-view tint and native-width layout for both status-bar and control-center instances. */
public final class PenStatusViews {
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final IconVisibility visibility;
    private final Supplier<PenStatusPresenter.Appearance> appearance;
    private final Map<ImageView, Integer> colors = new WeakHashMap<>();
    private final Set<ImageView> initialized = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<ImageView, View.OnAttachStateChangeListener> listeners = new WeakHashMap<>();
    private boolean painting;

    public PenStatusViews(FocusStylusModule module, ClassLoader loader, IconVisibility visibility,
                   Supplier<PenStatusPresenter.Appearance> appearance) {
        this.module = module; this.loader = loader; this.visibility = visibility; this.appearance = appearance;
    }
    public void install() {
        Class<?> type = Reflect.find(loader, "com.android.systemui.statusbar.StatusBarIconView");
        module.afterMethods(type, "set", (receiver, args, result) -> { if (ours(receiver)) configure((ImageView) receiver); });
        module.afterMethods(type, "maybeUpdateIconScaleDimens", (receiver, args, result) -> { if (ours(receiver)) resetScale(receiver); });
        module.afterMethods(type, "onDarkChanged", (receiver, args, result) -> {
            if (!ours(receiver) || args.length < 3 || !(args[2] instanceof Integer supplied)) return;
            ImageView image = (ImageView) receiver;
            int color = supplied;
            try { color = (Integer) Reflect.call(Reflect.find(loader, "com.android.systemui.plugins.DarkIconDispatcher"), "getTint", args[0], image, supplied); }
            catch (Throwable ignored) { }
            paint(image, color);
        });
        module.afterMethods(type, "setStaticDrawableColor", (receiver, args, result) -> {
            if (ours(receiver) && args.length > 0 && args[0] instanceof Integer color) paint((ImageView) receiver, color);
        });
        module.afterMethods(type, "updateIconColor", (receiver, args, result) -> {
            if (!painting && ours(receiver)) paint((ImageView) receiver, colors.getOrDefault((ImageView) receiver, Color.WHITE));
        });
    }
    public void refresh() { for (ImageView image : new ArrayList<>(colors.keySet())) configure(image); }
    public Map<ImageView, Integer> detach() {
        Map<ImageView, Integer> saved = new WeakHashMap<>(colors);
        listeners.forEach(ImageView::removeOnAttachStateChangeListener);
        listeners.clear(); initialized.clear(); colors.clear();
        return saved;
    }
    public void restore(Map<?, ?> saved) {
        saved.forEach((view, color) -> { if (view instanceof ImageView image && color instanceof Integer tint) colors.put(image, tint); });
    }
    private void configure(ImageView image) {
        if (!module.session().isActive()) return;
        int tint = colors.computeIfAbsent(image, this::initialColor);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resetScale(image); paint(image, tint); visibility.apply(image);
        if (initialized.add(image)) {
            View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                module.safely("Attach stylus icon view", () -> configure((ImageView) view));
            }
            @Override public void onViewDetachedFromWindow(View view) { }
            };
            image.addOnAttachStateChangeListener(listener);
            listeners.put(image, listener);
        }
    }
    private int initialColor(ImageView image) {
        if (image.getParent() instanceof ViewGroup parent) {
            for (int index = 0; index < parent.getChildCount(); index++) {
                View sibling = parent.getChildAt(index);
                if (sibling != image && sibling instanceof ImageView other && other.getImageTintList() != null)
                    return other.getImageTintList().getDefaultColor();
            }
        }
        return Color.WHITE;
    }
    private void paint(ImageView image, int requested) {
        if (painting) return;
        int color = requested == Color.TRANSPARENT ? Color.WHITE : requested;
        colors.put(image, color);
        painting = true;
        try {
            PenStatusPresenter.Appearance state = appearance.get();
            Bitmap bitmap = PenBatteryIcon.render(image.getContext(), state.level(), state.charging(),
                    state.attached(), color, state.signal(), state.showSignal());
            ViewGroup.LayoutParams layout = image.getLayoutParams();
            if (layout != null && layout.width != bitmap.getWidth()) { layout.width = bitmap.getWidth(); image.setLayoutParams(layout); }
            image.setImageTintList(null); image.clearColorFilter(); image.setImageBitmap(bitmap);
        } finally { painting = false; }
    }
    private void resetScale(Object view) {
        try { Reflect.set(view, "mIconScale", 1f); }
        catch (ReflectiveOperationException error) { module.error("Reset current status icon scale", error); }
    }
    private static boolean ours(Object view) {
        if (!(view instanceof ImageView)) return false;
        try { return SystemUiController.SLOT.equals(Reflect.call(view, "getSlot")); }
        catch (Throwable ignored) { return false; }
    }
}
