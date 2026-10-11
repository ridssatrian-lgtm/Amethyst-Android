package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;

/**
 * Decides which file of a mod fits a profile (Minecraft version + mod loader).
 * Data a source does not provide is treated as "unknown", which never rules a file out.
 */
public class VersionMatcher {
    private VersionMatcher() {}

    /**
     * @return the index of the newest compatible file (releases first, then betas and alphas),
     *         or -1 if no file is compatible
     */
    public static int findBestVersion(ModDetail detail, @Nullable String mcVersion, @Nullable String loader) {
        int count = detail.versionNames == null ? 0 : detail.versionNames.length;
        int bestRelease = -1;
        int bestAny = -1;
        for (int i = 0; i < count; i++) {
            if (!isCompatible(detail, i, mcVersion, loader)) continue;
            if (bestAny == -1 || isNewer(detail, i, bestAny)) bestAny = i;
            if (getChannel(detail, i) == ModDetail.CHANNEL_RELEASE
                    && (bestRelease == -1 || isNewer(detail, i, bestRelease))) {
                bestRelease = i;
            }
        }
        return bestRelease != -1 ? bestRelease : bestAny;
    }

    /** Whether the file at the given index fits the Minecraft version and loader */
    public static boolean isCompatible(ModDetail detail, int index, @Nullable String mcVersion, @Nullable String loader) {
        return matchesMinecraft(getGameVersions(detail, index), mcVersion)
                && matchesLoader(getLoaders(detail, index), mcVersion, loader);
    }

    /**
     * The loaders whose files a profile with the given loader can run.
     * @return null when the loader is not known (anything is accepted)
     */
    @Nullable
    public static String[] getAcceptedLoaders(@Nullable String loader, @Nullable String mcVersion) {
        if (loader == null) return null;
        switch (loader) {
            case InstanceInfo.LOADER_QUILT:
                // Quilt runs Fabric mods
                return new String[]{InstanceInfo.LOADER_QUILT, InstanceInfo.LOADER_FABRIC};
            case InstanceInfo.LOADER_NEOFORGE:
                // NeoForge kept compatibility with Forge mods on 1.20.1
                if ("1.20.1".equals(mcVersion)) {
                    return new String[]{InstanceInfo.LOADER_NEOFORGE, InstanceInfo.LOADER_FORGE};
                }
                return new String[]{InstanceInfo.LOADER_NEOFORGE};
            default:
                return new String[]{loader};
        }
    }

    /**
     * @param versionLoaders loaders a file supports (null or empty if unknown)
     * @param loader the loader of the profile (null if it can't be told)
     */
    public static boolean matchesLoader(@Nullable String[] versionLoaders, @Nullable String mcVersion, @Nullable String loader) {
        String[] accepted = getAcceptedLoaders(loader, mcVersion);
        if (accepted == null || versionLoaders == null || versionLoaders.length == 0) return true;
        for (String supported : versionLoaders) {
            if (supported == null) continue;
            for (String candidate : accepted) {
                if (supported.equalsIgnoreCase(candidate)) return true;
            }
        }
        return false;
    }

    public static boolean matchesMinecraft(@Nullable String[] versionGameVersions, @Nullable String mcVersion) {
        if (mcVersion == null || versionGameVersions == null || versionGameVersions.length == 0) return true;
        for (String supported : versionGameVersions) {
            if (mcVersion.equals(supported)) return true;
        }
        return false;
    }

    private static boolean isNewer(ModDetail detail, int candidate, int current) {
        String candidateDate = getDate(detail, candidate);
        String currentDate = getDate(detail, current);
        if (candidateDate == null) return false;
        if (currentDate == null) return true;
        // ISO 8601 dates in the same format sort correctly as plain text
        return candidateDate.compareTo(currentDate) > 0;
    }

    private static int getChannel(ModDetail detail, int index) {
        if (detail.versionChannels == null || index >= detail.versionChannels.length) return ModDetail.CHANNEL_RELEASE;
        return detail.versionChannels[index];
    }

    @Nullable
    private static String getDate(ModDetail detail, int index) {
        if (detail.versionDates == null || index >= detail.versionDates.length) return null;
        return detail.versionDates[index];
    }

    @Nullable
    private static String[] getGameVersions(ModDetail detail, int index) {
        if (detail.versionGameVersions == null || index >= detail.versionGameVersions.length) return null;
        return detail.versionGameVersions[index];
    }

    @Nullable
    private static String[] getLoaders(ModDetail detail, int index) {
        if (detail.versionLoaderNames == null || index >= detail.versionLoaderNames.length) return null;
        return detail.versionLoaderNames[index];
    }
}
