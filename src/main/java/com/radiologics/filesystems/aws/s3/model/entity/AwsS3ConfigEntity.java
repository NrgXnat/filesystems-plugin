// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.aws.s3.model.entity;

import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.exceptions.RemoteApiException;
import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import java.util.Objects;

@Entity
@DiscriminatorValue("awss3")
public class AwsS3ConfigEntity extends FilesystemConfigEntity {
    @Nonnull  private String bucketName;
    @Nullable private String accessKey;
    @Nullable private String secretKey;
    @Nullable private String subdirectory;

    public AwsS3ConfigEntity() {
        super();
    }

    @Nonnull
    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(@Nonnull String bucketName) {
        this.bucketName = bucketName;
    }

    @Nullable
    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(@Nullable String accessKey) {
        this.accessKey = accessKey;
    }

    @Nullable
    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(@Nullable String secretKey) {
        this.secretKey = secretKey;
    }

    @Nullable
    public String getSubdirectory() {
        return subdirectory;
    }

    public void setSubdirectory(@Nullable String subdirectory) {
        this.subdirectory = subdirectory;
    }

    @Override
    public String toString() {
        return "AwsS3ConfigEntity{" +
                "bucketName='" + bucketName + '\'' +
                ", accessKey='" + accessKey + '\'' +
                ", secretKey='" + secretKey + '\'' +
                ", subdirectory='" + subdirectory + '\'' +
                ", active=" + active +
                ", archiver=" + archiver +
                ", name='" + name + '\'' +
                ", permitAllProjects=" + permitAllProjects +
                '}';
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AwsS3Config toPojo() {
        return new AwsS3Config(getId(), bucketName, accessKey, secretKey, subdirectory, name,
                archiver, active, permitAllProjects, getPermittedProjects());
    }

    /**
     * Attempt to activate, throw exception if unable to do so
     * @return the POJO
     * @throws InvalidEntityException if unable to activate bc entity is invalid (invalid bucket, no versioning, etc)
     * @throws RemoteApiException if unable to activate bc of remote API (invalid credentials or inaccessible)
     */
    public AwsS3Config toActivatedPojo() throws InvalidEntityException, RemoteApiException {
        AwsS3Config config = toPojo();
        config.initializeS3();
        return config;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final AwsS3ConfigEntity that = (AwsS3ConfigEntity) o;
        return Objects.equals(this.bucketName, that.bucketName); //bucket name must be unique
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketName); //bucket name must be unique
    }
}
