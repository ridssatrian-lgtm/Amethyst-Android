package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.app.Activity;
import android.net.Uri;

import androidx.annotation.Nullable;

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
import java.util.Locale;
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
        return getModDetails(item, null, null);
    }

    @Override
    public ModDetail getModDetails(ModItem item, @Nullable String mcVersion, @Nullable String loader) {
        fillInMissingModItemData(item);

        // Ask Modrinth to leave out files for other versions/loaders: popular mods have hundreds of them
        HashMap<String, Object> params = new HashMap<>();
        if (mcVersion != null) params.put("game_versions", toJsonStringArray(mcVersion));
        String[] acceptedLoaders = VersionMatcher.getAcceptedLoaders(loader, mcVersion);
        if (acceptedLoaders != null) params.put("loaders", toJsonStringArray(acceptedLoaders));
        String endpoint = String.format("project/%s/version", item.id);
        JsonArray response = params.isEmpty() ?
                mApiHandler.get(endpoint, JsonArray.class) :
                mApiHandler.get(endpoint, params, JsonArray.class);
        if(response == null) return null;

        return ModrinthVersionParser.parse(item, response);
    }

    private static String toJsonStringArray(String... values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i != 0) builder.append(',');
            builder.append('"').append(values[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return builder.append(']').toString();
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
