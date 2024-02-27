// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.aws.s3.model.auto;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.BucketVersioningConfiguration;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.radiologics.filesystems.exceptions.FilesystemServiceException;
import com.radiologics.filesystems.exceptions.InvalidArchiverEntityException;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.exceptions.RemoteApiException;
import com.radiologics.filesystems.model.auto.FilesystemConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.Set;

@Slf4j
@JsonInclude
public class AwsS3Config extends FilesystemConfig {
    @JsonIgnore private static final FilesystemType filesystemType = FilesystemType.AWSS3;
    @JsonIgnore public static final String WRITE_CHECK_FILENAME = ".xnat-writecheck.txt";
    private final String filesystem = filesystemType.toString();

    @Nonnull  private String bucketName;
    @Nullable private String accessKey;
    @Nullable private String secretKey;
    @Nullable private String subdirectory;

    @JsonIgnore private AmazonS3 s3client = null;
    @JsonIgnore private TransferManager s3transfer = null;

    public AwsS3Config() {
        super();
    }

    public AwsS3Config(@Nonnull String bucketName, @Nullable String accessKey, @Nullable String secretKey) {
        this.bucketName = bucketName;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.permitAllProjects = true;
    }

    public AwsS3Config(long id, @Nonnull String bucketName, @Nullable String accessKey, @Nullable String secretKey,
                       @Nullable String subdirectory, String name, boolean archiver, boolean active, boolean permitAllProjects,
                       @Nullable Set<String> permittedProjects) {
        super(id, archiver, active, name, permitAllProjects, permittedProjects);
        this.bucketName = bucketName;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.subdirectory = subdirectory;
    }

    @Override
    @JsonIgnore
    public FilesystemType getFilesystemType() {
        return filesystemType;
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

    @JsonIgnore
    public AmazonS3 getS3client() throws RemoteApiException {
        if (s3client == null) {
            initializeS3Again();
        }
        return s3client;
    }

    @JsonIgnore
    public TransferManager getS3transfer() throws RemoteApiException {
        if (s3transfer == null) {
            initializeS3Again();
        }
        return s3transfer;
    }

    /**
     * Create AWS S3 java client: To be used outside initial creation/update of AWS S3 config thus any exception is treated the same
     * @throws RemoteApiException if unable to instantiate AWS S3 java client
     */
    @JsonIgnore
    public synchronized void initializeS3Again() throws RemoteApiException {
        try {
            initializeS3();
        } catch (InvalidEntityException e) {
            active = false;
            throw new RemoteApiException(e);
        }
    }

    /**
     * Create AWS S3 java client
     * @throws RemoteApiException if unable to instantiate AWS S3 java client due to credentials or connectivity
     * @throws InvalidEntityException refusing to instantiate AWS S3 java client due to invalid config (no such bucket,
     * no versioning)
     */
    @JsonIgnore
    public synchronized void initializeS3() throws RemoteApiException, InvalidEntityException {
        if (s3client != null && s3transfer != null) {
            return;
        }
        active = false;
        try {
            AmazonS3 client;
            if (StringUtils.isNotEmpty(accessKey) && StringUtils.isNotEmpty(secretKey)) {
                AWSCredentials credentials = new BasicAWSCredentials(
                        accessKey,
                        secretKey
                );
                client = AmazonS3ClientBuilder
                        .standard()
                        .withCredentials(new AWSStaticCredentialsProvider(credentials))
                        .withRegion(Regions.DEFAULT_REGION)
                        .withForceGlobalBucketAccessEnabled(true)
                        .build();
            } else {
                client = AmazonS3ClientBuilder
                        .standard()
                        .withRegion(Regions.DEFAULT_REGION)
                        .withForceGlobalBucketAccessEnabled(true)
                        .build();
            }
            validateBucket(client);
            s3client = client;
            s3transfer = TransferManagerBuilder.standard().withS3Client(client).build();
            active = true;
            if (archiver) {
                validateArchiver(s3client);
            }
            log.trace("Initialized s3client for {}", this);
        } catch (RuntimeException e) {
            active = false;
            log.trace("Initialized s3client for {}", this);
            throw new RemoteApiException(e);
        }
    }

    @JsonIgnore
    private void validateBucket(AmazonS3 client) throws InvalidEntityException {
        if (StringUtils.isBlank(bucketName) || !client.doesBucketExistV2(bucketName)) {
            throw new InvalidEntityException("No such bucket: \"" + bucketName + "\"");
        }
    }

    @JsonIgnore
    private void validateArchiver(AmazonS3 client) throws InvalidArchiverEntityException {
        archiver = false;
        BucketVersioningConfiguration conf = client.getBucketVersioningConfiguration(bucketName);
        if (!conf.getStatus().equals(BucketVersioningConfiguration.ENABLED)) {
            throw new InvalidArchiverEntityException("Versioning must be turned on for AWS S3 bucket \"" + bucketName + "\" " +
                    "in order to use it for archival");
        }
        String key = WRITE_CHECK_FILENAME;
        ObjectMetadata om = new ObjectMetadata();
        om.setContentLength(1);
        try {
            client.putObject(bucketName, key, new ByteArrayInputStream(new byte[]{0}), om);
        } catch (Exception e) {
            throw new InvalidArchiverEntityException("You must be able to write to the AWS S3 bucket \"" + bucketName + "\" " +
                    "in order to use it for archival", e);
        }
        try {
            client.deleteObject(bucketName, key);
        } catch (Exception e) {
            // Ignore
        }
        archiver = true;
    }

    @Override
    public String toString() {
        return "AwsS3Config{" +
                "id=" + id +
                ", bucketName='" + bucketName + '\'' +
                ", accessKey='" + accessKey + '\'' +
                ", secretKey='" + secretKey + '\'' +
                ", subdirectory='" + subdirectory + '\'' +
                ", archiver=" + archiver +
                ", active=" + active +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AwsS3Config config = (AwsS3Config) o;
        return id == config.id &&
                name.equals(config.name) &&
                bucketName.equals(config.bucketName) &&
                Objects.equals(accessKey, config.accessKey) &&
                Objects.equals(secretKey, config.secretKey) &&
                Objects.equals(subdirectory, config.subdirectory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, bucketName, accessKey, secretKey, subdirectory);
    }
}