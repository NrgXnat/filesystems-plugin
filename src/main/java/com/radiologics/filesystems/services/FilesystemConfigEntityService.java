// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface FilesystemConfigEntityService<E extends FilesystemConfigEntity>
        extends BaseHibernateService<E> {

    /**
     * Return the filesystem type this service supports
     * @return the filesystem type
     */
    FilesystemConfig.FilesystemType getFilesystemType();

    /**

     * Retrieve and add permitted project to entity
     * @param id the entity id
     * @param projectToAdd the project to add
     * @return the entity
     * @throws NotFoundException if entity with id cannot be found
     */
    @Nonnull
    E retrieveAndAddPermittedProject(long id, String projectToAdd) throws NotFoundException;


    @Nullable
    E findByUniqueProperty(final String property, final Object value);
}
