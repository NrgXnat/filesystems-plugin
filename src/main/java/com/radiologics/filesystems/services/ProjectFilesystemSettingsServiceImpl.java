// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.exceptions.NoSuchFilesystemException;
import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.exceptions.UnsupportedRemoteFilesOperationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ProjectFilesystemSettingsServiceImpl
        implements ProjectFilesystemSettingsService {

    private final ProjectFilesystemSettingsEntityService projectFilesystemSettingsEntityService;
    private final List<FilesystemService> filesystemServices;
    private final Map<FilesystemConfig.FilesystemType, FilesystemConfigEntityService<? extends FilesystemConfigEntity>>
            filesystemConfigEntityServiceMap = new HashMap<>();

    @Autowired
    public ProjectFilesystemSettingsServiceImpl(final ProjectFilesystemSettingsEntityService projectFilesystemSettingsEntityService,
                                                final List<FilesystemService> filesystemServices,
                                                final List<FilesystemConfigEntityService<? extends FilesystemConfigEntity>> filesystemConfigEntityServices) {
        this.projectFilesystemSettingsEntityService = projectFilesystemSettingsEntityService;
        this.filesystemServices = filesystemServices;
        for (FilesystemConfigEntityService<? extends FilesystemConfigEntity> service : filesystemConfigEntityServices) {
            filesystemConfigEntityServiceMap.put(service.getFilesystemType(), service);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public List<ProjectFilesystemSettings> getPojoForAllProjects(final UserI user) {
        Map<String, ProjectFilesystemSettings> projectSettings = projectFilesystemSettingsEntityService.getProjectPojosMap();
        List<ProjectFilesystemSettings> pojos = new ArrayList<>();
        for (XnatProjectdata project : AutoXnatProjectdata.getAllXnatProjectdatas(user, false)) {
            String pid = project.getId();
            if (projectSettings.containsKey(pid)) {
                pojos.add(projectSettings.get(pid));
            } else {
                pojos.add(new ProjectFilesystemSettings(pid));
            }
        }
        return pojos;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public FilesystemService getArchiverFilesystemForProject(String project, boolean processingOutputs)
            throws UnsupportedRemoteFilesOperationException, NoSuchFilesystemException {
        ProjectFilesystemSettings pojo;
        try {
            pojo = getSettingsForProject(project);
        } catch (NotFoundException e) {
            throw new UnsupportedRemoteFilesOperationException(e.getMessage(), e);
        }

        if (processingOutputs) {
            if (!pojo.isDirectUploadOutputs()) {
                throw new UnsupportedRemoteFilesOperationException("Project " + project + " not configured to automatically " +
                        "upload processing outputs to remote filesystem");
            }
        }

        FilesystemConfig fsConfig = pojo.getArchiverConfig();
        if (fsConfig == null || !fsConfig.isActive() || !fsConfig.isArchiver()) {
            throw new UnsupportedRemoteFilesOperationException("No archiver filesystem configured for project " +
                    project);
        }

        return getFilesystemServiceServingConfig(fsConfig);
    }

    /**
     * Get filesystem service that can serves fsConfig
     * @param fsConfig the FS config POJO
     * @return the filesystem service
     */
    private FilesystemService getFilesystemServiceServingConfig(FilesystemConfig fsConfig) {
        for (FilesystemService fs : filesystemServices) {
            if (fs.servesConfig(fsConfig)) {
                return fs;
            }
        }
        throw new NoSuchFilesystemException("No filesystem service configured for config " + fsConfig);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = {InvalidEntityException.class, NotFoundException.class, RuntimeException.class, Error.class})
    public void createOrUpdateFromPojo(ProjectFilesystemSettings pojo)
            throws InvalidEntityException, NotFoundException {

        long id = pojo.getId();
        ProjectFilesystemSettingsEntity entity;
        if (id == 0L) {
            // creating
            entity = new ProjectFilesystemSettingsEntity();
            setParamsFromPojo(entity, pojo);
            ProjectFilesystemSettingsEntity created = projectFilesystemSettingsEntityService.create(entity);
            if (created == null) {
                throw new InvalidEntityException("Unable to store entity");
            }
        } else {
            entity = projectFilesystemSettingsEntityService.get(id);
            updateArchiverFilesystemService(entity.toPojo(), true);
            setParamsFromPojo(entity, pojo);
            projectFilesystemSettingsEntityService.update(entity);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public ProjectFilesystemSettings getSettingsForProject(String project) throws NotFoundException {
        return projectFilesystemSettingsEntityService.findByProjectAndReturnPojo(project);
    }

    /**
     * Update archiver filesystem service after changing project settings
     * @param pojo the project settings pojo
     * @param remove if we're removing an archiver config from this project
     * @throws InvalidEntityException if archiver config id not found in filesystem service cache
     * @throws NoSuchFilesystemException if cannot determine filesystem service for archiver
     */
    private void updateArchiverFilesystemService(ProjectFilesystemSettings pojo, boolean remove)
            throws InvalidEntityException, NoSuchFilesystemException {
        FilesystemConfig archiverConfig = pojo.getArchiverConfig();
        if (archiverConfig == null) {
            return;
        }
        FilesystemService archiverFilesystemService = getFilesystemServiceServingConfig(archiverConfig);
        Long archiverId = remove ? null : archiverConfig.getId();
        archiverFilesystemService.updateProjectArchiver(pojo.getProjectId(), archiverId);
    }

    /**
     * Set parameters from pojo, validate (activate)
     * @param entity    the entity object
     * @param pojo      the pojo
     * @throws InvalidEntityException if archiver entity with id cannot be found
     */
    private void setParamsFromPojo(ProjectFilesystemSettingsEntity entity, ProjectFilesystemSettings pojo)
            throws InvalidEntityException {
        String project = pojo.getProjectId();
        entity.setProjectId(project);
        entity.setCleanupInterval(pojo.getCleanupInterval());
        entity.setDirectUploadOutputs(pojo.isDirectUploadOutputs());
        FilesystemConfig fsConfig = pojo.getArchiverConfig();
        FilesystemConfigEntity fsEntity = null;
        if (fsConfig != null) {
            FilesystemConfigEntityService<? extends FilesystemConfigEntity> service =
                    filesystemConfigEntityServiceMap.get(fsConfig.getFilesystemType());
            try {
                fsEntity = service.retrieveAndAddPermittedProject(fsConfig.getId(), project);
            } catch (NotFoundException e) {
                throw new InvalidEntityException("No filesystem config entity with id " + fsConfig.getId(), e);
            }
        }
        entity.setArchiverConfig(fsEntity);
        updateArchiverFilesystemService(pojo, false);
    }
}
