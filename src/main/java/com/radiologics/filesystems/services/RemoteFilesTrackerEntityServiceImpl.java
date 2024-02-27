// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.dao.RemoteFilesTrackerEntityDao;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceStatus;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceTask;
import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.auto.AutoXnatAbstractresource;
import org.nrg.xdat.security.helpers.Users;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
public class RemoteFilesTrackerEntityServiceImpl
        extends AbstractHibernateEntityService<RemoteFilesTrackerEntity, RemoteFilesTrackerEntityDao>
        implements RemoteFilesTrackerEntityService {

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Nullable
    @Override
    public RemoteFilesTrackerEntity findByResource(final XnatResourcecatalog resource) {
        return getDao().findByUniqueProperty("abstractResourceId", resource.getXnatAbstractresourceId());
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Nonnull
    @Override
    public RemoteFilesTrackerEntity findOrCreate(ArchivableItem item, XnatResourcecatalog resource,
                                                 XnatNodeInfo nodeInfo, RemoteFilesResourceStatus status) {
        RemoteFilesTrackerEntity entity = findByResource(resource);
        if (entity != null) {
            return entity;
        }

        return createRemoteFilesTrackerEntity(item, resource, nodeInfo, RemoteFilesResourceTask.None, status);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public List<RemoteFilesTrackerEntity> findByItemIdAndXsiType(final ArchivableItem item) {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("itemId", item.getId());
        properties.put("itemXsiType", item.getXSIType());
        List<RemoteFilesTrackerEntity> entities = getDao().findByProperties(properties);
        if (entities == null || entities.isEmpty()) {
            return entities;
        }

        //TODO review this. would prefer to remove on catalog delete since it's expensive and that's the less common case
        final UserI admin = Users.getAdminUser();
        return entities.stream().filter( e -> {
            if (AutoXnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(e.getAbstractResourceId(),
                    admin, false) == null) {
                // Resource has been deleted, delete the entity and don't return it
                delete(e);
                return false;
            } else {
                return true;
            }
        }).collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void setTaskAndStatus(RemoteFilesTrackerEntity remoteFilesTrackerEntity,
                                 XnatNodeInfo xnatNodeInfo,
                                 @Nullable RemoteFilesResourceTask task,
                                 @Nullable RemoteFilesResourceStatus status) {
        if (status != null) remoteFilesTrackerEntity.setStatus(status);
        if (task != null) remoteFilesTrackerEntity.setTask(task);
        remoteFilesTrackerEntity.setPullProgress(null);
        remoteFilesTrackerEntity.setNodeInfo(xnatNodeInfo);
        remoteFilesTrackerEntity.setRemoveForUnchangedStatus(false);
        update(remoteFilesTrackerEntity);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void setPullProgress(RemoteFilesTrackerEntity remoteFilesTrackerEntity, @Nullable Double progress) {
        remoteFilesTrackerEntity.setPullProgress(progress);
        update(remoteFilesTrackerEntity);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void setLastLocalCheck(RemoteFilesTrackerEntity remoteFilesTrackerEntity) {
        remoteFilesTrackerEntity.setLastLocalCheck(ZonedDateTime.now());
        update(remoteFilesTrackerEntity);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public RemoteFilesTrackerEntity createRemoteFilesTrackerEntity(ArchivableItem item,
                                                                   XnatResourcecatalog resource,
                                                                   XnatNodeInfo nodeInfo,
                                                                   RemoteFilesResourceTask task,
                                                                   RemoteFilesResourceStatus status) {

        return create(new RemoteFilesTrackerEntity(item, resource, nodeInfo, task, status));
    }
}
