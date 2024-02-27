// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xnat.exceptions.UnsupportedRemoteFilesOperationException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public interface ProjectFilesystemSettingsEntityService extends BaseHibernateService<ProjectFilesystemSettingsEntity> {

    /**
     * Retrieve project settings POJO by project id string
     * @param project project id string {@link XnatProjectdata#getId()}
     * @return the POJO
     * @throws NotFoundException if there is no entity for the provided project
     */
    ProjectFilesystemSettings findByProjectAndReturnPojo(String project) throws NotFoundException;

    /**
     * Get all pojos
     * @return map of project id to project settings POJO
     */
    Map<String, ProjectFilesystemSettings> getProjectPojosMap();
}
