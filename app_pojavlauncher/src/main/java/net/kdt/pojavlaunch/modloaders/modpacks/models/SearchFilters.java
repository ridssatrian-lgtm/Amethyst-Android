package net.kdt.pojavlaunch.modloaders.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    /** One of the Constants.CONTENT_* values */
    public int contentType = Constants.CONTENT_MODPACK;
    public String name;
    @Nullable public String mcVersion;

}
