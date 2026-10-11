package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Who a mod jar says it is: the mod IDs and the version from its metadata file.
 * Two jars with a mod ID in common are two versions of the same mod, whatever the file names say.
 * Supports Fabric, Quilt, Forge, NeoForge and old Forge (mcmod.info).
 */
public final class ModJarInfo {
    private static final int MAX_METADATA_SIZE = 2 * 1024 * 1024;
    private static final Pattern TOML_TABLE = Pattern.compile("^\\s*\\[\\[?\\s*([^\\]]+?)\\s*\\]\\]?\\s*(#.*)?$");
    private static final Pattern TOML_MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TOML_VERSION = Pattern.compile("^\\s*version\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern MANIFEST_VERSION = Pattern.compile("(?m)^Implementation-Version:\\s*(\\S+)");

    /** All IDs this jar provides, never empty */
    public final Set<String> modIds;
    /** The version string, null when the jar does not say */
    @Nullable public final String version;

    public ModJarInfo(Set<String> modIds, @Nullable String version) {
        this.modIds = modIds;
        this.version = version == null || version.trim().isEmpty() ? null : version.trim();
    }

    /** @return whether both jars provide at least one same mod ID */
    public boolean sharesModIdWith(@Nullable ModJarInfo other) {
        if (other == null) return false;
        for (String id : modIds) {
            if (other.modIds.contains(id)) return true;
        }
        return false;
    }

    /** @return the first mod ID, for showing to the user */
    public String primaryId() {
        return modIds.iterator().next();
    }

    /**
     * Read the metadata of a jar.
     * @return null when the file is not a readable jar or has no mod metadata
     */
    @Nullable
    public static ModJarInfo read(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry != null) return nonEmpty(parseFabric(readText(zip, entry)));
            entry = zip.getEntry("quilt.mod.json");
            if (entry != null) return nonEmpty(parseQuilt(readText(zip, entry)));
            entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) entry = zip.getEntry("META-INF/mods.toml");
            if (entry != null) return nonEmpty(parseToml(readText(zip, entry), zip));
            entry = zip.getEntry("mcmod.info");
            if (entry != null) return nonEmpty(parseMcmodInfo(readText(zip, entry)));
        } catch (IOException | RuntimeException e) {
            // Not a zip, corrupted or a metadata file we can't parse: treat as "unknown"
        }
        return null;
    }

    @Nullable
    private static ModJarInfo nonEmpty(@Nullable ModJarInfo info) {
        return info == null || info.modIds.isEmpty() ? null : info;
    }

    private static ModJarInfo parseFabric(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        Set<String> ids = new LinkedHashSet<>();
        addString(ids, root, "id");
        JsonArray provides = getArray(root, "provides");
        if (provides != null) {
            for (JsonElement element : provides) {
                if (element.isJsonPrimitive()) ids.add(element.getAsString());
            }
        }
        return new ModJarInfo(ids, getString(root, "version"));
    }

    private static ModJarInfo parseQuilt(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonObject loader = root.has("quilt_loader") && root.get("quilt_loader").isJsonObject() ?
                root.getAsJsonObject("quilt_loader") : null;
        if (loader == null) return null;
        Set<String> ids = new LinkedHashSet<>();
        addString(ids, loader, "id");
        JsonArray provides = getArray(loader, "provides");
        if (provides != null) {
            for (JsonElement element : provides) {
                if (element.isJsonPrimitive()) ids.add(element.getAsString());
                else if (element.isJsonObject()) addString(ids, element.getAsJsonObject(), "id");
            }
        }
        return new ModJarInfo(ids, getString(loader, "version"));
    }

    private static ModJarInfo parseToml(String text, ZipFile zip) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        String version = null;
        boolean inModsTable = false;
        for (String line : text.split("\\r?\\n")) {
            Matcher table = TOML_TABLE.matcher(line);
            if (table.matches()) {
                // Only [[mods]] describes the mod itself. [[dependencies.x]] lists other mods, with their IDs too.
                inModsTable = table.group(1).equals("mods");
                continue;
            }
            if (!inModsTable) continue;
            Matcher idMatcher = TOML_MOD_ID.matcher(line);
            if (idMatcher.find()) {
                ids.add(idMatcher.group(1));
                continue;
            }
            Matcher versionMatcher = TOML_VERSION.matcher(line);
            if (version == null && versionMatcher.find()) version = versionMatcher.group(1);
        }
        if (version != null && version.contains("${")) {
            // Build tools fill in "${file.jarVersion}" from the jar manifest
            version = readManifestVersion(zip);
        }
        return new ModJarInfo(ids, version);
    }

    private static ModJarInfo parseMcmodInfo(String text) {
        JsonElement root = JsonParser.parseString(text);
        JsonArray list = null;
        if (root.isJsonArray()) list = root.getAsJsonArray();
        else if (root.isJsonObject() && getArray(root.getAsJsonObject(), "modList") != null) {
            list = getArray(root.getAsJsonObject(), "modList");
        }
        Set<String> ids = new LinkedHashSet<>();
        String version = null;
        if (list != null) {
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject mod = element.getAsJsonObject();
                int before = ids.size();
                addString(ids, mod, "modid");
                if (version == null && ids.size() > before) version = getString(mod, "version");
            }
        }
        return new ModJarInfo(ids, version);
    }

    @Nullable
    private static String readManifestVersion(ZipFile zip) throws IOException {
        ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
        if (manifest == null) return null;
        Matcher matcher = MANIFEST_VERSION.matcher(readText(zip, manifest));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String readText(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream stream = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_METADATA_SIZE) throw new IOException("Metadata file is too big");
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            // Some tools save a byte order mark, which a JSON parser does not accept
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    @Nullable
    private static JsonArray getArray(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    @Nullable
    private static String getString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static void addString(Set<String> target, JsonObject object, String name) {
        String value = getString(object, name);
        if (value != null && !value.isEmpty()) target.add(value);
    }
}
