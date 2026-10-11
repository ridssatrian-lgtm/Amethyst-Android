package net.kdt.pojavlaunch.modloaders.modpacks.api;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which files have to be downloaded for a mod to work: the mod itself and everything it
 * requires, with the dependencies' own dependencies, each in a version that fits the profile.
 */
public final class DependencyResolver {
    /** More than this is almost certainly a mistake in the data, not a real mod */
    public static final int MAX_FILES = 40;

    public static final int PROBLEM_LOAD_FAILED = 0;
    public static final int PROBLEM_NO_COMPATIBLE_FILE = 1;
    public static final int PROBLEM_EXTERNAL = 2;
    public static final int PROBLEM_TOO_MANY = 3;

    private DependencyResolver() {}

    /** Where details of a dependency come from. Returns null if they can't be loaded. */
    public interface DetailSource {
        @Nullable
        ModDetail load(ModItem item, @Nullable String mcVersion, @Nullable String loader);
    }

    /** One file to download */
    public static final class Entry {
        public final ModDetail detail;
        public final int index;
        public final boolean dependency;
        /** Title of the mod that needs this one, null for the mod the user picked */
        @Nullable public final String requiredBy;

        Entry(ModDetail detail, int index, boolean dependency, @Nullable String requiredBy) {
            this.detail = detail;
            this.index = index;
            this.dependency = dependency;
            this.requiredBy = requiredBy;
        }

        public Entry(ModDetail detail, int index) {
            this(detail, index, false, null);
        }
    }

    /** Something that could not be resolved. The mod is still installed, but may not work. */
    public static final class Problem {
        public final int type;
        /** A mod title, id or file name; or the limit, depending on the type */
        public final String name;
        /** Extra detail for the message, for ex. the profile version */
        @Nullable public final String detail;

        Problem(int type, String name, @Nullable String detail) {
            this.type = type;
            this.name = name;
            this.detail = detail;
        }
    }

    public static final class Plan {
        /** The picked mod first, then its dependencies in the order they were found */
        public final List<Entry> entries = new ArrayList<>();
        public final List<Problem> problems = new ArrayList<>();
    }

    /**
     * @param source loads the details of the dependencies
     * @param root the mod the user picked
     * @param rootIndex which file of the picked mod to install
     * @param mcVersion Minecraft version of the profile, null if unknown
     * @param loader mod loader of the profile (InstanceInfo.LOADER_*), null if unknown
     */
    public static Plan resolve(DetailSource source, ModDetail root, int rootIndex,
                               @Nullable String mcVersion, @Nullable String loader) {
        Plan plan = new Plan();
        Entry rootEntry = new Entry(root, rootIndex);
        plan.entries.add(rootEntry);

        // The same mod can be required by many others, and two mods can require each other
        Set<String> seen = new HashSet<>();
        seen.add(key(root.apiSource, root.id));

        ArrayDeque<Entry> queue = new ArrayDeque<>();
        queue.add(rootEntry);
        while (!queue.isEmpty()) {
            Entry current = queue.poll();
            ModDetail.Dependencies[] dependencies = getDependencies(current);
            for (ModDetail.Dependencies dependency : dependencies) {
                if (dependency == null || !dependency.isRequired()) continue;
                String projectId = dependency.project_id;
                if (projectId == null || projectId.isEmpty()) {
                    // Modrinth lets a mod depend on a bare file name: nothing for us to look up
                    String name = dependency.file_name != null ? dependency.file_name : "?";
                    plan.problems.add(new Problem(PROBLEM_EXTERNAL, name, null));
                    continue;
                }
                if (!seen.add(key(root.apiSource, projectId))) continue;
                if (plan.entries.size() >= MAX_FILES) {
                    plan.problems.add(new Problem(PROBLEM_TOO_MANY, String.valueOf(MAX_FILES), null));
                    return plan;
                }

                ModItem item = new ModItem(root.apiSource, Constants.CONTENT_MOD, projectId, null, null, null);
                ModDetail detail = source.load(item, mcVersion, loader);
                String title = detail != null && detail.title != null ? detail.title : projectId;
                if (detail == null) {
                    plan.problems.add(new Problem(PROBLEM_LOAD_FAILED, title, null));
                    continue;
                }
                int best = VersionMatcher.findBestVersion(detail, mcVersion, loader);
                if (best == -1) {
                    plan.problems.add(new Problem(PROBLEM_NO_COMPATIBLE_FILE, title, describe(mcVersion, loader)));
                    continue;
                }
                Entry entry = new Entry(detail, best, true, current.detail.title);
                plan.entries.add(entry);
                queue.add(entry);
            }
        }
        return plan;
    }

    private static ModDetail.Dependencies[] getDependencies(Entry entry) {
        ModDetail.Dependencies[][] all = entry.detail.dependencies;
        if (all == null || entry.index < 0 || entry.index >= all.length || all[entry.index] == null) {
            return new ModDetail.Dependencies[0];
        }
        return all[entry.index];
    }

    private static String key(int apiSource, String projectId) {
        return apiSource + ":" + projectId;
    }

    private static String describe(@Nullable String mcVersion, @Nullable String loader) {
        StringBuilder builder = new StringBuilder();
        if (mcVersion != null) builder.append(mcVersion);
        if (loader != null) {
            if (builder.length() != 0) builder.append(' ');
            builder.append(loader);
        }
        return builder.toString();
    }
}
