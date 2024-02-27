// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.config;

import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.radiologics.filesystems.config.SharedStrings.*;

@Configuration
public class AwsS3MockTestConfig {

    @Bean
    public AwsS3Config awsArchiverConfig() {
        return new AwsS3Config(0L, fakeBucketName, fakeAccessKey, fakeSecretKey, null,
                "default",true, false, false, null);
    }

    @Bean
    public AwsS3Config awsAltConfig() {
        return new AwsS3Config(0L, altBucketName, fakeAccessKey, fakeSecretKey, null,
                "alternative",false, false, true, null);
    }

    @Bean
    public AwsS3Config awsSubdirConfig() {
        return new AwsS3Config(0L, fakeBucketNameSubdir, fakeAccessKey, fakeSecretKey, fakeSubdir,
                "subdir",true, false, false, null);
    }

    @Bean
    public AwsS3Config awsAltArchiverConfig() {
        return new AwsS3Config(0L, altBucketName, fakeAccessKey, fakeSecretKey, null,
                "altarchiver",true, false, false, null);
    }

    @Bean
    public AwsS3Config awsReadonlyConfig() {
        return new AwsS3Config(0L, fakeBucketNameReadonly, fakeAccessKey, fakeSecretKey, null,
                "readonly",false, false, false, null);
    }

    @Bean
    public AwsS3Config awsReadonlyPermitAllConfig() {
        return new AwsS3Config(0L, fakeBucketNameReadonly, fakeAccessKey, fakeSecretKey, null,
                "readonlyPermitAll",false, false, true, null);
    }

    @Bean
    public AwsS3Config noVersionAwsConfig() {
        return new AwsS3Config(0L, fakeBucketNameNoVersion, fakeAccessKey, fakeSecretKey, null,
                "noversion", false, false, false, null);
    }

    @Bean
    public AwsS3Config noVersionArchiverAwsConfig() {
        return new AwsS3Config(0L, fakeBucketNameNoVersion, fakeAccessKey, fakeSecretKey, null,
                "noversionarchive", true, false, false, null);
    }

    @Bean
    public AwsS3Config notWritableArchiverAwsConfig() {
        return new AwsS3Config(0L, fakeBucketNameNotWritable, fakeAccessKey, fakeSecretKey, null,
                "notwritablearchive", true, false, false, null);
    }

    @Bean
    public AwsS3Config invalidAwsConfig() {
        return new AwsS3Config(0L, fakeBucketNameBad, fakeAccessKeyBad, fakeSecretKeyBad, null,
                "invalid", true, false, false, null);
    }

    @Bean
    public AwsS3Config invalidCredsAwsConfig() {
        return new AwsS3Config(0L, fakeBucketNameAlt, fakeAccessKeyBad, fakeSecretKeyBad, null,
                "invalidCreds", false, false, false, null);
    }
}
