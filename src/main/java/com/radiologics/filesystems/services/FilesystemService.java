// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.exceptions.*;
import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.model.entity.FilesystemObjectInfo;
import org.nrg.xdat.model.CatEntryI;
import org.nrg.xnat.utils.CatalogUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface FilesystemService {
    String separator = "/";

    /**
     * Update any internal status pertaining to project's archiver
     * @param project the project id string
     * @param archiverId the archiver id or null to remove old archival settings for this project
     * @throws InvalidEntityException if archiverId not found in cache
     */
    void updateProjectArchiver(String project, @Nullable Long archiverId) throws InvalidEntityException;

    /**
     * Does this FilesystemService use the provided config entity
     * @param config the config entity
     * @return T/F
     */
    boolean servesConfig(FilesystemConfig config);

    /**
     * Can filesystem service + project support the provided url
     * @param url the url
     * @param project the project
     * @param shouldExist if True, check that file exists at the url
     * @return T/F
     */
    boolean supportsUrl(String url, @Nullable String project, boolean shouldExist);

    /**
     * Can filesystem service + project support the provided url
     * @param url the url
     * @param project the project
     * @param shouldExist if True, check that file exists at the url
     * @param shouldBeWritable if True, check that configured filesystem is an archiver
     * @return T/F
     */
    boolean supportsUrl(String url, @Nullable String project, boolean shouldExist, boolean shouldBeWritable);

    /**
     * Throw exception if filesystem service + project cannot support the provided url
     * @param url the url
     * @param project the project
     * @param shouldExist if True, check that file exists at the url
     * @return an object with configuration information or null if none needed
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws InvalidFilesystemOperationException if filesystem doesn't support the operation
     */
    @Nullable
    FilesystemObjectInfo assertSupportsUrl(String url, @Nullable String project, boolean shouldExist)
            throws InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException, InvalidFilesystemOperationException;

    /**
     * Throw exception if filesystem service + project cannot support the provided url
     * @param url the url
     * @param project the project
     * @param shouldExist if True, check that file exists at the url
     * @param shouldBeWritable if True, check that configured filesystem is an archiver
     * @return an object with configuration information or null if none needed
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws InvalidFilesystemOperationException if filesystem doesn't support the operation
     */
    @Nullable
    FilesystemObjectInfo assertSupportsUrl(String url, @Nullable String project, boolean shouldExist, boolean shouldBeWritable)
            throws InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException, InvalidFilesystemOperationException;

    /**
     * Retrieves the metadata for the file indicated url
     *
     * @param url may be a URL or may be an absolute local path
     * @param catalogPath is parent path of catalog file (used to determine local path)
     * @param project the project
     * @return CatalogUtils.CatalogEntryAttributes. Note that there are NO checks on whether the returned
     * CatalogEntryAttributes conflict with existing entries in the catalog. If you are wanting to add this entry, you'll
     * need to perform your own checking.
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws RemoteApiException exceptions from remote FS API
     * @throws InvalidFilesystemOperationException if filesystem doesn't support the operation
     */
    @Nonnull
    CatalogUtils.CatalogEntryAttributes getMetadata(String url, String catalogPath, @Nullable String project)
            throws InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException, RemoteApiException, InvalidFilesystemOperationException;

    /**
     * Retrieves an input stream for the file indicated by url
     *
     * @param url the URL
     * @param project the project
     * @return File
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws RemoteApiException exceptions from remote FS API
     * @throws InvalidFilesystemOperationException if filesystem doesn't support the operation
     */
    @Nonnull
    InputStream getInputStream(String url, @Nullable String project)
            throws InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException, RemoteApiException, InvalidFilesystemOperationException;

    /**
     * Retrieves the file indicated by url, and saves it to destinationPath
     *
     * @param url the URL
     * @param destinationPath absolute local path to file to which file ought to be saved
     * @param project the project
     * @return File
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws RemoteApiException exceptions from remote FS API
     * @throws InvalidFilesystemOperationException if filesystem doesn't support the operation
     */
    @Nonnull
    File pullFile(String url, String destinationPath, @Nullable String project)
            throws InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException, RemoteApiException,
            InvalidFilesystemOperationException;

    /**
     * Archives the file to remote filesystem.
     *
     * @param file the file to archive
     * @param url the remote url
     * @param project the project
     * @param firstPush true if this is the first time we're pushing the file
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws InvalidFilesystemOperationException if the filesystem doesn't support this operation
     * @throws FileNotFoundException if local file to be pushed doesn't exist
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws RemoteApiException exceptions from remote FS API
     * @throws IOException if unable to obtain read lock for file
     */
    void pushFile(File file, String url, @Nullable String project, boolean firstPush)
            throws InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException, IOException,
            UnsupportedUrlException, RemoteApiException;

    /**
     * Delete file on remote filesystem.
     *
     * @param url the remote url
     * @param project the project
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws InvalidFilesystemOperationException if the filesystem doesn't support this operation
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws RemoteApiException exceptions from remote FS API
     */
    void deleteFile(String url, @Nullable String project) throws InactiveUnpermittedOrNotWritableFilesystemException,
            InvalidFilesystemOperationException, UnsupportedUrlException, RemoteApiException;

    /**
     * Non-blocking pull resource files to destination. Will NOT overwrite.
     *
     * @param catPath       path to catalog files
     * @param catDestPath   path to destination for catalog files
     * @param project       the project
     * @param entriesToPull list of catalog entries needing pull
     * @return pull tracking object or null if no files on this filesystem
     */
    @Nullable
    Object initiatePullResourceFiles(@Nonnull final String catPath,
                                     @Nullable final String catDestPath,
                                     @Nullable String project,
                                     @Nonnull final List<CatEntryI> entriesToPull);

    /**
     * Poll progress of non-blocking download of directory from remote filesystem
     *
     * @param progressTracker object needed for tracking pull progress or null
     * @return bytes downloaded out of total as percentage, 100 for completed
     * @throws RemoteFilesOperationException issues tracking pull
     */
    double pollPullResource(@Nonnull Object progressTracker) throws RemoteFilesOperationException;

    /**
     * Make a URI on the remote filesystem based on local absolute path
     * @param absoluteLocalPath the local path
     * @param project           the project
     * @return the remote URI
     * @throws InvalidFilesystemOperationException if the filesystem service cannot archive files
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     */
    @Nonnull
    String makeUriFromLocal(String absoluteLocalPath, @Nullable String project)
            throws InvalidFilesystemOperationException, InactiveUnpermittedOrNotWritableFilesystemException;

    /**
     * Returns a list of all files AND DIRECTORIES within root, <strong>directories must end with "/"</strong>
     * @param root the topmost directory
     * @param project the project
     * @return      list of subdirs and files
     *
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws InvalidFilesystemOperationException if the filesystem doesn't support this operation
     * @throws RemoteApiException exceptions from remote FS API
     * @throws UnsupportedUrlException if root is an unsupported URL
     */
    @Nonnull
    List<String> listAllFiles(@Nullable String root, @Nullable String project)
            throws InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException,
            RemoteApiException, UnsupportedUrlException;

}
