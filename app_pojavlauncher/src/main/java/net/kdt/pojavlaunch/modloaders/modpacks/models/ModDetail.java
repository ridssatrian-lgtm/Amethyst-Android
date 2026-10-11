package net.kdt.pojavlaunch.modloaders.modpacks.models;


import androidx.annotation.NonNull;

import java.util.Arrays;

public class ModDetail extends ModItem {
    /* A cheap way to map from the front facing name to the underlying id */
    public String[] versionNames;
    public String [] mcVersionNames;
    public String[] versionUrls;
    /* SHA 1 hashes, null if a hash is unavailable */
    public String[] versionHashes;
    public String[] versionIds;
    public Dependencies[][] dependencies;
    /* File names of the downloadable files, used for single-file content (mods, shaders, resource packs). May contain nulls. */
    public String[] versionFileNames;
    /* Mod loaders each version supports (e.g. "fabric, quilt"), shown for mods. May be null or contain nulls. */
    public String[] versionLoaders;
    /* Every Minecraft version each file works with, used to match files with a profile. Entries may be null if unknown. */
    public String[][] versionGameVersions;
    /* Every loader (lowercase: fabric, quilt, forge, neoforge) each file works with. Entries may be null or empty if unknown. */
    public String[][] versionLoaderNames;
    /* Release channel of each file, see the CHANNEL_* constants */
    public int[] versionChannels;
    /* ISO 8601 publish date of each file, used to find the newest one. Entries may be null. */
    public String[] versionDates;

    public static final int CHANNEL_RELEASE = 0;
    public static final int CHANNEL_BETA = 1;
    public static final int CHANNEL_ALPHA = 2;
    public ModDetail(ModItem item, String[] versionNames, String[] versionIds, String[] mcVersionNames, String[] versionUrls, String[] hashes, Dependencies[][] dependencies) {
        super(item.apiSource, item.contentType, item.id, item.title, item.description, item.imageUrl);
        this.versionNames = versionNames;
        this.mcVersionNames = mcVersionNames;
        this.versionIds = versionIds;
        this.versionUrls = versionUrls;
        this.versionHashes = hashes;
        this.dependencies = dependencies;

        // Add the mc version to the version model
        for (int i=0; i<versionNames.length; i++){
            if (mcVersionNames[i] != null && !versionNames[i].contains(mcVersionNames[i]))
                versionNames[i] += " - " + mcVersionNames[i];
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "ModDetail{" +
                "versionNames=" + Arrays.toString(versionNames) +
                ", mcVersionNames=" + Arrays.toString(mcVersionNames) +
                ", versionIds=" + Arrays.toString(versionIds) +
                ", versionUrls=" + Arrays.toString(versionUrls) +
                ", id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", contentType=" + contentType +
                '}';
    }
    public static class Dependencies{
        public String project_id; // the main id in item.id
        public String version_id;
        public String file_name;
        public String dependency_type;
        public Dependencies(String project_id, String version_id, String file_name, String dependency_type){
            this.project_id = project_id;
            this.version_id = version_id;
            this.file_name = file_name;
            this.dependency_type = dependency_type;
        }

        /**
         * @return whether the mod does not work without this dependency.
         *         Modrinth says "required", CurseForge uses relation type 3 (RequiredDependency).
         */
        public boolean isRequired() {
            return "required".equals(dependency_type) || "3".equals(dependency_type);
        }
    }

}
