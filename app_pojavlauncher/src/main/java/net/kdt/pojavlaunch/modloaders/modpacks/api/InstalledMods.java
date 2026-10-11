package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The jars (and zips) that are already in a content folder, and the decision of what to do
 * when a freshly downloaded file collides with one of them.
 */
public final class InstalledMods {

    /** What to do with a downloaded file */
    public enum Action {
        /** Nothing in the way, put the file in the folder */
        INSTALL,
        /** The very same file (or version) is already installed, throw the download away */
        SKIP_IDENTICAL,
        /** Another version is installed and the file is only a dependency: leave what the user has */
        KEEP_EXISTING,
        /** Another version is installed and the user asked for this file: they need to choose */
        ASK_REPLACE
    }

    public static final class Entry {
        public final File file;
        /** Null if the file has no readable mod metadata */
        @Nullable public final ModJarInfo info;

        public Entry(File file, @Nullable ModJarInfo info) {
            this.file = file;
            this.info = info;
        }
    }

    public static final class Decision {
        public final Action action;
        /** The installed files that collide with the download, empty for INSTALL */
        public final List<Entry> conflicts;

        Decision(Action action, List<Entry> conflicts) {
            this.action = action;
            this.conflicts = conflicts;
        }
    }

    private final List<Entry> mEntries = new ArrayList<>();

    /** List what is in the folder. A folder that does not exist yet is simply empty. */
    public static InstalledMods scan(File folder) {
        InstalledMods installed = new InstalledMods();
        File[] files = folder.listFiles();
        if (files == null) return installed;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar") && !name.endsWith(".zip")) continue;
            installed.add(file, ModJarInfo.read(file));
        }
        return installed;
    }

    public void add(File file, @Nullable ModJarInfo info) {
        mEntries.add(new Entry(file, info));
    }

    public void remove(Entry entry) {
        mEntries.remove(entry);
    }

    public int size() {
        return mEntries.size();
    }

    /**
     * Decide what to do with a downloaded file.
     * @param targetName the file name the download will have in the folder
     * @param incoming the downloaded file
     * @param incomingInfo what the downloaded file says about itself, null if unknown
     * @param isDependency whether the file is only there because another mod needs it
     */
    public Decision decide(String targetName, File incoming, @Nullable ModJarInfo incomingInfo, boolean isDependency) {
        List<Entry> conflicts = new ArrayList<>();
        for (Entry entry : mEntries) {
            boolean sameName = entry.file.getName().equals(targetName);
            boolean sameMod = incomingInfo != null && incomingInfo.sharesModIdWith(entry.info);
            if (sameName || sameMod) conflicts.add(entry);
        }
        if (conflicts.isEmpty()) return new Decision(Action.INSTALL, conflicts);

        String incomingHash = null;
        for (Entry entry : conflicts) {
            if (entry.file.length() == incoming.length()) {
                try {
                    if (incomingHash == null) incomingHash = sha1(incoming);
                    if (incomingHash.equals(sha1(entry.file))) return new Decision(Action.SKIP_IDENTICAL, conflicts);
                } catch (IOException ignored) {
                    // Can't compare the bytes, fall through to the version check
                }
            }
            if (incomingInfo != null && entry.info != null && incomingInfo.version != null
                    && incomingInfo.version.equals(entry.info.version)
                    && incomingInfo.sharesModIdWith(entry.info)) {
                return new Decision(Action.SKIP_IDENTICAL, conflicts);
            }
        }
        return new Decision(isDependency ? Action.KEEP_EXISTING : Action.ASK_REPLACE, conflicts);
    }

    /**
     * Take a file out of the way without losing it: "mod.jar" becomes "mod.jar.disabled",
     * which no mod loader picks up. The user can rename it back.
     * @return the new location of the file
     */
    public static File disable(File file) throws IOException {
        File parent = file.getParentFile();
        File target = new File(parent, file.getName() + ".disabled");
        for (int i = 1; target.exists(); i++) {
            target = new File(parent, file.getName() + ".disabled" + i);
        }
        if (!file.renameTo(target)) throw new IOException("Unable to rename " + file.getName());
        return target;
    }

    /** @return the lowercase hex SHA-1 of a file */
    public static String sha1(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
