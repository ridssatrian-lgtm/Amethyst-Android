package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.content.Context;
import android.widget.Toast;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Installs single-file content (mods, shaders, resource packs) into the game directory
 * of the currently selected profile. Modpacks are not handled here: they create their own instance.
 */
public class ContentInstaller {
    private ContentInstaller() {}

    /**
     * Download the selected version of the content into the right folder of the current profile.
     * Must be called from a background thread. Errors are shown to the user, not thrown.
     */
    public static void install(Context context, ModDetail modDetail, int selectedVersion) {
        try {
            File destination = getDestination(modDetail, selectedVersion);
            String url = modDetail.versionUrls[selectedVersion];
            String sha1 = modDetail.versionHashes == null ? null : modDetail.versionHashes[selectedVersion];
            if (url == null) {
                throw new IOException("No download link is available for this file. " +
                        "The author may have disabled downloads from third-party launchers.");
            }

            byte[] buffer = new byte[8192];
            DownloaderProgressWrapper progress = new DownloaderProgressWrapper(
                    R.string.content_download_downloading, ProgressLayout.INSTALL_MODPACK);
            DownloadUtils.ensureSha1(destination, sha1, (Callable<Void>) () -> {
                DownloadUtils.downloadFileMonitored(url, destination, buffer, progress);
                return null;
            });

            String fileName = destination.getName();
            String folderName = destination.getParentFile() == null ? "" : destination.getParentFile().getName();
            Tools.runOnUiThread(() -> Toast.makeText(context,
                    context.getString(R.string.content_install_done, fileName, folderName),
                    Toast.LENGTH_LONG).show());
        } catch (IOException | RuntimeException e) {
            Tools.showErrorRemote(context, R.string.content_install_failed, e);
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    private static File getDestination(ModDetail modDetail, int selectedVersion) throws IOException {
        String folderName = Constants.getContentFolder(modDetail.contentType);
        if (folderName == null) throw new IOException("This content type is not a single file");

        File gameDir = Tools.getGameDirPath(LauncherProfiles.getCurrentProfile());
        File folder = new File(gameDir, folderName);
        FileUtils.ensureDirectory(folder);

        String fileName = null;
        if (modDetail.versionFileNames != null) fileName = modDetail.versionFileNames[selectedVersion];
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = FileUtils.getFileName(modDetail.versionUrls[selectedVersion]);
        }
        if (fileName == null) throw new IOException("Invalid file name");
        // Never let the file name escape the target folder
        fileName = new File(fileName).getName();
        if (fileName.isEmpty() || fileName.equals("..")) {
            throw new IOException("Invalid file name");
        }
        return new File(folder, fileName);
    }
}
