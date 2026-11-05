package cc.star0.xposed.hyperstylus.runtime.charge;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import cc.star0.xposed.hyperstylus.R;
import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Worker-confined system notification; actions are capabilities for one displayed dock state. */
final class ChargeNotifications {
    static final String CHANNEL = Protocol.MODULE + ".charging";
    static final int ID = 0x535443;
    enum Kind { NONE, CHARGING, ATTACHED }
    private record View(Kind kind, int level, int target, ChargeLimitPolicy.OneShot mode,
                        boolean ready, boolean mayRestore, int tabletLevel, int requiredTabletLevel,
                        ChargeLimitPolicy.OneShot primaryAction) { }
    private final Context host, resources;
    private final NotificationManager manager;
    private final BroadcastReceiver receiver;
    private final List<PendingIntent> intents = new ArrayList<>();
    private View shown;
    private String token;
    private boolean closed, registered;

    ChargeNotifications(Context host, Context resources, Handler worker, Consumer<Runnable> guarded,
                        Consumer<ChargeLimitPolicy.OneShot> action) {
        this.host = host; this.resources = resources;
        manager = host.getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("Notification service unavailable");
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                resources.getString(R.string.charge_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent event) {
                guarded.accept(() -> {
                    if (closed) return;
                    ChargeLimitPolicy.OneShot selected = selection(event);
                    if (selected == ChargeLimitPolicy.OneShot.NONE) return;
                    // Consume before dispatch. Even a queued duplicate cannot operate a later state.
                    invalidate();
                    action.accept(selected);
                });
            }
        };
        IntentFilter filter = new IntentFilter(Protocol.CHARGE_ACTION);
        filter.addDataScheme("hyperstylus-charge");
        host.registerReceiver(receiver, filter, null, worker, Context.RECEIVER_NOT_EXPORTED);
        registered = true;
    }

    static Kind kind(EnhanceConfig config, BatteryState pen, boolean connected, long now) {
        if (!connected || !fresh(pen.lastChargeAt, now) || pen.hallKnown && !pen.attached) return Kind.NONE;
        if (pen.charging) return config.chargingNotificationEnabled ? Kind.CHARGING : Kind.NONE;
        // Only positive Hall evidence can establish attached-but-not-charging.
        return config.attachedNotificationEnabled && pen.hallKnown && pen.attached
                && fresh(pen.lastAttachmentAt, now) && pen.lastChargeAt >= pen.attachmentChangedAt
                ? Kind.ATTACHED : Kind.NONE;
    }

    private static boolean fresh(long time, long now) {
        return time > 0 && time <= now && now - time < BatteryState.MAX_AGE_MS;
    }

    static ChargeLimitPolicy.OneShot primaryAction(EnhanceConfig config, Kind kind, int level, ChargeLimitPolicy.OneShot mode) {
        if (kind == Kind.NONE) return ChargeLimitPolicy.OneShot.NONE;
        if (kind == Kind.CHARGING && config.chargeLimitEnabled && level >= config.chargeStop)
            return ChargeLimitPolicy.OneShot.NONE; // The charging notification shows STOP alone at/above the cutoff.
        if (mode == ChargeLimitPolicy.OneShot.FULL) return ChargeLimitPolicy.OneShot.CANCEL_FULL;
        if (kind == Kind.CHARGING || config.chargeLimitEnabled && level >= config.chargeStop)
            return ChargeLimitPolicy.OneShot.FULL;
        return ChargeLimitPolicy.OneShot.START;
    }

    // The system_server host posts as android, which already holds notification permission.
    @SuppressLint({"MissingPermission", "NotificationPermission"})
    void update(EnhanceConfig config, BatteryState pen, boolean connected, boolean ready,
                ChargeLimitPolicy policy, boolean mayRestore, int tabletLevel, int requiredTabletLevel, long now) {
        if (closed) return;
        Kind kind = kind(config, pen, connected, now);
        if (kind == Kind.NONE) { clear(); return; }
        int level = pen.displayLevel(now);
        View next = new View(kind, level, policy.target(config), policy.oneShot(), ready, mayRestore,
                tabletLevel, requiredTabletLevel, primaryAction(config, kind, level, policy.oneShot()));
        if (next.equals(shown)) return;
        if (shown == null || shown.kind() != kind
                || shown.primaryAction() != next.primaryAction()) invalidate();
        if (token == null) token = UUID.randomUUID().toString();
        String text = next.level() < 0 ? resources.getString(R.string.charge_notification_unknown)
                : resources.getString(R.string.charge_notification_level, next.level());
        String detail = detail(next, config);
        if (detail != null) text += " · " + detail;
        Notification.Builder builder = new Notification.Builder(host, CHANNEL)
                .setSmallIcon(Icon.createWithResource(resources, R.drawable.ic_pen_native))
                .setContentTitle(resources.getString(kind == Kind.CHARGING
                        ? R.string.charge_notification_charging : R.string.charge_notification_attached))
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setSubText("HyperStylus").setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (kind == Kind.CHARGING) {
            addAction(builder, ChargeLimitPolicy.OneShot.STOP, R.string.charge_notification_stop);
        }
        if (next.primaryAction() != ChargeLimitPolicy.OneShot.NONE) {
            int title = switch (next.primaryAction()) {
                case START -> R.string.charge_notification_start;
                case FULL -> R.string.charge_notification_full;
                case CANCEL_FULL -> R.string.charge_notification_cancel_full;
                default -> throw new IllegalStateException("No primary charging action");
            };
            addAction(builder, next.primaryAction(), title);
        }
        manager.notify(CHANNEL, ID, builder.build());
        shown = next;
    }

    private String detail(View view, EnhanceConfig config) {
        if (view.kind() == Kind.ATTACHED && view.tabletLevel() >= 0 && view.tabletLevel() < view.requiredTabletLevel())
            return resources.getString(R.string.charge_notification_tablet_low, view.tabletLevel(), view.requiredTabletLevel());
        if (!view.ready()) return resources.getString(R.string.charge_notification_waiting);
        if (view.kind() == Kind.ATTACHED && !view.mayRestore())
            return resources.getString(R.string.charge_notification_protected);
        if (view.mode() == ChargeLimitPolicy.OneShot.FULL || view.mode() == ChargeLimitPolicy.OneShot.START)
            return resources.getString(R.string.charge_notification_target, view.target());
        if (view.kind() == Kind.ATTACHED && config.chargeLimitEnabled && view.level() >= config.chargeStop)
            return resources.getString(R.string.charge_notification_at_limit);
        if (view.kind() == Kind.ATTACHED && view.mode() == ChargeLimitPolicy.OneShot.STOP)
            return resources.getString(R.string.charge_notification_paused);
        return null;
    }

    private void addAction(Notification.Builder builder, ChargeLimitPolicy.OneShot action, int label) {
        Intent event = new Intent(Protocol.CHARGE_ACTION).setPackage(host.getPackageName())
                .setData(new Uri.Builder().scheme("hyperstylus-charge").authority(token).appendPath(action.name()).build())
                .putExtra("token", token).putExtra("operation", action.name())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent pending = PendingIntent.getBroadcast(host, action.ordinal(), event, PendingIntent.FLAG_IMMUTABLE);
        if (!intents.contains(pending)) intents.add(pending);
        builder.addAction(new Notification.Action.Builder(null, resources.getString(label), pending).build());
    }

    private ChargeLimitPolicy.OneShot selection(Intent event) {
        if (!Protocol.CHARGE_ACTION.equals(event.getAction()) || token == null || shown == null
                || !token.equals(event.getStringExtra("token"))) return ChargeLimitPolicy.OneShot.NONE;
        String operation = event.getStringExtra("operation");
        if (shown.primaryAction().name().equals(operation)) return shown.primaryAction();
        if (shown.kind() == Kind.CHARGING && "STOP".equals(operation)) return ChargeLimitPolicy.OneShot.STOP;
        return ChargeLimitPolicy.OneShot.NONE;
    }

    void invalidate() {
        token = null; shown = null;
        for (PendingIntent pending : intents) pending.cancel();
        intents.clear();
    }

    void clear() {
        invalidate();
        manager.cancel(CHANNEL, ID);
    }

    void close() throws Throwable {
        closed = true;
        ModuleSession.runAll(this::clear, () -> {
            if (registered) { host.unregisterReceiver(receiver); registered = false; }
        });
    }
}
