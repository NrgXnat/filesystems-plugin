package com.radiologics.filesystems.model.entity;

import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import org.hibernate.Hibernate;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.*;

@Entity
public class ProjectFilesystemSettingsEntity extends AbstractHibernateEntity {
    public static final int defaultCleanupInterval = 7;
    public static final boolean defaultDirectUploadOutputs = false;
    private String projectId;
    private int cleanupInterval = defaultCleanupInterval;
    private boolean directUploadOutputs = defaultDirectUploadOutputs;

    @Nullable private FilesystemConfigEntity archiverConfig;

    public ProjectFilesystemSettingsEntity() {
        super();
    }

    @Nonnull
    @Column(unique = true, nullable = false)
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    @Column(nullable = false, columnDefinition = "int default " + defaultCleanupInterval)
    public int getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(int cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    @Column(columnDefinition = "boolean default " + defaultDirectUploadOutputs)
    public boolean isDirectUploadOutputs() {
        return directUploadOutputs;
    }

    public void setDirectUploadOutputs(boolean directUploadOutputs) {
        this.directUploadOutputs = directUploadOutputs;
    }

    @ManyToOne(fetch = FetchType.LAZY,
            cascade = {CascadeType.DETACH, CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REFRESH})
    @JoinColumn(name="archiver_filesystem_config_id")
    @Nullable
    public FilesystemConfigEntity getArchiverConfig() {
        return archiverConfig;
    }

    public void setArchiverConfig(@Nullable FilesystemConfigEntity archiverConfig) {
        this.archiverConfig = archiverConfig;
    }

    public ProjectFilesystemSettings toPojo() {
        FilesystemConfig archiverPojo = null;
        Hibernate.initialize(archiverConfig);
        if (archiverConfig != null) {
            archiverPojo = archiverConfig.toPojo();
        }
        return new ProjectFilesystemSettings(getId(), projectId, cleanupInterval, directUploadOutputs,
                archiverPojo);
    }

    public ProjectFilesystemSettings toPojo(FilesystemConfig archiverPojo) {
        Hibernate.initialize(archiverConfig);
        if (archiverConfig == null || archiverConfig.getId() != archiverPojo.getId()) {
            archiverPojo = null;
        }
        return new ProjectFilesystemSettings(getId(), projectId, cleanupInterval, directUploadOutputs,
                archiverPojo);
    }

    @Override
    public String toString() {
        return "ProjectFilesystemSettingsEntity{" +
                "projectId='" + projectId + '\'' +
                ", cleanupInterval=" + cleanupInterval +
                ", directUploadOutputs=" + directUploadOutputs +
                ", archiverConfig=" + archiverConfig +
                '}';
    }
}
