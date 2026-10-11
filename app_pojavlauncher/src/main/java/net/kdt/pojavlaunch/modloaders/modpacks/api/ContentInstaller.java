package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.content.Context;
import android.content.ContextWrapper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.LifecycleAwareAlertDialog;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs single-file content (mods, shaders, resource packs) into the game directory
 * of the currently selected profile. Modpacks are not handled here: they create their own instance.
 *
 * For mods, the files the mod requires are installed along with it, in versions that fit the profile,
 * and a file that is already in the folder is never silently duplicated.
 */
public class ContentInstaller {
    private static final int CHOICE_CANCEL = 0;
    private static final int CHOICE_REPLACE = 1;
    private static final int CHOICE_KEEP_BOTH = 2;

    private ContentInstaller() {}

    /** What happened, shown to the user at the end */
    private static class Report {
        final List<String> installed = new ArrayList<>();
        final List<String> dependencies = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> replaced = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        boolean cancelled;
    }

    /**
     * Download the selected version of the content into the right folder of the current profile.
     * Must be called from a background thread. Errors are shown to the user, not thrown.
     * @param context an Activity context lets the user choose when a file is already installed
     * @param api used to look up the mods that the picked mod depends on
     */
    public static void install(Context context, ModpackApi api, ModDetail modDetail, int selectedVersion) {
        Report report = new Report();
        try {
            InstanceInfo instance = InstanceInfo.ofCurrentProfile();
            File folder = getFolder(modDetail);
            FileUtils.ensureDirectory(folder);

            DependencyResolver.Plan plan = createPlan(api, modDetail, selectedVersion, instance);
            InstalledMods installed = InstalledMods.scan(folder);

            int total = plan.entries.size();
            for (int i = 0; i < total; i++) {
                DependencyResolver.Entry entry = plan.entries.get(i);
                if (!installEntry(context, folder, entry, i + 1, total, installed, report)) {
                    report.cancelled = true;
                    break;
                }
            }
            if (!report.cancelled) addProblems(context, plan, report);
            showReport(context, report, folder.getName());
        } catch (IOException | RuntimeException e) {
            Tools.showErrorRemote(context, R.string.content_install_failed, e);
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    private static DependencyResolver.Plan createPlan(ModpackApi api, ModDetail modDetail, int selectedVersion,
                                                      @Nullable InstanceInfo instance) {
        if (modDetail.contentType != Constants.CONTENT_MOD) {
            // Shaders and resource packs do not depend on other files
            DependencyResolver.Plan plan = new DependencyResolver.Plan();
            plan.entries.add(new DependencyResolver.Entry(modDetail, selectedVersion));
            return plan;
        }
        String mcVersion = instance == null ? null : instance.minecraftVersion;
        String loader = instance == null ? null : instance.loader;
        return DependencyResolver.resolve(api::getModDetails, modDetail, selectedVersion, mcVersion, loader);
    }

    /**
     * Download one file and put it in the folder, unless it is already there.
     * @return false if the user cancelled the whole installation
     */
    private static boolean installEntry(Context context, File folder, DependencyResolver.Entry entry,
                                        int number, int total, InstalledMods installed, Report report) throws IOException {
        ModDetail detail = entry.detail;
        String label = detail.title != null ? detail.title : detail.id;
        String url = detail.versionUrls[entry.index];
        String sha1 = detail.versionHashes == null ? null : detail.versionHashes[entry.index];
        if (url == null) {
            String message = "No download link is available for " + label + ". " +
                    "The author may have disabled downloads from third-party launchers.";
            if (!entry.dependency) throw new IOException(message);
            report.warnings.add(context.getString(R.string.content_warn_no_link, label));
            return true;
        }

        String fileName = getFileName(detail, entry.index);
        File target = new File(folder, fileName);
        // The exact file is already there: nothing to download at all
        if (sha1 != null && target.isFile() && sha1.equalsIgnoreCase(InstalledMods.sha1(target))) {
            report.skipped.add(label);
            return true;
        }

        File part = new File(folder, fileName + ".part");
        try {
            DownloaderProgressWrapper progress = new DownloaderProgressWrapper(
                    R.string.content_download_downloading, ProgressLayout.INSTALL_MODPACK);
            progress.extraString = (total > 1 ? "[" + number + "/" + total + "] " : "") + fileName;
            DownloadUtils.downloadFileMonitored(url, part, new byte[8192], progress);
            if (sha1 != null && !sha1.equalsIgnoreCase(InstalledMods.sha1(part))) {
                throw new IOException("The downloaded file " + fileName + " is corrupted (checksum mismatch)");
            }

            ModJarInfo info = ModJarInfo.read(part);
            InstalledMods.Decision decision = installed.decide(fileName, part, info, entry.dependency);
            switch (decision.action) {
                case SKIP_IDENTICAL:
                case KEEP_EXISTING:
                    report.skipped.add(label);
                    return true;
                case ASK_REPLACE: {
                    int choice = askWhatToDo(context, decision.conflicts.get(0), info, fileName);
                    if (choice == CHOICE_CANCEL) return false;
                    boolean sameName = false;
                    for (InstalledMods.Entry conflict : decision.conflicts) {
                        if (choice == CHOICE_KEEP_BOTH && !conflict.file.equals(target)) continue;
                        // A file with the same name cannot stay next to the new one, so it is set aside too
                        if (conflict.file.equals(target)) sameName = true;
                        InstalledMods.disable(conflict.file);
                        installed.remove(conflict);
                        report.replaced.add(conflict.file.getName());
                    }
                    if (choice == CHOICE_KEEP_BOTH && sameName) report.warnings.add(
                            context.getString(R.string.content_warn_same_name, fileName));
                    break;
                }
                default:
                    break;
            }
            moveIntoPlace(part, target);
            installed.add(target, info);
            (entry.dependency ? report.dependencies : report.installed).add(label);
            return true;
        } finally {
            //noinspection ResultOfMethodCallIgnored a leftover of a failed download is useless
            if (part.exists()) part.delete();
        }
    }

    private static void moveIntoPlace(File part, File target) throws IOException {
        if (target.exists() && !target.delete()) throw new IOException("Unable to replace " + target.getName());
        if (!part.renameTo(target)) throw new IOException("Unable to save " + target.getName());
    }

    /** Let the user choose what happens with a mod that is already installed, in another version */
    private static int askWhatToDo(Context context, InstalledMods.Entry existing, @Nullable ModJarInfo incoming, String newFileName) {
        FragmentActivity activity = findActivity(context);
        // Nobody to ask (the screen is gone): the user did ask for this file, so update it. Nothing is deleted.
        if (activity == null) return CHOICE_REPLACE;

        AtomicInteger choice = new AtomicInteger(CHOICE_CANCEL);
        String oldVersion = existing.info != null && existing.info.version != null ? existing.info.version : "?";
        String newVersion = incoming != null && incoming.version != null ? incoming.version : "?";
        String message = context.getString(R.string.content_duplicate_message,
                existing.file.getName(), oldVersion, newFileName, newVersion);
        try {
            boolean lifecycleEnded = LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity,
                    (dialog, builder) -> builder
                            .setTitle(R.string.content_duplicate_title)
                            .setMessage(message)
                            .setPositiveButton(R.string.content_duplicate_replace, (d, w) -> choice.set(CHOICE_REPLACE))
                            .setNeutralButton(R.string.content_duplicate_keep_both, (d, w) -> choice.set(CHOICE_KEEP_BOTH))
                            .setNegativeButton(android.R.string.cancel, (d, w) -> choice.set(CHOICE_CANCEL)));
            if (lifecycleEnded) return CHOICE_CANCEL;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CHOICE_CANCEL;
        }
        return choice.get();
    }

    @Nullable
    private static FragmentActivity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof FragmentActivity) {
                FragmentActivity activity = (FragmentActivity) current;
                return activity.isFinishing() || activity.isDestroyed() ? null : activity;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static void addProblems(Context context, DependencyResolver.Plan plan, Report report) {
        for (DependencyResolver.Problem problem : plan.problems) {
            switch (problem.type) {
                case DependencyResolver.PROBLEM_LOAD_FAILED:
                    report.warnings.add(context.getString(R.string.content_warn_load_failed, problem.name));
                    break;
                case DependencyResolver.PROBLEM_NO_COMPATIBLE_FILE:
                    report.warnings.add(context.getString(R.string.content_warn_no_compatible, problem.name,
                            problem.detail == null ? "" : problem.detail));
                    break;
                case DependencyResolver.PROBLEM_EXTERNAL:
                    report.warnings.add(context.getString(R.string.content_warn_external, problem.name));
                    break;
                case DependencyResolver.PROBLEM_TOO_MANY:
                    report.warnings.add(context.getString(R.string.content_warn_too_many, problem.name));
                    break;
                default:
                    break;
            }
        }
    }

    private static void showReport(Context context, Report report, String folderName) {
        StringBuilder text = new StringBuilder();
        appendLine(text, context, R.string.content_summary_installed, report.installed, folderName);
        appendLine(text, context, R.string.content_summary_dependencies, report.dependencies, folderName);
        appendLine(text, context, R.string.content_summary_replaced, report.replaced, folderName);
        appendLine(text, context, R.string.content_summary_skipped, report.skipped, folderName);
        for (String warning : report.warnings) {
            if (text.length() != 0) text.append('\n');
            text.append("⚠ ").append(warning);
        }
        if (report.cancelled) {
            if (text.length() != 0) text.append('\n');
            text.append(context.getString(R.string.content_summary_cancelled));
        }
        if (text.length() == 0) return;

        String message = text.toString();
        boolean important = !report.warnings.isEmpty() || !report.dependencies.isEmpty()
                || !report.replaced.isEmpty() || !report.skipped.isEmpty() || report.cancelled;
        Tools.runOnUiThread(() -> {
            FragmentActivity activity = findActivity(context);
            if (activity != null && important) {
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.content_summary_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } else {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void appendLine(StringBuilder text, Context context, int formatId, List<String> items, String folderName) {
        if (items.isEmpty()) return;
        if (text.length() != 0) text.append('\n');
        text.append(context.getString(formatId, String.join(", ", items), folderName));
    }

    private static File getFolder(ModDetail modDetail) throws IOException {
        String folderName = Constants.getContentFolder(modDetail.contentType);
        if (folderName == null) throw new IOException("This content type is not a single file");
        return new File(Tools.getGameDirPath(LauncherProfiles.getCurrentProfile()), folderName);
    }

    private static String getFileName(ModDetail modDetail, int index) throws IOException {
        String fileName = null;
        if (modDetail.versionFileNames != null && index < modDetail.versionFileNames.length) {
            fileName = modDetail.versionFileNames[index];
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            String url = modDetail.versionUrls[index];
            fileName = url == null ? null : FileUtils.getFileName(url);
        }
        if (fileName == null) throw new IOException("Invalid file name");
        // Never let the file name escape the target folder
        fileName = new File(fileName).getName();
        if (fileName.isEmpty() || fileName.equals("..")) throw new IOException("Invalid file name");
        return fileName;
    }
}
