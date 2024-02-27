// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.model.entity.RemoteFilesResourceStatus;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceTask;
import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.turbine.utils.ArchivableItem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public interface RemoteFilesTrackerEntityService extends BaseHibernateService<RemoteFilesTrackerEntity> {
    /**
     * Finds the entry for a resource
     *
     * @param resource the XNAT item
     * @return the tracker entity
     */
    @Nullable
    RemoteFilesTrackerEntity findByResource(final XnatResourcecatalog resource);

    /**
     * Finds the entry for a resource or creates one if it doesn't exist
     * @param item          the item
     * @param resource      the resource
     * @param nodeInfo      the node performing the task
     * @param status        the status
     * @return the entity
     */
    @Nonnull
    RemoteFilesTrackerEntity findOrCreate(ArchivableItem item,
                                          XnatResourcecatalog resource,
                                          XnatNodeInfo nodeInfo,
                                          RemoteFilesResourceStatus status);

    /**
     * Finds the entries for an item
     *
     * @param item the XNAT item
     * @return the tracker entity
     */
    List<RemoteFilesTrackerEntity> findByItemIdAndXsiType(final ArchivableItem item);

    /**
     * Set the item task and/or status
     *
     * @param remoteFilesTrackerEntity    the  tracker
     * @param xnatNodeInfo          the node setting the tracker
     * @param task                  the task or null for no change
     * @param status                the status or null for no change
     */
    void setTaskAndStatus(RemoteFilesTrackerEntity remoteFilesTrackerEntity, XnatNodeInfo xnatNodeInfo,
                          RemoteFilesResourceTask task, RemoteFilesResourceStatus status);


    /**
     * Set pull progress % on entity
     * @param remoteFilesTrackerEntity the entity
     * @param progress the progress
     */
    void setPullProgress(RemoteFilesTrackerEntity remoteFilesTrackerEntity, @Nullable Double progress);

    /**
     * Set lastIsLocalCheck on entity
     * @param remoteFilesTrackerEntity the entity
     */
    void setLastLocalCheck(RemoteFilesTrackerEntity remoteFilesTrackerEntity);

    /**
     * Create RemoteFilesTrackerEntity for item resource
     * @param item          the item
     * @param resource      the resource
     * @param nodeInfo      the node performing the task
     * @param task          the task
     * @param status        the status
     * @return the entity
     */
    RemoteFilesTrackerEntity createRemoteFilesTrackerEntity(ArchivableItem item,
                                                            XnatResourcecatalog resource,
                                                            XnatNodeInfo nodeInfo,
                                                            RemoteFilesResourceTask task,
                                                            RemoteFilesResourceStatus status);

}
