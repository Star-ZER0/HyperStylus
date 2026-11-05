package cc.star0.xposed.hyperstylus.storage;


import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Current SCH1 ownership flags in /data/system/HyperStylus/charge_limit. */
public final class ChargeLimitStore {
    private static final int MAGIC = 0x53434831;
    private static final SystemDataFiles.Codec<Integer> CODEC = new SystemDataFiles.Codec<>() {
        @Override public Integer decode(InputStream stream) throws IOException {
            DataInputStream input = new DataInputStream(stream);
            if (input.readInt() != MAGIC) throw new IOException("Unknown charge ownership format");
            int value = input.readUnsignedByte();
            if ((value & ~7) != 0) throw new IOException("Invalid charge ownership flags");
            return value;
        }
        @Override public byte[] encode(Integer value) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(5);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC); output.writeByte(value); output.flush();
            return bytes.toByteArray();
        }
    };
    private final android.util.AtomicFile file;
    private int saved = -1;
    public ChargeLimitStore() { this(new SystemDataFiles()); }
    public ChargeLimitStore(SystemDataFiles files) { file = files.chargeLimit(); }
    public int read() throws IOException {
        int value = SystemDataFiles.read(file, CODEC, 0);
        // An absent record must still allow the first policy write to establish ownership.
        if (file.getBaseFile().exists()) saved = value;
        return value;
    }
    public void write(boolean blocked, boolean owned, boolean nativeStopped) throws IOException {
        int value = (blocked ? 1 : 0) | (owned ? 2 : 0) | (nativeStopped ? 4 : 0);
        if (value == saved) return;
        SystemDataFiles.write(file, CODEC, value);
        saved = value;
    }
}
