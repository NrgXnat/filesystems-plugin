// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.model.entity.RemoteFilesItemState;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.archive.RemoteFilesService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.CatalogUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public interface RemoteFilesPluginService extends RemoteFilesService {

    /**
     * Returns status of item's resources
     *
     * @param item item to check
     * @return info about item state
     */
    RemoteFilesItemState getItemState(ArchivableItem item);

    /**
     * Initiate <em>non-blocking</em> download of all item resources
     *
     * @param item item
     * @param resources all item resources
     * @param archiveRelativeDir the XNAT archive-relative directory, null to use expected current directory for item
     * @param destinationDir the destination path equivalent of archiveRelativeDir, null to use archiveRelativeDir
     * @return map of resource id to future of its pull job or null if pull not attempted due to another task or
     * missing xnat_node_info on server
     * @throws ServerException if cannot determine item archive path and none provided
     */
    Map<Integer, Future<Boolean>> initiatePullItem(ArchivableItem item,
                                                   List<XnatAbstractresourceI> resources,
                                                   @Nullable String archiveRelativeDir,
                                                   @Nullable String destinationDir)
            throws ServerException;

    /**
     * Monitor item pull progress
     *
     * @param resource resource we are pulling
     * @return bytes downloaded / total bytes as percentage
     * @throws ServerException upon failure
     */
    double monitorPullProgress(XnatResourcecatalog resource) throws ServerException;

    /**
     * Add urls to catalog, <strong>permissions ought to have already been checked!</strong>.
     * @param user              the user
     * @param item              the security item
     * @param catalogData       info about the catalog
     * @param parentEventId     nullable parent event id to avoid creating a new workflow
     * @param urlMap            map of url to CatalogEntryAttributes
     * @param overwrite         true to overwrite existing catalog entry, false to throw exception for conflicts
     * @param catalogMap        from CatalogUtils.buildCatalogMap(catalogData, true)
     * @throws ServerException if unable to perform the add
     * @throws ClientException if attempted addition conflicts with an existing cat entry
     */
    void addUrlsToCatalog(final UserI user,
                          final ArchivableItem item,
                          @Nonnull final CatalogUtils.CatalogData catalogData,
                          @Nullable final Integer parentEventId,
                          final Map<String, CatalogUtils.CatalogEntryAttributes> urlMap,
                          boolean overwrite, @Nullable Map<String, CatalogUtils.CatalogMapEntry> catalogMap)
            throws ServerException, ClientException;

    /**
     * Asynchronously push item resources to external filesystem
     * @param item              the item
     * @param resources         the item resources
     * @param user              the user performing the push
     * @param filesystemService the filesystem to which we'll archive any "stale" files (files with
     *                          last access time + cleanupInterval earlier than now) or null to determine
     *                          from item's project
     * @param cleanupInterval   the number of days after last access that a file can remain in the archive
     *                          (not be pushed)
     */
    void pushItem(final ArchivableItem item,
                  final List<XnatAbstractresourceI> resources,
                  final UserI user,
                  @Nonnull final FilesystemService filesystemService,
                  final int cleanupInterval);

    /**
     * Initiate push of item resources to external filesystem
     * @param item              the item
     * @param resources         the item resources
     * @param user              the user performing the push
     * @throws ClientException if project not configured for push or if any resource cannot be pushed due to other task
     */
    void initiatePushItem(final ArchivableItem item,
                          final List<XnatAbstractresourceI> resources,
                          final UserI user) throws ClientException;
}
