package net.kdt.pojavlaunch.modloaders.modpacks.models;

public class Constants {
    private Constants(){}

    /** Types of modpack apis */
    public static final int SOURCE_MODRINTH = 0x0;
    public static final int SOURCE_CURSEFORGE = 0x1;
    public static final int SOURCE_TECHNIC = 0x2;

    /** Kinds of downloadable content */
    public static final int CONTENT_MODPACK = 0x0;
    public static final int CONTENT_MOD = 0x1;
    public static final int CONTENT_SHADER = 0x2;
    public static final int CONTENT_RESOURCEPACK = 0x3;

    /**
     * @param contentType one of the CONTENT_* constants
     * @return the folder inside a game directory where single-file content of this type goes,
     *         or null for modpacks (which create their own instance instead)
     */
    public static String getContentFolder(int contentType) {
        switch (contentType) {
            case CONTENT_MOD:
                return "mods";
            case CONTENT_SHADER:
                return "shaderpacks";
            case CONTENT_RESOURCEPACK:
                return "resourcepacks";
            default:
                return null;
        }
    }

    /** Modrinth api, file environments */
    public static final String MODRINTH_FILE_ENV_REQUIRED = "required";
    public static final String MODRINTH_FILE_ENV_OPTIONAL = "optional";
    public static final String MODRINTH_FILE_ENV_UNSUPPORTED = "unsupported";

}
