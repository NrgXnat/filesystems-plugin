// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.model.entity.FilesystemObjectInfo;
import com.radiologics.filesystems.model.entity.RemoteFilesPathInfo;
import com.radiologics.filesystems.exceptions.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xdat.model.CatEntryI;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xnat.utils.CatalogUtils;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
public abstract class AbstractFilesystemService implements FilesystemService {
    protected final ExecutorService executorService;
    @Nullable protected final SiteConfigPreferences siteConfigPreferences;

    public AbstractFilesystemService(final SiteConfigPreferences siteConfigPreferences,
                                     final ThreadPoolExecutorFactoryBean executorFactoryBean) {
        this.siteConfigPreferences = siteConfigPreferences;
        this.executorService = executorFactoryBean.getObject();
    }

    public AbstractFilesystemService() {
        executorService = Executors.newFixedThreadPool(5);
        siteConfigPreferences = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsUrl(String url, @Nullable String project, boolean shouldExist) {
        return supportsUrl(url, project, shouldExist, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsUrl(String url, @Nullable String project, boolean shouldExist, boolean shouldBeWritable) {
        try {
            assertSupportsUrl(url, project, shouldExist, shouldBeWritable);
            return true;
        } catch (InactiveUnpermittedOrNotWritableFilesystemException | UnsupportedUrlException | InvalidFilesystemOperationException e) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateProjectArchiver(String project, @Nullable Long archiverId) throws InvalidEntityException {
        // Override this stub if the filesystem can archive files
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean servesConfig(FilesystemConfig config) {
        // Override this stub if the filesystem can archive files
        return false;
    }

    /**
     * Create RemoteFilesPathInfo object, determining appropriate archive-local path
     * @param url             to be translated into remote path
     * @param catalogPath     path to catalog
     * @param project         the project
     * @return the RemoteFilesPathInfo object
     * @throws UnsupportedUrlException for unsupported or nonexistent url
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     * @throws InvalidFilesystemOperationException if filesystem doesn't support the operation
     */
    @Nonnull
    protected abstract RemoteFilesPathInfo getRemoteFilesPathInfo(String url,
                                                                  String catalogPath,
                                                                  @Nullable String project)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException;

    /**
     * Create RemoteFilesPathInfo object with known local paths
     * @param url                   to be translated into remote path
     * @param destinationPath       path to absolute local path where file should be saved
     *                              (defaults to localArchivePath if null)
     * @param catalogRelativePath   path relative to catalog
     * @param project               the project
     * @return the RemoteFilesPathInfo object
     * @throws UnsupportedUrlException for unsupported or nonexistent url
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     * @throws InvalidFilesystemOperationException if filesystem doesn't support the operation
     */
    @Nonnull
    protected abstract RemoteFilesPathInfo getRemoteFilesPathInfo(String url,
                                                                  String destinationPath,
                                                                  @Nullable String catalogRelativePath,
                                                                  @Nullable String project)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException;

    /**
     * Populates RemoteFilesPathInfo object
     * @param destinationPath       absolute destination location path
     * @param catalogRelativePath   path relative to catalog
     * @param info                  the existing RemoteFilesPathInfo object
     */
    protected void populateLocalPathInfo(String destinationPath,
                                         @Nullable String catalogRelativePath,
                                         @Nonnull RemoteFilesPathInfo info) {

        if (StringUtils.isBlank(info.localDestinationPath))
            info.localDestinationPath = destinationPath;
        if (StringUtils.isBlank(info.catalogRelativePath))
            info.catalogRelativePath = catalogRelativePath;
        if (StringUtils.isBlank(info.name))
            info.name = FilenameUtils.getName(info.localDestinationPath);
    }

    /**
     * Upload file to remote filesystem
     * @param f             the local file
     * @param url           the url
     * @param project       the project
     * @param info          info about the request that was collected when checking if url was supported
     * @param firstPush     true if this is the first time we're pushing the file (can be used to speed up initial upload)
     * @throws RemoteApiException    exceptions from remote FS API
     * @throws UnsupportedUrlException for unsupported url
     * @throws InvalidFilesystemOperationException if filesystem doesn't support pushing
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     * @throws FileNotFoundException if local file to be pushed doesn't exist
     * @throws IOException if unable to obtain read lock for file
     */
    protected abstract void doPushFile(File f, String url, @Nullable String project, @Nullable FilesystemObjectInfo info,
                                       boolean firstPush)
            throws RemoteApiException, InvalidFilesystemOperationException, UnsupportedUrlException,
            InactiveUnpermittedOrNotWritableFilesystemException, IOException;

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushFile(File file, String url, @Nullable String project, boolean firstPush)
            throws IOException, UnsupportedUrlException, RemoteApiException,
            InvalidFilesystemOperationException, InactiveUnpermittedOrNotWritableFilesystemException {
        if (!file.exists()) {
            throw new FileNotFoundException(file.getAbsolutePath() + " doesn't exist");
        }
        FilesystemObjectInfo info = assertSupportsUrl(url, project, false, true);
        doPushFile(file, url, project, info, firstPush);
    }

    /**
     * Download file from remote filesystem. This method DOES overwrite (but should only be called from
     * {@link #doPullFile(RemoteFilesPathInfo, DownloadListener)} after ensuring that the requested file doesn't exist)
     *
     * @param f             the local destination file
     * @param info          info about the request
     * @param listener      download progress listener
     * @throws RemoteApiException    exceptions from remote FS API
     */
    protected abstract void doPullFile(File f, RemoteFilesPathInfo info, DownloadListener listener)
            throws RemoteApiException;

    /**
     * Download to info.localDestinationPath, will NOT overwrite.
     * @param info  RemoteFilesPathInfo object
     * @param listener progress listener
     * @return the file, which will exist
     * @throws RemoteApiException exceptions from remote FS API
     */
    @Nonnull
    private File doPullFile(RemoteFilesPathInfo info, DownloadListener listener) throws RemoteApiException {
        File f = new File(info.localDestinationPath);
        if (f.exists()) return f;
        doPullFile(f, info, listener);
        if (!f.exists()) {
            throw new RemoteApiException("Supposedly-downloaded file doesn't exist");
        }
        return f;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public File pullFile(String url, String destinationPath, @Nullable String project)
            throws UnsupportedUrlException, RemoteApiException, InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException {
        RemoteFilesPathInfo info = getRemoteFilesPathInfo(url, destinationPath, null, project);
        try {
            return doPullFile(info, null);
        } catch (Exception e) {
            String msg = "Unable to download " + url + " to " + destinationPath + " from remote filesystem " +
                    this.getClass().getName() + ": " + e.getMessage();
            log.error(msg, e);
            throw e;
        }
    }

    /**
     * Download file metadata from remote filesystem
     *
     * @param info          info about the request
     * @return CatalogUtils.CatalogEntryAttributes
     * @throws RemoteApiException    exceptions from remote FS API
     */
    @Nonnull
    protected abstract CatalogUtils.CatalogEntryAttributes doGetMetadata(RemoteFilesPathInfo info)
            throws RemoteApiException;

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public CatalogUtils.CatalogEntryAttributes getMetadata(String url, String catalogPath, @Nullable String project)
            throws UnsupportedUrlException, RemoteApiException, InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException {
        RemoteFilesPathInfo info = getRemoteFilesPathInfo(url, catalogPath, project);
        try {
            return doGetMetadata(info);
        } catch (Exception e) {
            String msg = "Unable to HEAD " + url + " with remote filesystem " +
                    this.getClass().getName() + ": " + e.getMessage();
            log.error(msg, e);
            throw e;
        }
    }

    /**
     * Delete file from remote filesystem
     *
     * @param url the url
     * @param project the project
     * @param info info about the request that was collected when checking if url was supported
     * @throws RemoteApiException exceptions from remote FS API
     * @throws UnsupportedUrlException for unsupported url
     * @throws InvalidFilesystemOperationException if filesystem doesn't support pushing
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     */
    protected abstract void doDeleteFile(String url, @Nullable String project, @Nullable FilesystemObjectInfo info)
            throws RemoteApiException, InvalidFilesystemOperationException, UnsupportedUrlException,
            InactiveUnpermittedOrNotWritableFilesystemException;

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteFile(String url, @Nullable String project) throws UnsupportedUrlException, RemoteApiException,
            InvalidFilesystemOperationException, InactiveUnpermittedOrNotWritableFilesystemException {
        FilesystemObjectInfo info = assertSupportsUrl(url, project, true, true);
        try {
            doDeleteFile(url, project, info);
        } catch (Exception e) {
            String msg = "Unable to delete " + url + " from remote filesystem " +
                    this.getClass().getName() + ": " + e.getMessage();
            log.error(msg, e);
            throw e;
        }
    }

    /**
     * Get input stream for url
     * @param url the url
     * @param project the project
     * @return the input stream
     * @throws RemoteApiException exceptions from remote FS API
     * @throws UnsupportedUrlException for unsupported url
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     */
    @Nonnull
    protected abstract InputStream doGetInputStream(String url, @Nullable String project)
            throws RemoteApiException, UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException;

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public InputStream getInputStream(String url, @Nullable String project)
            throws RemoteApiException, UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException {
        assertSupportsUrl(url, project, true);
        try {
            return doGetInputStream(url, project);
        } catch (Exception e) {
            String msg = "Unable to get input stream for " + url + " from remote filesystem " +
                    this.getClass().getName() + ": " + e.getMessage();
            log.error(msg, e);
            throw e;
        }
    }

    /**
     * Returns a list of all files AND DIRECTORIES within root, <strong>directories must end with "/"</strong>
     * @param root    the topmost directory
     * @param project the project
     * @return list of subdirs and files
     *
     * @throws InvalidFilesystemOperationException if the filesystem doesn't support this operation
     * @throws UnsupportedUrlException if root is an unsupported URL
     * @throws RemoteApiException exceptions from remote FS API
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     */
    @Nonnull
    protected abstract List<String> doListAllFiles(@Nullable String root, @Nullable String project)
            throws InvalidFilesystemOperationException, RemoteApiException, InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException;

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public List<String> listAllFiles(@Nullable String root, @Nullable String project)
            throws InvalidFilesystemOperationException, RemoteApiException, InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException {
        return doListAllFiles(root, project);
    }

    /**
     * Get path info objects to prep for download.
     * May be overridden for specific filesystems if additional info is needed.
     *
     * @param catPath       the archive-local path
     * @param destCatPath   the destination path
     * @param project       the project
     * @param entriesToPull the entries needing pull
     * @return the list
     */
    @Nonnull
    protected List<RemoteFilesPathInfo> preprocessCatalog(@Nonnull final String catPath,
                                                          @Nullable final String destCatPath,
                                                          @Nullable final String project,
                                                          @Nonnull final List<CatEntryI> entriesToPull) {

        List<RemoteFilesPathInfo> targets = new ArrayList<>();
        List<CatEntryI> toRemove = new ArrayList<>();

        for (CatEntryI entry : entriesToPull) {
            String uri = entry.getUri();
            try {
                assertSupportsUrl(uri, project, true);
            } catch (InactiveUnpermittedOrNotWritableFilesystemException | UnsupportedUrlException | InvalidFilesystemOperationException e) {
                // unsupported, ignore
                continue;
            }

            CatalogUtils.CatalogEntryPathInfo info = new CatalogUtils.CatalogEntryPathInfo(entry, catPath, destCatPath);
            try {
                RemoteFilesPathInfo remoteInfo = getRemoteFilesPathInfo(uri, info.entryPathDest,
                        info.catalogRelativePath, project);
                targets.add(remoteInfo);
                toRemove.add(entry);
            } catch (UnsupportedUrlException | InactiveUnpermittedOrNotWritableFilesystemException | InvalidFilesystemOperationException e) {
                // Not supported by this filesystem, skip it
            }
        }

        // Remove the entries we're downloading so no other filesystem tries to pull them (or even bothers checking them)
        entriesToPull.removeAll(toRemove);

        return targets;
    }

    /**
     * Instantiate DownloadListener for filesystem
     * @return download listener instance
     */
    @Nonnull
    protected DownloadListener getDownloadListenerInstance() {
        return new DownloadListener();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Object initiatePullResourceFiles(@Nonnull final String catPath,
                                            @Nullable final String catDestPath,
                                            @Nullable final String project,
                                            @Nonnull final List<CatEntryI> entriesToPull) {
        List<RemoteFilesPathInfo> targets = preprocessCatalog(catPath, catDestPath, project, entriesToPull);
        if (targets.isEmpty()) {
            return null;
        }
        DownloadTracker tracker = new DownloadTracker();
        for (final RemoteFilesPathInfo info : targets) {
            final DownloadListener listener = getDownloadListenerInstance();
            tracker.addDownload(executorService.submit(() -> {
                try {
                    doPullFile(info, listener);
                    return true;
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    return false;
                }
            }), listener);
        }
        tracker.setTotal();
        return tracker;
    }

    /**
     * Class for listening for progress of single file download, may be extended for different filesystems
     */
    public static class DownloadListener {
        protected LocalDateTime startTime = LocalDateTime.now();

        /**
         * Job progress
         * @return decimal percent complete / 100, should be [0,1)
         */
        protected double computeProgress() {
            // No partial progress per job and jobs running in parallel means that, to user, it appears that
            // nothing is happening. So, fake it by just computing duration (3 second "chunks")
            return Math.min(0.99, Math.max(0, ChronoUnit.SECONDS.between(startTime, LocalDateTime.now()) / 300.0));
        }
    }

    /**
     * Class for tracking progress of multi-file download, may be extended for different types of downloads
     * (e.g., if external filesystem offers a more efficient download than one-by-one)
     */
    public static class DownloadTracker {
        protected Map<Future<Boolean>, DownloadListener> downloads = new HashMap<>();

        protected double completed = 0;
        protected double failed = 0;
        protected double total = -1;

        protected synchronized void setTotal() {
            total = downloads.size();
        }

        protected synchronized void addDownload(Future<Boolean> job, DownloadListener listener) {
            downloads.put(job, listener);
        }

        protected synchronized double pollDownloads() throws RemoteFilesOperationException {
            if (total == 0) {
                if (downloads.isEmpty()) {
                    // Nothing to download
                    return 100;
                } else {
                    throw new RemoteFilesOperationException("Bad tracker object");
                }
            }

            StringJoiner messages = new StringJoiner("; ");
            double progress = 0.0;
            List<Future<Boolean>> toRemove = new ArrayList<>();
            for (Future<Boolean> job : downloads.keySet()) {
                try {
                    if (job.isDone()) {
                        if (job.get(10L, TimeUnit.MILLISECONDS)) {
                            completed++;
                            toRemove.add(job);
                        } else {
                            // failed, treat it like an exception
                            throw new Exception(job + " failed");
                        }
                    } else {
                        // Never let per-job progress be 1 (100%)
                        progress += Math.min(0.99, downloads.get(job).computeProgress());
                    }
                } catch (Exception e) {
                    messages.add(e.getMessage());
                    failed++;
                    toRemove.add(job);
                    log.error("Download failed", e);
                }
            }
            downloads.keySet().removeAll(toRemove);

            double percDone = (completed + failed + progress) / total;
            if (percDone == 1) {
                // Done with the downloads, if any failed throw exception, else return 100
                if (failed > 0) {
                    throw new RemoteFilesOperationException("Downloads failed: " + messages.toString());
                } else {
                    return 100;
                }
            } else {
                return percDone * 100;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * May be overridden for specific filesystems.
     */
    @Override
    public double pollPullResource(@Nonnull Object tracker) throws RemoteFilesOperationException {
        if (!(tracker instanceof DownloadTracker)) {
            throw new RemoteFilesOperationException("Unknown pull progress object");
        }

        DownloadTracker downloadTracker = (DownloadTracker) tracker;
        return downloadTracker.pollDownloads();
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public String makeUriFromLocal(String absoluteLocalPath, @Nullable String project)
            throws InvalidFilesystemOperationException, InactiveUnpermittedOrNotWritableFilesystemException {
        // Override this stub if the filesystem can archive files
        throw new InvalidFilesystemOperationException("Filesystem cannot archive files");
    }

    /**
     * Gets Url protocol (works for URLs like s3:// that aren't recognized by Java's URL class)
     *
     * @param path the URL
     * @return the protocol
     */
    protected String getUrlProtocol(String path) {
        if (!FileUtils.IsUrl(path)) {
            return null;
        }
        return path.replaceAll("://.*", "");
    }
}

