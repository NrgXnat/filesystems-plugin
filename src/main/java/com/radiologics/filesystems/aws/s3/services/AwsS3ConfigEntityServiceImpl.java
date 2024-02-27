// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.aws.s3.services;

import com.radiologics.filesystems.aws.s3.dao.AwsS3ConfigEntityDao;
import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.aws.s3.model.entity.AwsS3ConfigEntity;
import com.radiologics.filesystems.exceptions.FilesystemServiceException;
import com.radiologics.filesystems.exceptions.InvalidArchiverEntityException;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.exceptions.RemoteApiException;
import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xdat.security.helpers.Users;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AwsS3ConfigEntityServiceImpl
        extends AbstractHibernateEntityService<AwsS3ConfigEntity, AwsS3ConfigEntityDao>
        implements AwsS3ConfigEntityService {

    /**
     * {@inheritDoc}
     */
    @Override
    public FilesystemConfig.FilesystemType getFilesystemType() {
        return FilesystemConfig.FilesystemType.AWSS3;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    @Nonnull
    public List<AwsS3Config> getPojoForAllConfigs() {
        return getAll().stream().map(AwsS3ConfigEntity::toPojo)
                .sorted(Comparator.comparingLong(FilesystemConfig::getId))
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = {NotFoundException.class, RuntimeException.class, Error.class})
    @Nonnull
    public AwsS3ConfigEntity retrieveAndAddPermittedProject(long id, String projectToAdd) throws NotFoundException {
        AwsS3ConfigEntity entity = get(id);
        entity.addPermittedProject(projectToAdd);
        update(entity);
        return entity;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    @Nullable
    public AwsS3ConfigEntity findByUniqueProperty(String property, Object value) {
        return getDao().findByUniqueProperty(property, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = {InvalidEntityException.class, RuntimeException.class, Error.class})
    @Nonnull
    public AwsS3Config createOrUpdateFromPojo(AwsS3Config pojo,
                                              boolean updatePermittedProjects,
                                              AwsS3FilesystemService filesystemService)
            throws NotFoundException, InvalidEntityException {
        long id = pojo.getId();
        AwsS3Config configWithClient;
        if (id == 0L) {
            configWithClient = createFromPojo(pojo);
        } else {
            configWithClient = updateFromPojo(id, pojo, updatePermittedProjects);
        }
        filesystemService.refresh(configWithClient);
        return configWithClient;
    }

    /**
     * Create entity from POJO
     * @param pojo the pojo
     * @return updated, activated pojo
     * @throws InvalidEntityException if POJO is not valid or cannot be activated
     */
    @Nonnull
    private AwsS3Config createFromPojo(AwsS3Config pojo) throws InvalidEntityException {
        AwsS3ConfigEntity entity = new AwsS3ConfigEntity();
        AwsS3Config configWithClient;
        try {
            configWithClient = setParamsFromPojo(entity, pojo, true, true);
            entity.setActive(configWithClient.isActive());
        } catch (RemoteApiException e) {
            // Upon create, treat credentials/connectivity issues as invalid
            throw new InvalidEntityException(e);
        }
        AwsS3ConfigEntity created = create(entity);
        if (created == null) {
            throw new InvalidEntityException("Unable to store entity");
        }
        configWithClient.setId(created.getId());
        return configWithClient;
    }


    /**
     * Update entity from pojo
     * @param id the id
     * @param pojo the pojo
     * @param updatePermittedProjects whether to update permitted projects (not always populated in pojo)
     * @return pojo for updated entity, including aws s3 java client
     * @throws NotFoundException if no entity with this id
     * @throws InvalidEntityException if invalid
     */
    @Nonnull
    private AwsS3Config updateFromPojo(long id, AwsS3Config pojo, boolean updatePermittedProjects)
            throws NotFoundException, InvalidEntityException {
        AwsS3ConfigEntity entity = get(id);
        AwsS3Config configWithClient;
        boolean credentialOrBucketUpdate = isUpdatingCredentialsOrBucketName(entity, pojo);
        try {
            configWithClient = setParamsFromPojo(entity, pojo, false, updatePermittedProjects);
            entity.setActive(configWithClient.isActive());
        } catch (RemoteApiException e) {
            if (credentialOrBucketUpdate) {
                // treat credentials/connectivity issues as invalid
                throw new InvalidEntityException(e);
            } else {
                // Since creds & bucket haven't changed, assume it's a connectivity issue and set active=false,
                // allowing other changes to persist
                entity.setActive(false);
                configWithClient = entity.toPojo();
            }
        }
        update(entity);
        configWithClient.setArchivesForProjectsFromEntities(entity.getArchivesForProjects());
        return configWithClient;
    }

    /**
     * Will updating the entity based on POJO change credentials?
     * @param entity the entity
     * @param pojo the POJO for updating
     * @return T/F
     */
    private boolean isUpdatingCredentialsOrBucketName(AwsS3ConfigEntity entity, AwsS3Config pojo) {
        return !Objects.equals(entity.getAccessKey(), pojo.getAccessKey()) ||
                !Objects.equals(entity.getSecretKey(), pojo.getSecretKey()) ||
                !Objects.equals(entity.getBucketName(), pojo.getBucketName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    @Nullable
    public Set<Long> queryDeleted(Set<Long> currentIds) {
        return getDao().findDeleted(currentIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    @Nullable
    public FilesystemConfig getPojoById(long id) {
        try {
            AwsS3ConfigEntity entity = get(id);
            initialize(entity);
            return entity.toPojo();
        } catch (NotFoundException e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * Note: do not rollback for InvalidEntityException here bc we want our updates to persist
     */
    @Override
    @Transactional(rollbackFor = {RuntimeException.class, Error.class})
    public void refresh(long id, AwsS3FilesystemService filesystemService) throws NotFoundException, InvalidEntityException {
        AwsS3ConfigEntity entity = get(id);
        refreshAndReturnPojo(entity, filesystemService);
    }

    /**
     * Initialize S3 client for entity, update db if anything has changed, return the POJO
     * @param entity the entity
     * @return the POJO
     * @throws InvalidEntityException if entity is no longer active or can no longer archive
     */
    private AwsS3Config refreshAndReturnPojo(AwsS3ConfigEntity entity) throws InvalidEntityException {
        return refreshAndReturnPojo(entity, null);
    }

    /**
     * Initialize S3 client for entity, update db if anything has changed, return the POJO
     * @param entity the entity
     * @param filesystemService the filesystem service that may need to be updated accordingly
     * @return the POJO
     * @throws InvalidEntityException if entity is no longer active or can no longer archive
     */
    private AwsS3Config refreshAndReturnPojo(AwsS3ConfigEntity entity, @Nullable AwsS3FilesystemService filesystemService)
            throws InvalidEntityException {
        InvalidEntityException exception = null;
        boolean needsUpdate = false;
        initialize(entity);
        final AwsS3Config config = entity.toPojo();
        try {
            config.initializeS3();
        } catch (InvalidEntityException | RemoteApiException e) {
            if (e instanceof InvalidArchiverEntityException) {
                log.debug("AWS config for bucket {} cannot be used for archival", entity.getBucketName(), e);
            } else {
                log.debug("AWS config for bucket {} not active", entity.getBucketName(), e);
            }
            //entity.removeArchivesForProjects(); // don't run this so that if config is fixed, user doesn't have to re-add
            entity.setArchiver(false);
            needsUpdate = true;
            if (e instanceof InvalidEntityException) {
                exception = (InvalidEntityException) e;
            } else {
                exception = new InvalidEntityException(e);
            }
        }
        if (needsUpdate || config.isActive() != entity.isActive()) {
            entity.setActive(config.isActive());
            getDao().update(entity, false);
            // Once @UpdateTimestamp works, this update call will increment the timestamp, which means we'll rerun
            // this method next cache update just due to changing the "active" param: not ideal, but not disastrous.
            // It IS problematic if this method runs too much, as config.initializeS3() opens a java socket.
        } else {
            // Refresh from db to accurately getArchivesForProjects (update call achieves same thing)
            getDao().refresh(true, entity);
        }
        config.setArchivesForProjectsFromEntities(entity.getArchivesForProjects());
        if (filesystemService != null) {
            filesystemService.refresh(config);
            if (exception != null) {
                throw exception;
            }
        }
        return config;
    }

    /**
     * Set parameters from pojo, validate (activate). Never changes archivesForProjects nor active.
     * @param entity    the entity object
     * @param pojo      the pojo
     * @param create    true if creating, false if updating
     * @param updatePermittedProjects whether to update permitted projects based on pojo or not
     * @return activated pojo
     * @throws InvalidEntityException if invalid
     */
    private AwsS3Config setParamsFromPojo(AwsS3ConfigEntity entity,
                                          AwsS3Config pojo,
                                          boolean create,
                                          boolean updatePermittedProjects)
            throws InvalidEntityException, RemoteApiException {

        entity.setBucketName(pojo.getBucketName());
        entity.setAccessKey(pojo.getAccessKey());
        entity.setSecretKey(pojo.getSecretKey());
        entity.setSubdirectory(pojo.getSubdirectory());
        entity.setArchiver(pojo.isArchiver());
        entity.setName(pojo.getName());
        entity.setPermitAllProjects(pojo.isPermitAllProjects());
        if (updatePermittedProjects) {
            entity.setPermittedProjects(pojo.getPermittedProjects());
        }
        return validate(entity, create);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void deleteById(long id, AwsS3FilesystemService filesystemService) {
        delete(id);
        filesystemService.deactivate(id);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    @Nullable
    public List<AwsS3Config> queryRefreshAndReturnPojos(AtomicReference<Date> queryTime, boolean refreshAll) {
        Date lastQuery = queryTime.getAndSet(new Date());
        if (refreshAll) {
            return refreshAndReturnPojos(getAll());
        } else {
            return refreshAndReturnPojos(getDao().findByTimestampAfter(lastQuery));
        }
    }

    /**
     * Refresh entities in list
     * @param entities list of entities to refresh
     * @return null or list of activated configs
     */
    @Nullable
    private List<AwsS3Config> refreshAndReturnPojos(@Nullable List<AwsS3ConfigEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        List<AwsS3Config> configs = new ArrayList<>();
        for (AwsS3ConfigEntity entity : entities) {
            try {
                configs.add(refreshAndReturnPojo(entity));
            } catch (InvalidEntityException e) {
                log.error("Unable to refresh {}; this shouldn't happen as exceptions should be swallowed during " +
                        "cache refresh", entity.getBucketName(), e);
            }
        }
        return configs;
    }


    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Transactional(rollbackFor = {InvalidEntityException.class, RuntimeException.class, Error.class})
    @Override
    public AwsS3Config updatePermittedProjects(long id,
                                               boolean permitted,
                                               @Nullable List<String> projects,
                                               AwsS3FilesystemService filesystemService)
            throws NotFoundException, InvalidEntityException {
        AwsS3ConfigEntity entity = get(id);
        initialize(entity);

        boolean applyToAll = projects == null;
        if (permitted) {
            if (applyToAll) {
                entity.setPermitAllProjects(true);
            } else {
                for (String project : projects) {
                    entity.addPermittedProject(project);
                }
            }
        } else {
            if (applyToAll) {
                // Only permit for projects using this as an archiver
                entity.setPermitAllProjects(false);
                entity.setPermittedProjects(entity.getArchivesForProjects().stream()
                        .map(ProjectFilesystemSettingsEntity::getProjectId).collect(Collectors.toSet()));
            } else {
                if (entity.isPermitAllProjects()) {
                    entity.setPermitAllProjects(false);
                    // Remove access for just the provided projects by adding access for all projects and then removing
                    // these (should be a rare operation)
                    entity.setPermittedProjects(XnatProjectdata.getAllXnatProjectdatas(Users.getAdminUser(), false).stream()
                            .map(AutoXnatProjectdata::getId).collect(Collectors.toSet()));
                }
                Set<String> archivesFor = null;
                if (entity.isArchiver()) {
                    archivesFor = entity.getArchivesForProjects().stream()
                            .map(ProjectFilesystemSettingsEntity::getProjectId).collect(Collectors.toSet());
                }
                for (String project : projects) {
                    if (archivesFor != null && archivesFor.contains(project)) {
                        // Don't remove read access for projects this FS archives
                        continue;
                    }
                    entity.removePermittedProject(project);
                }
            }
        }
        update(entity);

        AwsS3Config pojo = entity.toPojo();
        filesystemService.updateProjectsForConfig(pojo);
        return pojo;
    }

    /**
     * Validate the entity before save. Tests
     * (1) entity doesn't conflict with existing entities (bucket must be unique),
     * (2) entity bucket exists
     * (3) if entity is archiver, versioning is on
     * (4) can connect to remote API
     *
     * @param entity the entity
     * @param create T if creating, F if updating
     * @return the config POJO with S3 client object
     * @throws InvalidEntityException if invalid per above
     * @throws RemoteApiException if unable to connect to remote API - could be bad credentials or could be
     * connectivity issues
     */
    private AwsS3Config validate(AwsS3ConfigEntity entity, boolean create)
            throws InvalidEntityException, RemoteApiException {
        // Bucket cannot be null, but we have to relax this db constraint bc of our single table inheritance
        String bucket = entity.getBucketName();
        if (bucket == null) {
            throw new InvalidEntityException("You must specify a bucket");
        }

        // entity doesn't conflict with existing entities (bucket must be unique)
        boolean valid;
        if (create) {
            valid = getDao().findByUniqueProperty("bucketName", bucket) == null;
        } else {
            valid = getDao().findByExcluding(entity.getId(), "bucketName", bucket) == null;
        }
        if (!valid) {
            throw new InvalidEntityException("A config for bucket " + bucket + " already exists");
        }
        return entity.toActivatedPojo();
    }
}
