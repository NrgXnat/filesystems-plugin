// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.model.auto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

@Slf4j
@JsonInclude
public class ProjectFilesystemSettings {
    protected long id;

    private String projectId;
    private int cleanupInterval;
    private boolean directUploadOutputs = false;

    @Nullable private FilesystemConfig archiverConfig;

    public ProjectFilesystemSettings() {
        super();
    }

    public ProjectFilesystemSettings(String projectId) {
        this.id = 0L;
        this.projectId = projectId;
        this.cleanupInterval = ProjectFilesystemSettingsEntity.defaultCleanupInterval;
        this.directUploadOutputs = ProjectFilesystemSettingsEntity.defaultDirectUploadOutputs;
    }

    public ProjectFilesystemSettings(long id, String projectId, int cleanupInterval,
                                     boolean directUploadOutputs, @Nullable FilesystemConfig archiverConfig) {
        this.id = id;
        this.projectId = projectId;
        this.cleanupInterval = cleanupInterval;
        this.directUploadOutputs = directUploadOutputs;
        this.archiverConfig = archiverConfig;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @Nonnull
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public int getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(int cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public boolean isDirectUploadOutputs() {
        return directUploadOutputs;
    }

    public void setDirectUploadOutputs(boolean directUploadOutputs) {
        this.directUploadOutputs = directUploadOutputs;
    }

    @Nullable
    public FilesystemConfig getArchiverConfig() {
        return archiverConfig;
    }

    public void setArchiverConfig(@Nullable FilesystemConfig archiverConfig) {
        this.archiverConfig = archiverConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectFilesystemSettings that = (ProjectFilesystemSettings) o;
        return id == that.id &&
                cleanupInterval == that.cleanupInterval &&
                directUploadOutputs == that.directUploadOutputs &&
                projectId.equals(that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, projectId, cleanupInterval, directUploadOutputs);
    }

    @Override
    public String toString() {
        return "ProjectFilesystemSettings{" +
                "id=" + id +
                ", projectId='" + projectId + '\'' +
                ", cleanupInterval=" + cleanupInterval +
                ", directUploadOutputs=" + directUploadOutputs +
                ", archiverConfig=" + archiverConfig +
                '}';
    }
}