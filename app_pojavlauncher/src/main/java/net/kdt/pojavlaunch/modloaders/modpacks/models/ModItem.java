package net.kdt.pojavlaunch.modloaders.modpacks.models;

import androidx.annotation.NonNull;

public class ModItem extends ModSource {

    public String id;
    public String title;
    public String description;
    public String imageUrl;

    public ModItem(int apiSource, boolean isModpack, String id, String title, String description, String imageUrl) {
        this(apiSource, isModpack ? Constants.CONTENT_MODPACK : Constants.CONTENT_MOD, id, title, description, imageUrl);
    }

    public ModItem(int apiSource, int contentType, String id, String title, String description, String imageUrl) {
        this.apiSource = apiSource;
        this.contentType = contentType;
        this.isModpack = contentType == Constants.CONTENT_MODPACK;
        this.id = id;
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
    }

    @NonNull
    @Override
    public String toString() {
        return "ModItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", contentType=" + contentType +
                '}';
    }

    public String getIconCacheTag() {
        return apiSource+"_"+id;
    }
}
