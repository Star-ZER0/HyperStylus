package cc.star0.xposed.hyperstylus.storage;


import android.os.Environment;
import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Current framework-owned records, exclusively below /data/system/HyperStylus. */
public final class SystemDataFiles {
    public interface Codec<T> {
        public T decode(InputStream input) throws IOException;
        public byte[] encode(T value) throws IOException;
    }
    private final File directory;
    public SystemDataFiles() { this(new File(Environment.getDataDirectory(), "system")); }
    public SystemDataFiles(File systemDirectory) { directory = new File(systemDirectory, "HyperStylus"); }
    public AtomicFile chargeLimit() { return new AtomicFile(new File(directory, "charge_limit")); }
    public AtomicFile brushSettings() { return new AtomicFile(new File(directory, "brush_settings")); }

    public static synchronized <T> T read(AtomicFile file, Codec<T> codec, T missing) throws IOException {
        if (!hasCommitted(file)) return missing;
        return decode(file, codec);
    }
    public static synchronized <T> void write(AtomicFile file, Codec<T> codec, T value) throws IOException {
        // Do not erase corrupt ownership records when a service continues after read failure.
        if (hasCommitted(file)) decode(file, codec);
        byte[] bytes = codec.encode(value);
        try (InputStream check = new ByteArrayInputStream(bytes)) { codec.decode(check); }
        File parent = file.getBaseFile().getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create HyperStylus system data directory: " + parent);
        FileOutputStream transaction = null;
        try {
            transaction = file.startWrite();
            transaction.write(bytes);
            transaction.flush();
            transaction.getFD().sync();
            file.finishWrite(transaction);
            // AtomicFile logs some rename failures instead of throwing; verify the base.
            try (InputStream committed = new FileInputStream(file.getBaseFile())) {
                if (!Arrays.equals(bytes, committed.readAllBytes())) throw new IOException("System data commit verification failed");
            }
        } catch (IOException | RuntimeException error) {
            if (transaction != null) file.failWrite(transaction);
            if (error instanceof IOException failure) throw failure;
            throw new IOException("Commit HyperStylus system data", error);
        }
    }
    private static boolean hasCommitted(AtomicFile file) {
        return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists();
    }
    private static <T> T decode(AtomicFile file, Codec<T> codec) throws IOException {
        // Let AtomicFile recover this current record after a failed transaction.
        try (InputStream input = file.openRead()) { return codec.decode(input); }
    }
}
