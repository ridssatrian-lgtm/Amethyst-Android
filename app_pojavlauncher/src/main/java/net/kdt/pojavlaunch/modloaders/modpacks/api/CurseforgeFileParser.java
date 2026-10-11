package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the files of a CurseForge mod ("mods/{id}/files") into a ModDetail.
 * Every field that may be missing in the answer is treated as unknown, never as an error.
 */
final class CurseforgeFileParser {
    private static final Pattern MC_VERSION_PATTERN = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
    private static final int ALGO_SHA_1 = 1;

    private CurseforgeFileParser() {}

    static ModDetail parse(ModItem item, List<JsonObject> files) {
        int length = files.size();
        String[] versionNames = new String[length];
        String[] versionIds = new String[length];
        String[] mcVersionNames = new String[length];
        String[] versionUrls = new String[length];
        String[] hashes = new String[length];
        String[] fileNames = new String[length];
        String[] loaders = new String[length];
        String[][] allGameVersions = new String[length][];
        String[][] allLoaderNames = new String[length][];
        int[] channels = new int[length];
        String[] dates = new String[length];
        ModDetail.Dependencies[][] dependencies = new ModDetail.Dependencies[length][];
        for (int i = 0; i < length; i++) {
            JsonObject modDetail = files.get(i);
            versionIds[i] = modDetail.get("id").getAsString();
            String displayName = GsonJsonUtils.getStringSafe(modDetail, "displayName");
            versionNames[i] = displayName == null || displayName.isEmpty() ? versionIds[i] : displayName;
            fileNames[i] = GsonJsonUtils.getStringSafe(modDetail, "fileName");
            // The URL is null when the author disallowed third-party distribution. In that case
            // try the edge link that the rest of the CurseForge code falls back to as well.
            String downloadUrl = GsonJsonUtils.getStringSafe(modDetail, "downloadUrl");
            if (downloadUrl == null && fileNames[i] != null) {
                int fileId = GsonJsonUtils.getIntSafe(modDetail, "id", -1);
                if (fileId != -1) {
                    downloadUrl = String.format(Locale.ROOT, "https://edge.forgecdn.net/files/%s/%s/%s",
                            fileId / 1000, fileId % 1000, fileNames[i]);
                }
            }
            versionUrls[i] = downloadUrl;

            dependencies[i] = new ModDetail.Dependencies[0];
            JsonArray dependenciesJsonArray = GsonJsonUtils.getJsonArraySafe(modDetail, "dependencies");
            if (dependenciesJsonArray != null) {
                ArrayList<ModDetail.Dependencies> parsed = new ArrayList<>(dependenciesJsonArray.size());
                for (JsonElement element : dependenciesJsonArray) {
                    if (!element.isJsonObject()) continue;
                    JsonObject obj = element.getAsJsonObject();
                    parsed.add(new ModDetail.Dependencies(
                            GsonJsonUtils.getStringSafe(obj, "modId"),
                            null,
                            null, // These two are only present on modrinth
                            GsonJsonUtils.getStringSafe(obj, "relationType")
                    ));
                }
                dependencies[i] = parsed.toArray(new ModDetail.Dependencies[0]);
            }

            StringBuilder loaderLabel = new StringBuilder();
            ArrayList<String> fileGameVersions = new ArrayList<>();
            ArrayList<String> fileLoaders = new ArrayList<>();
            JsonArray gameVersions = GsonJsonUtils.getJsonArraySafe(modDetail, "gameVersions");
            if (gameVersions != null) {
                for (JsonElement jsonElement : gameVersions) {
                    if (!jsonElement.isJsonPrimitive()) continue;
                    String gameVersion = jsonElement.getAsString();
                    if (MC_VERSION_PATTERN.matcher(gameVersion).matches()) {
                        fileGameVersions.add(gameVersion);
                        if (mcVersionNames[i] == null) mcVersionNames[i] = gameVersion;
                        continue;
                    }
                    // Everything that is not a version number is a mod loader (Forge, Fabric...) or an environment (Client, Server, Java 17)
                    String lowerCased = gameVersion.toLowerCase(Locale.ROOT);
                    if (lowerCased.equals("client") || lowerCased.equals("server") || lowerCased.startsWith("java ")) continue;
                    fileLoaders.add(lowerCased);
                    if (loaderLabel.length() != 0) loaderLabel.append(", ");
                    loaderLabel.append(gameVersion);
                }
            }
            allGameVersions[i] = fileGameVersions.toArray(new String[0]);
            allLoaderNames[i] = fileLoaders.toArray(new String[0]);
            loaders[i] = loaderLabel.length() == 0 ? null : loaderLabel.toString();
            channels[i] = parseReleaseType(GsonJsonUtils.getIntSafe(modDetail, "releaseType", 1));
            dates[i] = GsonJsonUtils.getStringSafe(modDetail, "fileDate");
            hashes[i] = getSha1(modDetail);
        }
        ModDetail detail = new ModDetail(item, versionNames, versionIds, mcVersionNames, versionUrls, hashes, dependencies);
        detail.versionFileNames = fileNames;
        detail.versionLoaders = loaders;
        detail.versionGameVersions = allGameVersions;
        detail.versionLoaderNames = allLoaderNames;
        detail.versionChannels = channels;
        detail.versionDates = dates;
        return detail;
    }

    /** CurseForge release types: 1 = release, 2 = beta, 3 = alpha */
    private static int parseReleaseType(int releaseType) {
        if (releaseType == 2) return ModDetail.CHANNEL_BETA;
        if (releaseType == 3) return ModDetail.CHANNEL_ALPHA;
        return ModDetail.CHANNEL_RELEASE;
    }

    /** @return the SHA-1 listed for a file (or the file data of a mod), null if there is none */
    @Nullable
    static String getSha1(@NonNull JsonObject object) {
        JsonArray hashes = GsonJsonUtils.getJsonArraySafe(object, "hashes");
        if (hashes == null) return null;
        for (JsonElement jsonElement : hashes) {
            // The sha1 = 1; md5 = 2;
            JsonObject jsonObject = GsonJsonUtils.getJsonObjectSafe(jsonElement);
            if (GsonJsonUtils.getIntSafe(jsonObject, "algo", -1) == ALGO_SHA_1) {
                return GsonJsonUtils.getStringSafe(jsonObject, "value");
            }
        }
        return null;
    }
}
