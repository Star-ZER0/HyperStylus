package cc.star0.xposed.hyperstylus.storage;

import cc.star0.xposed.hyperstylus.config.StylusGestureConfig;
import cc.star0.xposed.hyperstylus.runtime.nativegesture.SystemBrushController;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SystemDataStorageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File system, unrelated;
    private SystemDataFiles files;
    @Before public void setup() throws Exception {
        system = temporary.newFolder("system"); files = new SystemDataFiles(system);
        unrelated = new File(system, "another_system_component"); write(unrelated, new byte[]{11, 22, 33});
    }
    @After public void otherSystemDataIsUntouched() throws Exception {
        assertArrayEquals(new byte[]{11, 22, 33}, Files.readAllBytes(unrelated.toPath()));
    }
    @Test public void recordsUseTheHyperStylusDirectory() {
        assertEquals(new File(system, "HyperStylus/charge_limit"), files.chargeLimit().getBaseFile());
        assertEquals(new File(system, "HyperStylus/brush_settings"), files.brushSettings().getBaseFile());
    }
    @Test public void missingRecordsReadAsEmptyState() throws Exception {
        assertEquals(0, new ChargeLimitStore(files).read());
        assertEquals(Map.of(), new SystemBrushJournal(files).read());
        assertFalse(new File(system, "HyperStylus").exists());
    }
    @Test public void chargeFlagsPersistAndSubsequentUpdatesReplaceTheCurrentRecord() throws Exception {
        ChargeLimitStore store = new ChargeLimitStore(files); store.write(true, true, true);
        assertEquals(7, new ChargeLimitStore(files).read());
        assertArrayEquals(charge(7), Files.readAllBytes(files.chargeLimit().getBaseFile().toPath()));
        store.write(false, true, false); assertEquals(2, new ChargeLimitStore(files).read());
    }
    @Test public void journalPreservesUsersAndOriginallyAbsentSettings() throws Exception {
        SystemBrushJournal journal = new SystemBrushJournal(files); journal.write(claims());
        assertEquals(claims(), new SystemBrushJournal(files).read());
        journal.write(Map.of()); assertEquals(Map.of(), new SystemBrushJournal(files).read());
    }
    @Test public void currentAtomicBackupRecoversAnInterruptedTransaction() throws Exception {
        File target = files.chargeLimit().getBaseFile();
        write(target, new byte[]{0}); write(sidecar(target, ".bak"), charge(5)); write(sidecar(target, ".new"), charge(6));
        assertEquals(5, new ChargeLimitStore(files).read());
        assertArrayEquals(charge(5), Files.readAllBytes(target.toPath()));
        assertFalse(sidecar(target, ".bak").exists()); assertFalse(sidecar(target, ".new").exists());
    }
    @Test public void corruptChargeRecordSurvivesReadAndSubsequentPolicyWrites() throws Exception {
        File target = files.chargeLimit().getBaseFile(); byte[] invalid = charge(128); write(target, invalid);
        ChargeLimitStore store = new ChargeLimitStore(files);
        assertThrows(IOException.class, store::read);
        assertThrows(IOException.class, () -> store.write(false, false, false));
        assertArrayEquals(invalid, Files.readAllBytes(target.toPath()));
    }
    @Test public void invalidJournalSchemaCannotBeOverwrittenWithEmptyOwnership() throws Exception {
        File target = files.brushSettings().getBaseFile();
        byte[] invalid = "{\"version\":2,\"claims\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8); write(target, invalid);
        SystemBrushJournal journal = new SystemBrushJournal(files);
        assertThrows(IOException.class, journal::read);
        assertThrows(IOException.class, () -> journal.write(Map.of()));
        assertArrayEquals(invalid, Files.readAllBytes(target.toPath()));
    }
    @Test public void blockedDirectoryFailsWithoutModifyingTheBlockerAndCanBeRetried() throws Exception {
        File blocked = new File(system, "HyperStylus"); write(blocked, new byte[]{77});
        ChargeLimitStore store = new ChargeLimitStore(files);
        assertThrows(IOException.class, () -> store.write(true, false, false));
        assertArrayEquals(new byte[]{77}, Files.readAllBytes(blocked.toPath()));
        assertTrue(blocked.delete()); store.write(true, false, false); assertEquals(1, store.read());
    }
    @Test public void failedAtomicWriteDoesNotCreateAnOwnershipRecordAndCanBeRetried() throws Exception {
        File target = files.brushSettings().getBaseFile(), pending = sidecar(target, ".new");
        assertTrue(pending.mkdirs()); SystemBrushJournal journal = new SystemBrushJournal(files);
        assertThrows(IOException.class, () -> journal.write(claims())); assertFalse(target.exists());
        assertEquals(Map.of(), journal.read()); assertTrue(pending.delete());
        journal.write(claims()); assertEquals(claims(), journal.read());
    }
    @Test public void invalidWrittenActionIsRejectedBeforeAnyCommit() throws Exception {
        var key = new SystemBrushController.Key(0, StylusGestureConfig.Kind.PINCH);
        SystemBrushJournal journal = new SystemBrushJournal(files);
        assertThrows(IOException.class, () -> journal.write(Map.of(key, new SystemBrushController.Claim(null, "99"))));
        assertFalse(files.brushSettings().getBaseFile().exists());
    }
    private static Map<SystemBrushController.Key, SystemBrushController.Claim> claims() {
        return Map.of(new SystemBrushController.Key(0, StylusGestureConfig.Kind.PINCH), new SystemBrushController.Claim(null, "3"),
                new SystemBrushController.Key(10, StylusGestureConfig.Kind.DOUBLE_TAP), new SystemBrushController.Claim("2", "1"));
    }
    private static byte[] charge(int flags) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x53434831); output.writeByte(flags); output.flush(); return bytes.toByteArray();
    }
    private static File sidecar(File base, String suffix) { return new File(base.getPath() + suffix); }
    private static void write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile(); if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Test fixture directory");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }
}
