package cc.star0.xposed.hyperstylus.runtime.nativegesture;

import cc.star0.xposed.hyperstylus.config.StylusGestureConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Transactional ownership of native gesture settings; the journal always precedes mutation. */
public final class SystemBrushController {
    public record Key(int user, StylusGestureConfig.Kind kind) {}
    public record Claim(String original, String written) {}
    public interface Database {
        public String get(Key key) throws Throwable;
        public boolean put(Key key, String value) throws Throwable;
    }
    public interface Journal {
        public Map<Key, Claim> read() throws Exception;
        public void write(Map<Key, Claim> claims) throws Exception;
    }
    private final Database database;
    private final Journal journal;
    private Map<Key, Claim> owned;

    public SystemBrushController(Database database, Journal journal) throws Exception {
        this.database = database; this.journal = journal;
        owned = Map.copyOf(journal.read());
    }
    /** Queried on the owning worker after release, never inferred from a requested configuration. */
    public boolean hasClaims() { return !owned.isEmpty(); }
    public void apply(StylusGestureConfig choice, int user, String foreground) throws Throwable {
        if (user < 0) return;
        for (Key previous : new ArrayList<>(owned.keySet())) {
            if (previous.kind == choice.kind && (previous.user != user || !choice.enabled)) release(previous);
        }
        if (!choice.enabled) return;
        Key key = new Key(user, choice.kind);
        String desired = Integer.toString(choice.action(foreground));
        String current = database.get(key);
        Claim existing = owned.get(key);
        Claim next = new Claim(existing == null ? current : existing.original, desired);
        if (!next.equals(existing)) remember(key, next);
        writeIfChanged(key, current, desired);
    }
    private void release(Key key) throws Throwable {
        Claim claim = owned.get(key);
        if (claim == null) return;
        String current = database.get(key);
        if (Objects.equals(current, claim.written)) writeIfChanged(key, current, claim.original);
        remember(key, null);
    }
    private void writeIfChanged(Key key, String current, String desired) throws Throwable {
        if (!Objects.equals(current, desired) && !database.put(key, desired))
            throw new IllegalStateException("Native stylus setting write rejected: " + key.kind.key);
    }
    private void remember(Key key, Claim claim) throws Exception {
        Map<Key, Claim> next = new LinkedHashMap<>(owned);
        if (claim == null) next.remove(key); else next.put(key, claim);
        Map<Key, Claim> immutable = Map.copyOf(next);
        journal.write(immutable);
        owned = immutable;
    }
}
