package dev.t1m3.qplayer.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Portable exchange format for QPlayer personalized configuration.
 * Serializes catalog settings (excluding machine-local paths and sensitive keys),
 * typography choices, audio gain, and track-specific lyric offsets into a versioned
 * JSON document, with optional user-passphrase AES-256-GCM encryption.
 */
public final class SettingsTransfer {
    public static final String FORMAT = "qplayer.settings";
    public static final String ENCRYPTED_FORMAT = "qplayer.settings.encrypted";
    public static final int FORMAT_VERSION = 1;
    public static final String FILE_SUFFIX = ".json";

    public enum ImportStatus {
        OK,
        INVALID_FORMAT,
        PASSWORD_REQUIRED,
        WRONG_PASSWORD
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<String> SENSITIVE_KEYWORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "cookie", "token", "credential", "auth", "login", "password", "secret", "session", "user"
            )));

    static final int ITERATIONS = 120_000;
    private static final int MIN_ITERATIONS = 10_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final class Document {
        public String format = FORMAT;
        public int formatVersion = FORMAT_VERSION;
        public String appVersion = "";
        public long exportedAtMs;
        public Map<String, Object> settings = new LinkedHashMap<>();
        public Map<String, Integer> lyricOffsets = new LinkedHashMap<>();
    }

    public static final class EncryptedDocument {
        public String format = ENCRYPTED_FORMAT;
        public int formatVersion = FORMAT_VERSION;
        public String appVersion = "";
        public long exportedAtMs;
        public String cipher = "AES-256-GCM";
        public int iterations = ITERATIONS;
        public String salt;
        public String iv;
        public int tagBits = GCM_TAG_BITS;
        public String data;
    }

    private SettingsTransfer() {}

    /** Checks whether a setting key could be related to authentication or credentials. */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String kw : SENSITIVE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** Returns true when {@code json} is a JSON object with format {@value #ENCRYPTED_FORMAT}. */
    public static boolean isEncryptedPayload(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JsonElement rootElem = JsonParser.parseString(json);
            if (!rootElem.isJsonObject()) return false;
            JsonObject root = rootElem.getAsJsonObject();
            JsonElement fmt = root.get("format");
            return fmt != null && fmt.isJsonPrimitive() && ENCRYPTED_FORMAT.equals(fmt.getAsString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Export current portable settings into a formatted plain JSON string. */
    public static String export(SettingsCore settings, PlayerController controller, String appVersion) {
        Document doc = new Document();
        doc.format = FORMAT;
        doc.formatVersion = FORMAT_VERSION;
        doc.appVersion = appVersion != null ? appVersion : "";
        doc.exportedAtMs = System.currentTimeMillis();

        if (settings != null) {
            for (SettingSpec spec : SettingsCatalog.specs()) {
                if (!spec.hasValue()) continue;
                if (spec.type == SettingSpec.PATH) continue;
                if (isSensitiveKey(spec.key)) continue;
                Object val = settings.raw(spec.key);
                if (val != null) {
                    doc.settings.put(spec.key, val);
                }
            }

            String font = settings.fontSelection();
            if (font != null && !font.isEmpty()) {
                doc.settings.put(SettingsCore.FONT_KEY, font);
            }
            String desktopFont = settings.desktopLyricFontSelection();
            if (desktopFont != null && !desktopFont.isEmpty()) {
                doc.settings.put(SettingsCore.DESKTOP_LYRIC_FONT_KEY, desktopFont);
            }

            if (settings.appVolume.peek() != null) {
                doc.settings.put("volume", settings.appVolume.peek());
            }
        }

        if (controller != null) {
            Map<String, Integer> offsets = controller.getLyricOffsets();
            if (offsets != null && !offsets.isEmpty()) {
                doc.lyricOffsets.putAll(offsets);
            }
        }

        return GSON.toJson(doc);
    }

    /** Export current portable settings into an AES-256-GCM document protected by {@code passphrase}. */
    public static String exportEncrypted(SettingsCore settings, PlayerController controller,
                                         String appVersion, char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Passphrase must not be empty");
        }
        String plainJson = export(settings, controller, appVersion);
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);

        byte[] keyBytes = null;
        try {
            keyBytes = deriveKey(passphrase, salt, ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));

            EncryptedDocument doc = new EncryptedDocument();
            doc.format = ENCRYPTED_FORMAT;
            doc.formatVersion = FORMAT_VERSION;
            doc.appVersion = appVersion != null ? appVersion : "";
            doc.exportedAtMs = System.currentTimeMillis();
            doc.iterations = ITERATIONS;
            doc.salt = Base64.getEncoder().encodeToString(salt);
            doc.iv = Base64.getEncoder().encodeToString(iv);
            doc.data = Base64.getEncoder().encodeToString(ciphertext);

            return GSON.toJson(doc);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt settings configuration", e);
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /** Import plain JSON settings into {@code settings}. */
    public static boolean importInto(String json, SettingsCore settings, PlayerController controller) {
        return importInto(json, null, settings, controller) == ImportStatus.OK;
    }

    /** Import plain or encrypted settings using {@code passphrase} when encrypted. */
    public static ImportStatus importInto(String jsonOrEncrypted, char[] passphrase,
                                          SettingsCore settings, PlayerController controller) {
        if (jsonOrEncrypted == null || jsonOrEncrypted.trim().isEmpty() || settings == null) {
            return ImportStatus.INVALID_FORMAT;
        }
        try {
            JsonElement rootElem = JsonParser.parseString(jsonOrEncrypted);
            if (!rootElem.isJsonObject()) return ImportStatus.INVALID_FORMAT;
            JsonObject root = rootElem.getAsJsonObject();

            JsonElement formatElem = root.get("format");
            if (formatElem == null || !formatElem.isJsonPrimitive()) {
                return ImportStatus.INVALID_FORMAT;
            }
            String format = formatElem.getAsString();
            String plainJson;
            if (ENCRYPTED_FORMAT.equals(format)) {
                if (passphrase == null || passphrase.length == 0) {
                    return ImportStatus.PASSWORD_REQUIRED;
                }
                if (!isValidEncryptedEnvelope(root)) {
                    return ImportStatus.INVALID_FORMAT;
                }
                plainJson = decryptDocument(root, passphrase);
                if (plainJson == null) {
                    return ImportStatus.WRONG_PASSWORD;
                }
            } else if (FORMAT.equals(format)) {
                plainJson = jsonOrEncrypted;
            } else {
                return ImportStatus.INVALID_FORMAT;
            }

            return importPlainJson(plainJson, settings, controller);
        } catch (Throwable t) {
            Logger.warn("import settings failed: {}", t.getMessage());
            return ImportStatus.INVALID_FORMAT;
        }
    }

    private static boolean isValidEncryptedEnvelope(JsonObject root) {
        JsonElement salt = root.get("salt");
        JsonElement iv = root.get("iv");
        JsonElement data = root.get("data");
        return salt != null && salt.isJsonPrimitive()
                && iv != null && iv.isJsonPrimitive()
                && data != null && data.isJsonPrimitive();
    }

    private static String decryptDocument(JsonObject root, char[] passphrase) {
        byte[] keyBytes = null;
        try {
            int iterations = ITERATIONS;
            JsonElement iterElem = root.get("iterations");
            if (iterElem != null && iterElem.isJsonPrimitive() && iterElem.getAsJsonPrimitive().isNumber()) {
                iterations = iterElem.getAsInt();
            }
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                Logger.warn("unsupported settings KDF iterations: {}", iterations);
                return null;
            }
            byte[] salt = Base64.getDecoder().decode(root.get("salt").getAsString());
            byte[] iv = Base64.getDecoder().decode(root.get("iv").getAsString());
            byte[] ciphertext = Base64.getDecoder().decode(root.get("data").getAsString());

            keyBytes = deriveKey(passphrase, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            Logger.warn("failed to decrypt settings configuration: {}", e.getMessage());
            return null;
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static ImportStatus importPlainJson(String json, SettingsCore settings, PlayerController controller) {
        JsonElement rootElem = JsonParser.parseString(json);
        if (!rootElem.isJsonObject()) return ImportStatus.INVALID_FORMAT;
        JsonObject root = rootElem.getAsJsonObject();

        JsonElement formatElem = root.get("format");
        if (formatElem == null || !formatElem.isJsonPrimitive() || !FORMAT.equals(formatElem.getAsString())) {
            return ImportStatus.INVALID_FORMAT;
        }
        JsonElement settingsElem = root.get("settings");
        if (settingsElem == null || !settingsElem.isJsonObject()) {
            return ImportStatus.INVALID_FORMAT;
        }
        JsonObject settingsObj = settingsElem.getAsJsonObject();
        JsonObject offsetsObj = null;
        JsonElement offsetsElem = root.get("lyricOffsets");
        if (offsetsElem != null && !offsetsElem.isJsonNull()) {
            if (!offsetsElem.isJsonObject()) return ImportStatus.INVALID_FORMAT;
            offsetsObj = offsetsElem.getAsJsonObject();
        }

        for (Map.Entry<String, JsonElement> entry : settingsObj.entrySet()) {
            String key = entry.getKey();
            if (isSensitiveKey(key)) continue;

            JsonElement elem = entry.getValue();
            if (elem == null || elem.isJsonNull()) continue;

            if (SettingsCore.FONT_KEY.equals(key)) {
                if (elem.isJsonPrimitive()) {
                    settings.setFontSelection(elem.getAsString());
                }
            } else if (SettingsCore.DESKTOP_LYRIC_FONT_KEY.equals(key)) {
                if (elem.isJsonPrimitive()) {
                    settings.setDesktopLyricFontSelection(elem.getAsString());
                }
            } else if ("volume".equals(key)) {
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                    settings.setAppVolume(elem.getAsInt());
                }
            } else if (settings.hasSpec(key)) {
                SettingSpec spec = settings.specOf(key);
                if (spec != null && spec.hasValue() && spec.type != SettingSpec.PATH) {
                    Object parsedVal = parseValueForSpec(spec, elem);
                    if (parsedVal != null) {
                        settings.setValue(key, parsedVal);
                    }
                }
            }
        }

        if (offsetsObj != null && controller != null) {
            Map<String, Integer> offsets = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : offsetsObj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val != null && val.isJsonPrimitive() && val.getAsJsonPrimitive().isNumber()) {
                    offsets.put(entry.getKey(), val.getAsInt());
                }
            }
            controller.importLyricOffsets(offsets);
        }

        return ImportStatus.OK;
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS);
        return factory.generateSecret(spec).getEncoded();
    }

    private static Object parseValueForSpec(SettingSpec spec, JsonElement elem) {
        if (!elem.isJsonPrimitive()) return null;
        JsonPrimitive p = elem.getAsJsonPrimitive();
        switch (spec.type) {
            case SettingSpec.SWITCH:
                return p.isBoolean() ? p.getAsBoolean() : Boolean.parseBoolean(p.getAsString());
            case SettingSpec.SLIDER:
            case SettingSpec.STEPPER:
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN:
                try {
                    return p.isNumber() ? p.getAsInt() : Integer.parseInt(p.getAsString());
                } catch (NumberFormatException e) {
                    return null;
                }
            case SettingSpec.COLOR:
            case SettingSpec.TEXT:
                return p.getAsString();
            default:
                return null;
        }
    }
}
