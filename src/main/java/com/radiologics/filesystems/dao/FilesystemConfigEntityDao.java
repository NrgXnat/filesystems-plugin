// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.dao;

import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;
import org.hibernate.Criteria;
import org.hibernate.Hibernate;
import org.hibernate.criterion.Restrictions;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FilesystemConfigEntityDao<E extends FilesystemConfigEntity>
        extends AbstractHibernateDAO<E> {
    @Override
    public void initialize(final E entity) {
        if (entity == null) {
            return;
        }
        Hibernate.initialize(entity.getPermittedProjects());
    }

    /**
     * Find entity with property, excluding entity with provided id (used for validation)
     * @param id the id to exclude
     * @param property the property
     * @param value the value
     * @return list of matching entities or null
     */
    @Nullable
    public List<E> findByExcluding(long id, String property, Object value) {
        Criteria criteria = this.getCriteriaForType();
        criteria.add(Restrictions.eq(property, value));
        criteria.add(Restrictions.ne("id", id));
        List<E> matches = criteria.list();
        if (matches == null || matches.isEmpty()) {
            return null;
        } else {
            return matches;
        }
    }

    /**
     * Find entity with timestamp after provided date
     * @param value the value
     * @return list of matching entities or null
     */
    @Nullable
    public List<E> findByTimestampAfter(Date value) {
        Criteria criteria = this.getCriteriaForType();
        criteria.add(Restrictions.gt("timestamp", value));
        List<E> matches = criteria.list();
        if (matches == null || matches.isEmpty()) {
            return null;
        } else {
            return matches;
        }
    }

    /**
     * Given list of ids, return any that have been deleted
     * @param ids the ids
     * @return subset of ids that have been deleted or null
     */
    @Nullable
    public Set<Long> findDeleted(Set<Long> ids) {
        if (ids.isEmpty()) {
            return null;
        }
        Criteria criteria = this.getCriteriaForType();
        criteria.add(Restrictions.in("id", ids));
        List<E> matches = criteria.list();
        if (matches == null || matches.isEmpty()) {
            // All deleted, return the input
            return ids;
        } else {
            for (E entity : matches) {
                ids.remove(entity.getId());
            }
            if (ids.isEmpty()) {
                return null;
            }
            return ids;
        }
    }

    /**
     * Allow entity update without changing timestamp
     *
     * <strong>When JPA persistence lifecycle support is working, this isn't going to matter</strong>
     *
     * @param entity the entity
     * @param setTimestamp True to increment timestamp, False to skip
     */
    public void update(final E entity, boolean setTimestamp) {
        if (setTimestamp) {
            // TODO: When JPA persistence lifecycle support is working this isn't going to matter
            entity.setTimestamp(new Date());
        }
        getSession().update(entity);
    }
}
