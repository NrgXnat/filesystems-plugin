// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.dao;

import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

@Repository
public class RemoteFilesTrackerEntityDao extends AbstractHibernateDAO<RemoteFilesTrackerEntity> {
}
