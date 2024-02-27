// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.dao;

import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import org.hibernate.Hibernate;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectFilesystemSettingsEntityDao extends AbstractHibernateDAO<ProjectFilesystemSettingsEntity> {
    @Override
    public void initialize(final ProjectFilesystemSettingsEntity entity) {
        if (entity == null) {
            return;
        }
        FilesystemConfigEntity fsEntity = entity.getArchiverConfig();
        if (fsEntity != null) {
            Hibernate.initialize(fsEntity);
            Hibernate.initialize(fsEntity.getPermittedProjects());
        }
    }
}
