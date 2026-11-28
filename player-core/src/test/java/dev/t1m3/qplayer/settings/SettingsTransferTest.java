package dev.t1m3.qplayer.settings;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SettingsTransferTest {

    private static final class TestSettingsStore implements SettingsStore {
        private final Map<String, Object> map = new HashMap<>();

        @Override
        public boolean getBool(String key, boolean def) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override
        public int getInt(String key, int def) {
            Object v = map.get(key);
            return v instanceof Number ? ((Number) v).intValue() : def;
        }

        @Override
        public String getString(String key, String def) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override
        public boolean has(String key) {
            return map.containsKey(key);
        }

        @Override
        public void putBool(String key, boolean value) {
            map.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            map.put(key, value);
        }

        @Override
        public void putString(String key, String value) {
            map.put(key, value);
        }
    }

    @Test
    public void exportAndImportRoundTripExcludesPaths() {
        SettingsCore source = new SettingsCore();
        TestSettingsStore store1 = new TestSettingsStore();
        source.setDefault("musicFolder", "C:\\OldMachine\\Music");
        source.setDefault("cacheFolder", "C:\\OldMachine\\Cache");
        source.load(store1, SettingsCatalog.DESKTOP);

        source.setValue("darkMode", 2);
        source.setValue("uiScale", 115);
        source.setValue("lyricFontSize", 32);
        source.setValue("lyricSpring", false);
        source.setValue("lyricScale", false);
        source.setValue("lyricLineSpacing", 230);
        source.setValue("highQuality", false);
        source.setAppVolume(65);
        source.setFontSelection("TestCustomFont");

        String json = SettingsTransfer.export(source, null, "2.3.1");
        assertNotNull(json);
        assertTrue(json.contains(SettingsTransfer.FORMAT));
        assertFalse("PATH specs must not be exported", json.contains("musicFolder"));
        assertFalse("PATH specs must not be exported", json.contains("cacheFolder"));
        assertTrue(json.contains("\"darkMode\": 2"));
        assertTrue(json.contains("\"volume\": 65"));

        SettingsCore target = new SettingsCore();
        TestSettingsStore store2 = new TestSettingsStore();
        target.setDefault("musicFolder", "D:\\NewMachine\\Music");
        target.setDefault("cacheFolder", "D:\\NewMachine\\Cache");
        target.load(store2, SettingsCatalog.DESKTOP);

        assertFalse(SettingsTransfer.isEncryptedPayload(json));
        assertEquals(SettingsTransfer.ImportStatus.OK,
                SettingsTransfer.importInto(json, null, target, null));

        assertEquals(2, target.intOf("darkMode"));
        assertEquals(115, target.intOf("uiScale"));
        assertEquals(32, target.intOf("lyricFontSize"));
        assertFalse(target.bool("lyricSpring"));
        assertFalse(target.bool("lyricScale"));
        assertEquals(230, target.intOf("lyricLineSpacing"));
        assertFalse(target.bool("highQuality"));
        assertEquals((Integer) 65, target.appVolume.peek());
        assertEquals("TestCustomFont", target.fontSelection());
        assertEquals("D:\\NewMachine\\Music", target.str("musicFolder"));
        assertEquals("D:\\NewMachine\\Cache", target.str("cacheFolder"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void exportEncryptedRequiresNonEmptyPassphrase() {
        SettingsCore source = new SettingsCore();
        source.load(new TestSettingsStore(), SettingsCatalog.ANDROID);
        SettingsTransfer.exportEncrypted(source, null, "2.3.1", new char[0]);
    }

    @Test
    public void exportEncryptedWithUserPassphraseRoundTrip() {
        SettingsCore source = new SettingsCore();
        source.load(new TestSettingsStore(), SettingsCatalog.ANDROID);
        source.setValue("darkMode", 1);
        source.setValue("uiScale", 120);
        source.setFontSelection("EncryptedFontTest");

        char[] passphrase = "MySecretCustomPassword123!".toCharArray();
        String encJson = SettingsTransfer.exportEncrypted(source, null, "2.3.1", passphrase);
        assertNotNull(encJson);
        assertTrue(SettingsTransfer.isEncryptedPayload(encJson));
        assertTrue(encJson.contains("\"iterations\": " + SettingsTransfer.ITERATIONS));
        assertFalse(encJson.contains("EncryptedFontTest"));

        SettingsCore target = new SettingsCore();
        target.load(new TestSettingsStore(), SettingsCatalog.ANDROID);

        assertEquals(SettingsTransfer.ImportStatus.PASSWORD_REQUIRED,
                SettingsTransfer.importInto(encJson, null, target, null));
        assertEquals(SettingsTransfer.ImportStatus.WRONG_PASSWORD,
                SettingsTransfer.importInto(encJson, "WrongPass".toCharArray(), target, null));
        assertEquals(SettingsTransfer.ImportStatus.OK,
                SettingsTransfer.importInto(encJson, passphrase, target, null));
        assertEquals(1, target.intOf("darkMode"));
        assertEquals(120, target.intOf("uiScale"));
        assertEquals("EncryptedFontTest", target.fontSelection());
    }

    @Test
    public void importStrictlyRejectsUnknownFormatLooseJsonAndPathKeys() {
        SettingsCore target = new SettingsCore();
        TestSettingsStore store = new TestSettingsStore();
        target.setDefault("musicFolder", "D:\\KeepMyMusic");
        target.load(store, SettingsCatalog.DESKTOP);

        assertFalse(SettingsTransfer.importInto(null, target, null));
        assertFalse(SettingsTransfer.importInto("", target, null));
        assertFalse(SettingsTransfer.importInto("{malformed json", target, null));
        assertFalse(SettingsTransfer.importInto("[\"an\", \"array\"]", target, null));
        // Loose JSON or unrelated playlist JSON (.qpl.json) must be rejected cleanly
        assertEquals(SettingsTransfer.ImportStatus.INVALID_FORMAT,
                SettingsTransfer.importInto("{\"darkMode\": 2}", null, target, null));
        assertEquals(SettingsTransfer.ImportStatus.INVALID_FORMAT,
                SettingsTransfer.importInto("{\"format\": \"qplayer.playlist\", \"settings\": {\"darkMode\": 2}}",
                        null, target, null));

        // Valid format still ignores unknown raw keys and PATH specs
        String mixed = "{\n"
                + "  \"format\": \"qplayer.settings\",\n"
                + "  \"settings\": {\n"
                + "    \"darkMode\": 1,\n"
                + "    \"musicFolder\": \"/tmp/hijack\",\n"
                + "    \"unknownArbitraryKey\": \"should_not_persist\",\n"
                + "    \"authToken\": \"SECRET\"\n"
                + "  }\n"
                + "}";
        assertEquals(SettingsTransfer.ImportStatus.OK,
                SettingsTransfer.importInto(mixed, null, target, null));
        assertEquals(1, target.intOf("darkMode"));
        assertEquals("D:\\KeepMyMusic", target.str("musicFolder"));
        assertFalse(store.has("unknownArbitraryKey"));
        assertFalse(store.has("authToken"));
    }
}
