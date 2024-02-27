package com.radiologics.filesystems.model.entity;

import com.radiologics.filesystems.model.auto.FilesystemConfig;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.annotation.Nonnull;
import javax.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "filesystem")
public abstract class FilesystemConfigEntity extends AbstractHibernateEntity {
    protected boolean active = false;
    protected boolean archiver = false;

    @Nonnull protected String name;
    protected boolean permitAllProjects = false;

    protected Set<String> permittedProjects = new HashSet<>();
    protected Set<ProjectFilesystemSettingsEntity> archivesForProjects = new HashSet<>();

    public FilesystemConfigEntity() {
        super();
    }

    /**
     * Direct to POJO - no attempt to activate, just use "cached" values
     * @return the POJO
     */
    public abstract FilesystemConfig toPojo();

    @Column(columnDefinition = "boolean default false")
    public boolean isArchiver() {
        return archiver;
    }

    public void setArchiver(boolean archiver) {
        this.archiver = archiver;
    }

    @Column(columnDefinition = "boolean default false")
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @ElementCollection
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

    @OneToMany(mappedBy = "archiverConfig", orphanRemoval = true, cascade = {CascadeType.DETACH})
    public Set<ProjectFilesystemSettingsEntity> getArchivesForProjects() {
        return archivesForProjects;
    }

    public void setArchivesForProjects(Set<ProjectFilesystemSettingsEntity> archivesForProjects) {
        this.archivesForProjects = archivesForProjects;
    }

    public void removeArchivesForProjects() {
        archivesForProjects.clear();
    }

    @Nonnull
    @Column(unique = true, nullable = false)
    public String getName() {
        return name;
    }

    public void setName(@Nonnull String name) {
        this.name = name.replaceAll("[^A-Za-z0-9_\\-]","");
    }

    @Column(columnDefinition = "boolean default false")
    public boolean isPermitAllProjects() {
        return permitAllProjects;
    }

    public void setPermitAllProjects(boolean permitAllProjects) {
        this.permitAllProjects = permitAllProjects;
    }
}
