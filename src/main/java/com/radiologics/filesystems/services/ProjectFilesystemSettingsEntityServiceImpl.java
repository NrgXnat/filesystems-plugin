// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.dao.ProjectFilesystemSettingsEntityDao;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProjectFilesystemSettingsEntityServiceImpl extends
        AbstractHibernateEntityService<ProjectFilesystemSettingsEntity, ProjectFilesystemSettingsEntityDao>
        implements ProjectFilesystemSettingsEntityService {

    /**
     * {@inheritDoc}
     */
    @Transactional(rollbackFor = {NotFoundException.class, RuntimeException.class, Error.class})
    @Nonnull
    @Override
    public ProjectFilesystemSettings findByProjectAndReturnPojo(String project) throws NotFoundException {
        ProjectFilesystemSettingsEntity entity = getDao().findByUniqueProperty("projectId", project);
        if (entity == null) {
            throw new NotFoundException("Project " + project + " not configured for filesystems plugin");
        }
        initialize(entity);
        return entity.toPojo();
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Nonnull
    @Override
    public Map<String, ProjectFilesystemSettings> getProjectPojosMap() {
        return getAll().stream().collect(Collectors.toMap(ProjectFilesystemSettingsEntity::getProjectId,
                        ProjectFilesystemSettingsEntity::toPojo));
    }
}
