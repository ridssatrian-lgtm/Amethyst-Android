package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a profile runs: the Minecraft version and the mod loader. Used to pick mod files
 * that actually fit the profile the files are being installed to.
 */
public class InstanceInfo {
    public static final String LOADER_FABRIC = "fabric";
    public static final String LOADER_QUILT = "quilt";
    public static final String LOADER_FORGE = "forge";
    public static final String LOADER_NEOFORGE = "neoforge";

    private static final Pattern RELEASE_PATTERN = Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?$");
    private static final Pattern LEADING_VERSION_PATTERN = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)-.*");

    /** The Minecraft version, null when it can't be told (for ex. "latest-release") */
    @Nullable public final String minecraftVersion;
    /** One of the LOADER_* constants, null for vanilla and for anything that can't load mods */
    @Nullable public final String loader;
    /** The name of the profile as shown to the user, may be null */
    @Nullable public final String profileName;

    public InstanceInfo(@Nullable String minecraftVersion, @Nullable String loader, @Nullable String profileName) {
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.profileName = profileName;
    }

    /**
     * @return info about the currently selected profile, or null if there is no valid selected profile
     */
    @Nullable
    public static InstanceInfo ofCurrentProfile() {
        try {
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            String key = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            String name = profile.name != null && !profile.name.isEmpty() ? profile.name : key;
            return of(profile, name);
        } catch (RuntimeException e) {
            // The selected profile does not exist (anymore)
            return null;
        }
    }

    @NonNull
    public static InstanceInfo of(@NonNull MinecraftProfile profile, @Nullable String displayName) {
        String versionId = profile.lastVersionId;
        if (versionId == null || versionId.isEmpty()
                || versionId.equals(MinecraftProfile.LATEST_RELEASE)
                || versionId.equals(MinecraftProfile.LATEST_SNAPSHOT)) {
            return new InstanceInfo(null, null, displayName);
        }

        String loader = detectLoaderFromId(versionId);
        String minecraftVersion = null;
        boolean hasInheritance = false;

        File versionJson = new File(Tools.DIR_HOME_VERSION + "/" + versionId, versionId + ".json");
        if (versionJson.isFile()) {
            try {
                JsonObject root = JsonParser.parseString(Tools.read(versionJson)).getAsJsonObject();
                JsonElement inherits = root.get("inheritsFrom");
                if (inherits != null && inherits.isJsonPrimitive()) {
                    minecraftVersion = inherits.getAsString();
                    hasInheritance = true;
                }
                if (loader == null) loader = detectLoaderFromLibraries(root.getAsJsonArray("libraries"));
            } catch (Exception ignored) {
                // Fall back to what the id tells us
            }
        }

        if (minecraftVersion == null && loader == null && !hasInheritance && versionJson.isFile()) {
            // A plain vanilla version (maybe a snapshot): the version id is the Minecraft version
            minecraftVersion = versionId;
        }
        if (minecraftVersion == null) {
            minecraftVersion = guessMinecraftVersion(versionId, loader);
        }
        return new InstanceInfo(minecraftVersion, loader, displayName);
    }

    /** Short text like "1.21.1 · Fabric" for showing to the user */
    @NonNull
    public String describe() {
        StringBuilder builder = new StringBuilder();
        if (minecraftVersion != null) builder.append(minecraftVersion);
        if (loader != null) {
            if (builder.length() != 0) builder.append(" · ");
            builder.append(loader.substring(0, 1).toUpperCase(Locale.ROOT)).append(loader.substring(1));
        }
        return builder.toString();
    }

    @Nullable
    private static String detectLoaderFromId(String versionId) {
        String id = versionId.toLowerCase(Locale.ROOT);
        if (id.contains("neoforge")) return LOADER_NEOFORGE;
        if (id.contains("quilt")) return LOADER_QUILT;
        if (id.contains("fabric")) return LOADER_FABRIC;
        if (id.contains("forge")) return LOADER_FORGE;
        return null;
    }

    @Nullable
    private static String detectLoaderFromLibraries(@Nullable JsonArray libraries) {
        if (libraries == null) return null;
        String found = null;
        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) continue;
            JsonElement nameElement = element.getAsJsonObject().get("name");
            if (nameElement == null || !nameElement.isJsonPrimitive()) continue;
            String name = nameElement.getAsString();
            // NeoForge ships some libraries under the Forge name too, so it has to win over Forge
            if (name.startsWith("net.neoforged:")) return LOADER_NEOFORGE;
            if (name.startsWith("org.quiltmc:quilt-loader")) return LOADER_QUILT;
            if (name.startsWith("net.fabricmc:fabric-loader")) found = LOADER_FABRIC;
            else if (found == null && (name.startsWith("net.minecraftforge:forge")
                    || name.startsWith("net.minecraftforge:fmlloader"))) found = LOADER_FORGE;
        }
        return found;
    }

    @Nullable
    private static String guessMinecraftVersion(String versionId, @Nullable String loader) {
        if (RELEASE_PATTERN.matcher(versionId).matches()) return versionId;
        if (loader == null) {
            // OptiFine and similar: "1.12.2-OptiFine_HD_U_G5"
            Matcher matcher = LEADING_VERSION_PATTERN.matcher(versionId);
            return matcher.matches() ? matcher.group(1) : null;
        }
        switch (loader) {
            case LOADER_FABRIC:
            case LOADER_QUILT: {
                // fabric-loader-0.16.5-1.21.1
                int lastDash = versionId.lastIndexOf('-');
                if (lastDash == -1) return null;
                String candidate = versionId.substring(lastDash + 1);
                return RELEASE_PATTERN.matcher(candidate).matches() ? candidate : null;
            }
            case LOADER_FORGE: {
                // 1.20.1-forge-47.2.0
                Matcher matcher = LEADING_VERSION_PATTERN.matcher(versionId);
                return matcher.matches() ? matcher.group(1) : null;
            }
            case LOADER_NEOFORGE: {
                // neoforge-21.1.172 stands for Minecraft 1.21.1
                Matcher matcher = Pattern.compile("neoforge-(\\d+)\\.(\\d+)").matcher(versionId);
                if (!matcher.find()) return null;
                String major = matcher.group(1);
                String minor = matcher.group(2);
                return minor.equals("0") ? "1." + major : "1." + major + "." + minor;
            }
            default:
                return null;
        }
    }
}
