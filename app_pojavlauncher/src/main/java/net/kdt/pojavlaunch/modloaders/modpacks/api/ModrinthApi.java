package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.app.Activity;
import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

public class ModrinthApi implements ModpackApi{
    private final ApiHandler mApiHandler;
    public ModrinthApi(){
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult modrinthSearchResult = (ModrinthSearchResult) previousPageResult;

        // Fixes an issue where the offset being equal or greater than total_hits is ignored
        if (modrinthSearchResult != null && modrinthSearchResult.previousOffset >= modrinthSearchResult.totalResultCount) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = modrinthSearchResult.totalResultCount;
            emptyResult.previousOffset = modrinthSearchResult.previousOffset;
            return emptyResult;
        }


        // Build the facets filters
        HashMap<String, Object> params = new HashMap<>();
        StringBuilder facetString = new StringBuilder();
        facetString.append("[");
        facetString.append(String.format("[\"project_type:%s\"]", getProjectType(searchFilters.contentType)));
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            facetString.append(String.format(",[\"versions:%s\"]", searchFilters.mcVersion));
        facetString.append("]");
        params.put("facets", facetString.toString());
        params.put("query", searchFilters.name);
        params.put("limit", 50);
        params.put("index", "relevance");
        if(modrinthSearchResult != null)
            params.put("offset", modrinthSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray responseHits = response.getAsJsonArray("hits");
        if(responseHits == null) return null;

        ModItem[] items = new ModItem[responseHits.size()];
        for(int i=0; i<responseHits.size(); ++i){
            JsonObject hit = responseHits.get(i).getAsJsonObject();
            items[i] = new ModItem(
                    Constants.SOURCE_MODRINTH,
                    searchFilters.contentType,
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.get("description").getAsString(),
                    GsonJsonUtils.getStringSafe(hit, "icon_url")
            );
        }
        if(modrinthSearchResult == null) modrinthSearchResult = new ModrinthSearchResult();
        modrinthSearchResult.previousOffset += responseHits.size();
        modrinthSearchResult.results = items;
        modrinthSearchResult.totalResultCount = response.get("total_hits").getAsInt();
        return modrinthSearchResult;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        fillInMissingModItemData(item);
        JsonArray response = mApiHandler.get(String.format("project/%s/version", item.id), JsonArray.class);
        if(response == null) return null;
        System.out.println(response);
        String[] names = new String[response.size()];
        String[] ids = new String[response.size()];
        String[] mcNames = new String[response.size()];
        String[] urls = new String[response.size()];
        String[] hashes = new String[response.size()];
        String[] fileNames = new String[response.size()];
        String[] loaders = new String[response.size()];
        ModDetail.Dependencies[][] dependencies = new ModDetail.Dependencies[response.size()][];

        for (int i=0; i<response.size(); ++i) {
            JsonObject version = response.get(i).getAsJsonObject();
            names[i] = version.get("name").getAsString();
            ids[i] = version.get("id").getAsString();
            try {
                JsonArray dependenciesJsonArray = version.getAsJsonArray("dependencies");
                dependencies[i] = new ModDetail.Dependencies[dependenciesJsonArray.size()];
                for (int i1 = 0; i1 < dependenciesJsonArray.size(); ++i1) {
                    JsonObject obj = dependenciesJsonArray.get(i1).getAsJsonObject();
                    dependencies[i][i1] = new ModDetail.Dependencies(
                            GsonJsonUtils.getStringSafe(obj, "project_id"),
                            GsonJsonUtils.getStringSafe(obj, "version_id"),
                            GsonJsonUtils.getStringSafe(obj, "file_name"),
                            GsonJsonUtils.getStringSafe(obj, "dependency_type")
                    );
                }
            } catch (Exception ignored) {}

            mcNames[i] = version.get("game_versions").getAsJsonArray().get(0).getAsString();
            loaders[i] = joinJsonStrings(version.getAsJsonArray("loaders"));
            // Prefer the file marked as primary, otherwise use the first one
            JsonArray files = version.getAsJsonArray("files");
            JsonObject file = files.get(0).getAsJsonObject();
            for (int f = 0; f < files.size(); ++f) {
                JsonObject candidate = files.get(f).getAsJsonObject();
                JsonElement primary = candidate.get("primary");
                if (primary != null && !primary.isJsonNull() && primary.getAsBoolean()) {
                    file = candidate;
                    break;
                }
            }
            urls[i] = file.get("url").getAsString();
            fileNames[i] = GsonJsonUtils.getStringSafe(file, "filename");
            // Assume there may not be hashes, in case the API changes
            JsonObject hashesMap = file.getAsJsonObject("hashes");
            if(hashesMap == null || hashesMap.get("sha1") == null){
                hashes[i] = null;
                continue;
            }

            hashes[i] = hashesMap.get("sha1").getAsString();
        }

        ModDetail modDetail = new ModDetail(item, names, ids, mcNames, urls, hashes, dependencies);
        modDetail.versionFileNames = fileNames;
        modDetail.versionLoaders = loaders;
        return modDetail;
    }

