package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Turns the answer of Modrinth's "project/{id}/version" endpoint into a ModDetail.
 * Every field that may be missing in the answer is treated as unknown, never as an error.
 */
final class ModrinthVersionParser {
    private ModrinthVersionParser() {}

    static ModDetail parse(ModItem item, JsonArray response) {
        int count = response.size();
        String[] names = new String[count];
        String[] ids = new String[count];
        String[] mcNames = new String[count];
        String[] urls = new String[count];
        String[] hashes = new String[count];
        String[] fileNames = new String[count];
        String[] loaderLabels = new String[count];
        String[][] gameVersions = new String[count][];
        String[][] loaderNames = new String[count][];
        int[] channels = new int[count];
        String[] dates = new String[count];
        ModDetail.Dependencies[][] dependencies = new ModDetail.Dependencies[count][];

        for (int i = 0; i < count; ++i) {
            JsonObject version = response.get(i).getAsJsonObject();
            ids[i] = version.get("id").getAsString();
            String name = GsonJsonUtils.getStringSafe(version, "name");
            names[i] = name == null || name.isEmpty() ? ids[i] : name;
            dates[i] = GsonJsonUtils.getStringSafe(version, "date_published");
            channels[i] = parseChannel(GsonJsonUtils.getStringSafe(version, "version_type"));

            dependencies[i] = new ModDetail.Dependencies[0];
            JsonArray dependenciesJsonArray = GsonJsonUtils.getJsonArraySafe(version, "dependencies");
            if (dependenciesJsonArray != null) {
                ArrayList<ModDetail.Dependencies> parsed = new ArrayList<>(dependenciesJsonArray.size());
                for (JsonElement element : dependenciesJsonArray) {
                    if (!element.isJsonObject()) continue;
                    JsonObject obj = element.getAsJsonObject();
                    parsed.add(new ModDetail.Dependencies(
                            GsonJsonUtils.getStringSafe(obj, "project_id"),
                            GsonJsonUtils.getStringSafe(obj, "version_id"),
                            GsonJsonUtils.getStringSafe(obj, "file_name"),
                            GsonJsonUtils.getStringSafe(obj, "dependency_type")
                    ));
                }
                dependencies[i] = parsed.toArray(new ModDetail.Dependencies[0]);
            }

            gameVersions[i] = toStringArray(GsonJsonUtils.getJsonArraySafe(version, "game_versions"), false);
            loaderNames[i] = toStringArray(GsonJsonUtils.getJsonArraySafe(version, "loaders"), true);
            mcNames[i] = gameVersions[i].length > 0 ? gameVersions[i][0] : null;
            loaderLabels[i] = loaderNames[i].length == 0 ? null : String.join(", ", loaderNames[i]);

            // Prefer the file marked as primary, otherwise use the first one
            JsonArray files = GsonJsonUtils.getJsonArraySafe(version, "files");
            JsonObject file = null;
            if (files != null) {
                for (int f = 0; f < files.size(); ++f) {
                    JsonObject candidate = GsonJsonUtils.getJsonObjectSafe(files.get(f));
                    if (candidate == null) continue;
                    if (file == null) file = candidate;
                    JsonElement primary = candidate.get("primary");
                    if (primary != null && primary.isJsonPrimitive() && primary.getAsBoolean()) {
                        file = candidate;
                        break;
                    }
                }
            }
            if (file == null) continue; // a version without files, nothing to download

            urls[i] = GsonJsonUtils.getStringSafe(file, "url");
            fileNames[i] = GsonJsonUtils.getStringSafe(file, "filename");
            // Assume there may not be hashes, in case the API changes
            JsonObject hashesMap = GsonJsonUtils.getJsonObjectSafe(file, "hashes");
            hashes[i] = hashesMap == null ? null : GsonJsonUtils.getStringSafe(hashesMap, "sha1");
        }

        ModDetail modDetail = new ModDetail(item, names, ids, mcNames, urls, hashes, dependencies);
        modDetail.versionFileNames = fileNames;
        modDetail.versionLoaders = loaderLabels;
        modDetail.versionGameVersions = gameVersions;
        modDetail.versionLoaderNames = loaderNames;
        modDetail.versionChannels = channels;
        modDetail.versionDates = dates;
        return modDetail;
    }

    private static int parseChannel(@Nullable String versionType) {
        if ("beta".equals(versionType)) return ModDetail.CHANNEL_BETA;
        if ("alpha".equals(versionType)) return ModDetail.CHANNEL_ALPHA;
        return ModDetail.CHANNEL_RELEASE;
    }

    private static String[] toStringArray(@Nullable JsonArray array, boolean lowerCase) {
        if (array == null) return new String[0];
        ArrayList<String> list = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) continue;
            String value = element.getAsString();
            list.add(lowerCase ? value.toLowerCase(Locale.ROOT) : value);
        }
        return list.toArray(new String[0]);
    }
}
