package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.R;
import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Render the unchanged native vector geometry as wave, pen and battery layers. */
public final class PenBatteryIcon {
    public static final int WIDTH_DP = 25;
    public static final int CHARGE_COLOR = 0xffffcc26;
    public static final int LOW_COLOR = 0xfffa382e;
    private static final int HEIGHT_DP = 20;
    private static final int WAVE_WIDTH_DP = 5;
    private static final int[] BATTERY_FILLS = {
            R.drawable.ic_pen_battery_fill_1, R.drawable.ic_pen_battery_fill_2,
            R.drawable.ic_pen_battery_fill_3, R.drawable.ic_pen_battery_fill_4, R.drawable.ic_pen_battery_fill_5
    };
    private static final Path OUTER_WAVE = wave(7f, 4f, 2.8f, 6.2f, 2.5f, 9.2f, 2.8f, 12.3f);
    private static final Path INNER_WAVE = wave(7.4f, 7f, 5.5f, 8f, 5.1f, 9.4f, 5.3f, 10.8f);
    private static final RectF UNKNOWN_LEVEL = new RectF(18.7f, 10.7f, 20.7f, 11.3f);
    private record Frame(float density, int bars, boolean unknown, boolean charging, boolean attached,
                         int foreground, int inactive, int signal, boolean showSignal) { }
    private static final LruCache<Frame, Bitmap> FRAMES = new LruCache<>(256 * 1024) {
        @Override protected int sizeOf(Frame frame, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final Map<Resources, WeakReference<Resources>> MODULE_RESOURCES = new WeakHashMap<>();
    private static final Map<Resources, Map<Integer, Drawable.ConstantState>> ARTWORK = new WeakHashMap<>();
    private PenBatteryIcon() { }

    /** Returned frames are shared read-only between SystemUI views and native Icon models. */
    public static Bitmap render(Context context, int level, boolean charging, boolean attached, int foreground, int signalBars) {
        return render(context, level, charging, attached, foreground, signalBars, true);
    }
    public static Bitmap render(Context context, int level, boolean charging, boolean attached,
                                int foreground, int signalBars, boolean showSignal) {
        float density = context.getResources().getDisplayMetrics().density;
        Frame frame = new Frame(density, BatteryState.bars(level), level < 0, charging, attached, foreground,
                attached ? foreground : inactiveWaveColor(context, foreground), Math.max(0, Math.min(2, signalBars)), showSignal);
        Bitmap cached = FRAMES.get(frame);
        if (cached != null) return cached;
        int inset = attached || !showSignal ? WAVE_WIDTH_DP : 0;
        Bitmap bitmap = Bitmap.createBitmap(Math.round((WIDTH_DP - inset) * density),
                Math.round(HEIGHT_DP * density), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(density, density);
        canvas.translate(-inset, 0); // Trim waves without changing native pen/battery bounds.
        if (!attached && showSignal) drawWaves(canvas, frame);
        Resources resources = resources(context);
        drawPen(canvas, resources, foreground);
        drawBattery(canvas, resources, frame);
        FRAMES.put(frame, bitmap);
        return bitmap;
    }

    private static Path wave(float x, float y, float a, float b, float c, float d, float e, float f) {
        Path path = new Path();
        path.moveTo(x, y);
        path.cubicTo(a, b, c, d, e, f);
        return path;
    }

    private static void drawWaves(Canvas canvas, Frame frame) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.25f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(frame.signal >= 2 ? frame.foreground : frame.inactive);
        canvas.drawPath(OUTER_WAVE, paint);
        paint.setColor(frame.signal >= 1 ? frame.foreground : frame.inactive);
        canvas.drawPath(INNER_WAVE, paint);
    }

    private static void drawPen(Canvas canvas, Resources resources, int foreground) {
        int checkpoint = canvas.save();
        canvas.translate(3.72947f, 2.221108f);
        canvas.scale(.664352f, .664352f);
        draw(resources, R.drawable.ic_pen_native, canvas, foreground, 24, 24);
        canvas.restoreToCount(checkpoint);
    }

    private static void drawBattery(Canvas canvas, Resources resources, Frame frame) {
        boolean low = !frame.unknown && frame.bars == 1;
        draw(resources, low ? R.drawable.ic_pen_battery_frame_low : R.drawable.ic_pen_battery_frame,
                canvas, frame.foreground, WIDTH_DP, HEIGHT_DP);
        int fill = frame.charging ? CHARGE_COLOR : low ? LOW_COLOR : frame.foreground;
        if (frame.unknown) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(fill);
            canvas.drawRoundRect(UNKNOWN_LEVEL, .25f, .25f, paint);
        } else draw(resources, BATTERY_FILLS[frame.bars - 1], canvas, fill, WIDTH_DP, HEIGHT_DP);
    }

    public @SuppressLint("DiscouragedApi")
    static int inactiveWaveColor(Context context, int foreground) {
        boolean dark = darkTone(foreground);
        Resources resources = context.getResources();
        String name = (dark ? "dark" : "light") + "_mode_icon_transparent_color_single_tone";
        int id = resources.getIdentifier(name, "color", Protocol.SYSTEM_UI);
        if (id != 0) {
            try {
                int color = resources.getColor(id, context.getTheme());
                if (Color.alpha(color) > 0 && darkTone(color) == dark) return color;
            } catch (Resources.NotFoundException absent) { }
        }
        return dark ? 0x4c000000 : 0x5cffffff;
    }

    private static boolean darkTone(int color) {
        return Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 < 128000;
    }

    private static Resources resources(Context context) {
        Resources host = context.getResources();
        if (Protocol.MODULE.equals(context.getPackageName())) return host;
        synchronized (MODULE_RESOURCES) {
            WeakReference<Resources> reference = MODULE_RESOURCES.get(host);
            Resources existing = reference == null ? null : reference.get();
            if (existing != null) return existing;
            try {
                Resources loaded = context.createPackageContext(Protocol.MODULE, 0).getResources();
                MODULE_RESOURCES.put(host, new WeakReference<>(loaded));
                return loaded;
            } catch (PackageManager.NameNotFoundException absent) {
                throw new IllegalStateException("Module icon resources unavailable", absent);
            }
        }
    }

    private static void draw(Resources resources, int id, Canvas canvas, int color, int width, int height) {
        Drawable.ConstantState state;
        synchronized (ARTWORK) {
            Map<Integer, Drawable.ConstantState> loaded = ARTWORK.computeIfAbsent(resources, ignored -> new HashMap<>());
            state = loaded.get(id);
            if (state == null) {
                state = resources.getDrawable(id, null).getConstantState();
                if (state != null) loaded.put(id, state);
            }
        }
        Drawable drawable = (state == null ? resources.getDrawable(id, null) : state.newDrawable(resources)).mutate();
        drawable.setTint(color);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
    }
}
