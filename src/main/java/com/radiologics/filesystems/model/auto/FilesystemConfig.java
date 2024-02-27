// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.model.auto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.NoClass;
import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@JsonInclude
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "filesystem",
        defaultImpl = NoClass.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AwsS3Config.class, name = "awss3")
})
public abstract class FilesystemConfig {
    protected long id;
    protected Set<String> permittedProjects = new HashSet<>();
    @JsonIgnore protected Set<ProjectFilesystemSettings> archivesForProjects = new HashSet<>();

    protected boolean archiver;
    protected boolean active;
    protected String name;
    protected boolean permitAllProjects = false;

    public enum FilesystemType {
        // should match FilesystemConfigEntity extender's DiscriminatorValue
        AWSS3("awss3");

        private String name;
        FilesystemType(String name) {
            this.name = name;
        }

        @Override
        public String toString(){
            return name;
        }
    }

    public FilesystemConfig() {}

    public FilesystemConfig(long id, boolean archiver, boolean active, String name, boolean permitAllProjects,
                            @Nullable Set<String> permittedProjects) {
        this.id = id;
        this.name = name;
        this.archiver = archiver;
        this.active = active;
        this.permitAllProjects = permitAllProjects;
        if (!permitAllProjects && permittedProjects != null) {
            this.permittedProjects = permittedProjects;
        }
    }

    public abstract FilesystemType getFilesystemType();

    public long getId() {
        return id;
    }

    public void setId(Long id) {
        if (id == null) {
            id = 0L;
        }
        this.id = id;
    }

    public boolean isArchiver() {
        return archiver;
    }

    public void setArchiver(boolean archiver) {
        this.archiver = archiver;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<String> getPermittedProjects() {
        return permittedProjects;
    }

    public void setPermittedProjects(Set<String> permittedProjects) {
        this.permittedProjects = permittedProjects;
    }

    public void addPermittedProject(String project) {
        permittedProjects.add(project);
    }

    public void removePermittedProject(String project) {
        permittedProjects.remove(project);
    }

    public Set<ProjectFilesystemSettings> getArchivesForProjects() {
        return archivesForProjects;
    }

    public void setArchivesForProjects(Set<ProjectFilesystemSettings> archivesForProjects) {
        this.archivesForProjects = archivesForProjects;
    }

    public void setArchivesForProjectsFromEntities(Set<ProjectFilesystemSettingsEntity> projectEntities) {
        this.archivesForProjects = projectEntities.stream().map(p -> p.toPojo(this))
                .collect(Collectors.toSet());
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPermitAllProjects() {
        return permitAllProjects;
    }

    public void setPermitAllProjects(boolean permitAllProjects) {
        this.permitAllProjects = permitAllProjects;
    }

    public void updatePermittedProjects(AwsS3Config otherConfig) throws InvalidEntityException {
        if (id != otherConfig.getId()) {
            throw new InvalidEntityException("Cannot update " + name + " from " + otherConfig.getName() +
                    ": ids don't match");
        }
        permitAllProjects = otherConfig.isPermitAllProjects();
        permittedProjects = otherConfig.getPermittedProjects();
    }
}