    private static String getProjectType(int contentType) {
        switch (contentType) {
            case Constants.CONTENT_MOD:
                return "mod";
            case Constants.CONTENT_SHADER:
                return "shader";
            case Constants.CONTENT_RESOURCEPACK:
                return "resourcepack";
            default:
                return "modpack";
        }
    }

    private static String joinJsonStrings(JsonArray array) {
        if (array == null || array.size() == 0) return null;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.size(); ++i) {
            if (i != 0) builder.append(", ");
            builder.append(array.get(i).getAsString());
        }
        return builder.toString();
    }

    private void fillInMissingModItemData(ModItem item) {
        if (!(item.title == null || item.description == null || item.imageUrl == null)) return;
        JsonObject projectResponse = mApiHandler.get(String.format("project/%s", item.id), JsonObject.class);
        if (projectResponse == null) return;
        if (item.title == null) {
            JsonElement title = projectResponse.get("title");
            item.title = title != null ? title.getAsString() : "";
        }
        if (item.description == null) {
            JsonElement description = projectResponse.get("description");
            item.description = description != null ? description.getAsString() : "";
        }
        if (item.imageUrl == null) {
            JsonElement imageUrl = projectResponse.get("icon_url");
            item.imageUrl = imageUrl != null ? imageUrl.getAsString() : null;
        }
    }

    @Override
    public ModLoader installMod(ModDetail modDetail, int selectedVersion) throws IOException{
        // Only modpacks reach this point, single-file content is handled by ContentInstaller
        return ModpackInstaller.installModpack(modDetail, selectedVersion, this::installMrpack);
    }

    @Override
    public ModLoader importModpack(File modpackFile) throws IOException, NoSuchAlgorithmException {
        return ModpackInstaller.importModpack(modpackFile, Constants.SOURCE_MODRINTH, this::installMrpack);
    }

    private static ModLoader createInfo(ModrinthIndex modrinthIndex) {
        if(modrinthIndex == null) return null;
        Map<String, String> dependencies = modrinthIndex.dependencies;
        String mcVersion = dependencies.get("minecraft");
        if(mcVersion == null) return null;
        String modLoaderVersion;
        if((modLoaderVersion = dependencies.get("forge")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_FORGE, modLoaderVersion, mcVersion);
        }
        if((modLoaderVersion = dependencies.get("fabric-loader")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_FABRIC, modLoaderVersion, mcVersion);
        }
        if((modLoaderVersion = dependencies.get("quilt-loader")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_QUILT, modLoaderVersion, mcVersion);
        }
        if((modLoaderVersion = dependencies.get("neoforge")) != null) {
            return new ModLoader(ModLoader.MOD_LOADER_NEOFORGE, modLoaderVersion, mcVersion);
        }
        return null;
    }

    private ModLoader installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(mrpackFile)){
            ModrinthIndex modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "modrinth.index.json")),
                    ModrinthIndex.class);
            
            ModDownloader modDownloader = new ModDownloader(instanceDestination);
            for(ModrinthIndex.ModrinthIndexFile indexFile : modrinthIndex.files) {
                modDownloader.submitDownload(indexFile.fileSize, indexFile.path, indexFile.hashes.sha1, indexFile.downloads);
            }
            modDownloader.awaitFinish(new DownloaderProgressWrapper(R.string.modpack_download_downloading_mods, ProgressLayout.INSTALL_MODPACK));
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            ZipUtils.zipExtract(modpackZipFile, "overrides/", instanceDestination);
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            ZipUtils.zipExtract(modpackZipFile, "client-overrides/", instanceDestination);
            return createInfo(modrinthIndex);
        }
    }

    class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }
}
