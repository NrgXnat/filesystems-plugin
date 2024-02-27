// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.exceptions.NoSuchFilesystemException;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.exceptions.UnsupportedRemoteFilesOperationException;

import javax.annotation.Nonnull;
import java.util.List;

public interface ProjectFilesystemSettingsService {

    /**
     * Retrieve project settings entity POJO for all projects in XNAT
     * {@link XnatProjectdata#getAllXnatProjectdatas(UserI, boolean)}
     * @param user user running the query
     * @return list of project settings entity POJOs
     */
    List<ProjectFilesystemSettings> getPojoForAllProjects(final UserI user);

    /**
     * Get the filesystem service instance for the archiver filesystem for a project by project id string
     * @param project the project
     * @param processingOutputs true if using filesystem service to upload processing outputs
     * @return the filesystem service instance
     * @throws UnsupportedRemoteFilesOperationException if project not configured to archive
     * @throws NoSuchFilesystemException if no filesystem configured to handle archiver
     * [processing outputs if processingOutputs=true]
     */
    FilesystemService getArchiverFilesystemForProject(String project, boolean processingOutputs)
            throws UnsupportedRemoteFilesOperationException, NoSuchFilesystemException;

    /**
     * Create or update project settings entity based on POJO, update relevant filesystem services if needed
     * @param pojo the project settings POJO
     * @throws InvalidEntityException if POJO invalid
     * @throws NotFoundException if attempting to update an invalid id
     * @throws NoSuchFilesystemException if no filesystem configured to handle archiver
     */
    void createOrUpdateFromPojo(ProjectFilesystemSettings pojo)
            throws InvalidEntityException, NotFoundException, NoSuchFilesystemException;

    /**
     * Get project settings pojo for project
     * @param project the project id
     * @return the pojo
     * @throws NotFoundException if no entity for project
     */
    @Nonnull
    ProjectFilesystemSettings getSettingsForProject(String project) throws NotFoundException;
}
