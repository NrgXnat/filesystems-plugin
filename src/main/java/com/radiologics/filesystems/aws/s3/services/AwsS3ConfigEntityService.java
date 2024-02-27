// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.aws.s3.services;

import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.aws.s3.model.entity.AwsS3ConfigEntity;
import com.radiologics.filesystems.exceptions.InvalidArchiverEntityException;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.services.FilesystemConfigEntityService;
import org.nrg.framework.exceptions.NotFoundException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public interface AwsS3ConfigEntityService extends FilesystemConfigEntityService<AwsS3ConfigEntity> {
    /**
     * Retrieve all filesystem config entity POJOs
     * @return list of  filesystem config entity POJOs
     */
    @Nonnull
    List<AwsS3Config> getPojoForAllConfigs();

    /**
     * Delete entity
     * @param id the id of the entity to be deleted
     * @param filesystemService the filesystem service that may need to be updated accordingly
     */
    void deleteById(long id, AwsS3FilesystemService filesystemService);

    /**
     * Refresh entities (try to activate them, set active=false if cannot), return POJOS
     * @param queryTime date of last cache refresh (will be updated with this query's time)
     * @param refreshAll true to retrieve all entities; false for only those entities that have changed since queryTime
     * @return the POJOs
     */
    List<AwsS3Config> queryRefreshAndReturnPojos(AtomicReference<Date> queryTime, boolean refreshAll);

    /**
     * Update permitted project list
     * @param id        entity id
     * @param permitted permitted or not
     * @param projects  list of project ids or null to permit or block all
     * @param filesystemService the filesystem service that may need to be updated accordingly
     * @return the updated POJO (not activated since we're not changing access params)
     * @throws NotFoundException if entity with id cannot be found
     * @throws InvalidEntityException if entity associated with this id in filesystemService is not the entity
     * referenced here
     */
    @Nonnull
    AwsS3Config updatePermittedProjects(long id,
                                        boolean permitted,
                                        @Nullable List<String> projects,
                                        AwsS3FilesystemService filesystemService)
            throws NotFoundException, InvalidEntityException;

    /**
     * Create or update from config POJO (used for JSON serialization)
     * @param pojo the config
     * @param updatePermittedProjects whether to update permitted projects based on pojo or not
     * @param filesystemService the filesystem service that may need to be updated accordingly
     * @return config with s3 object
     * @throws InvalidEntityException for issues creating/updating (e.g., non unique bucket name)
     * @throws NotFoundException if entity with id cannot be found
     */
    @Nonnull
    AwsS3Config createOrUpdateFromPojo(AwsS3Config pojo,
                                       boolean updatePermittedProjects,
                                       AwsS3FilesystemService filesystemService)
            throws NotFoundException, InvalidEntityException;

    /**
     * Given list of ids, return any that have been deleted
     * @param currentIds list of ids to check
     * @return subset of currentIds that have been deleted or null
     */
    @Nullable
    Set<Long> queryDeleted(Set<Long> currentIds);

    /**
     * Get pojo by id
     * @param id the id
     * @return the pojo
     */
    FilesystemConfig getPojoById(long id);

    /**
     * Refresh config (in case it's incorrectly set to "active=false" due to AWS issues, etc
     * @param id the ID
     * @param filesystemService the filesystem service that may need to be updated accordingly
     * @throws NotFoundException if entity with id cannot be found
     * @throws InvalidEntityException if entity is now invalid (shouldn't happen unless bucket deleted or versioning changed)
     * @throws InvalidArchiverEntityException if entity is no longer writable and archiver was TRUE
     */
    void refresh(long id, AwsS3FilesystemService filesystemService) throws NotFoundException, InvalidEntityException, InvalidArchiverEntityException;
}
