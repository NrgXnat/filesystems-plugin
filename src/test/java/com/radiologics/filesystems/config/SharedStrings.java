// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.config;

import com.google.common.collect.ImmutableMap;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SharedStrings {
    public static String testArchiveDir;

    static {
        try {
            testArchiveDir = Paths.get(ClassLoader.getSystemResource("filesystemServicesTest").toURI())
                            .toString().replace("%20", " ");
        } catch (URISyntaxException e) {
            testArchiveDir = "/tmp/archive";
        }
    }

    public static final String writableArchivePath = "/tmp/filesystems";

    public static final String supportedProject = "project";
    public static final String otherProject = "project2";
    public static final List<String> projectArrayList = Arrays.asList(supportedProject, "test1", "test2");

    public static final List<String> allProjectArrayList = Arrays.asList(supportedProject, otherProject, "test1", "test2");


    public static final String fakeAccessKey = "access";
    public static final String fakeAccessKeyBad = "badaccess";
    public static final String fakeSecretKey = "secret";
    public static final String fakeSecretKeyBad = "badsecret";
    public static final String fakeBucketName = "bucket";
    public static final String fakeBucketNameSubdir = "bucketsubdir";
    public static final String fakeSubdir = "subdir";
    public static final String altBucketName = "testbucket";
    public static final String fakeBucketNameBad = "badbucket";
    public static final String fakeBucketNameAlt = "another";
    public static final String fakeBucketNameNoVersion = "noversion";
    public static final String fakeBucketNameReadonly = "readonly";
    public static final String fakeBucketNameNotWritable = "fakeBucketNameNotWritable";
    public static final String exceptionThrowerBucket = "noconnection";

    public static final String etagNull = "etagNull.txt";
    public static final String exceptionThrower = "exceptionThrower.txt";
    public static final String exceptionMsg = "Fake exception";
    public static final String permissionsMsg = "Fake permissions exception";
    public static final String missingFile = "missing.txt";

    public static final String badCredsExceptionMsg = "The AWS Access Key Id you provided does not exist in our records";
    public static final String connectivityExceptionMessage = "Unable to execute HTTP request";

    public static final String awsGoodUrlFileName = "awsGoodUrlFile.txt";
    public static final String awsGoodUrlFilePath = "subdir/" + awsGoodUrlFileName;
    public static final long awsGoodUrlFileSize = 6;
    public static final String awsGoodUrlFileMd5 = "b1946ac92492d2347c6235b4d2611184";
    public static final File awsGoodUrlFile = Paths.get(testArchiveDir, awsGoodUrlFilePath).toFile();
    public static final String testFileName = "subdir/testFile.txt";
    public static final File testFile = Paths.get(testArchiveDir, testFileName).toFile();
    public static final Map<String, FileSystemResource> testFileMap = ImmutableMap.of(testFile.getName(),
            new FileSystemResource(testFile));

    //resource
    public static final String catResFileName = "out.txt";
    public static final String catResFileUrl = "s3://" + altBucketName +
            "/project/arc001/ses1/RESOURCES/DEBUG_OUTPUT/" + catResFileName;

    //exist
    public static final String awsGoodUrl = "s3://" + fakeBucketName + "/" + awsGoodUrlFilePath;
    public static final String awsGoodUrl2 = "s3://" + altBucketName + "/" + awsGoodUrlFilePath;
    public static final String awsGoodUrl3 = "s3://" + altBucketName + "/" + testFileName;
    public static final String awsConflictUrl = "s3://" + fakeBucketName + "/" + catResFileName;
    public static final String etagUrl = "s3://" + fakeBucketName + "/" + etagNull;
    public static final String exceptionThrowerUrl = "s3://" + altBucketName + "/" + exceptionThrower;
    public static final String exceptionThrowerUrl2 = "s3://" + fakeBucketName + "/" + exceptionThrower;

    //dont
    public static final String awsTestFileUrl = "s3://" + fakeBucketName + "/" + testFileName;
    public static final String awsTestFileUrlSubdir = "s3://" + fakeBucketNameSubdir + "/" + fakeSubdir + "/" + testFileName;
    public static final String awsMissingUrl = "s3://" + altBucketName + "/" + missingFile;
    public static final String awsMissingUrl2 = "s3://" + fakeBucketName + "/" + missingFile;
    public static final String nonUrl = "file.txt";
    public static final String badUrl = "http://thisIsNotReal.html";
    public static final String readonlyBucketUrl = "s3://" + fakeBucketNameReadonly + "/" + missingFile;

    // Note: This test uses httpbin.org/user-agent which returns Java version string.
    // Updated for Java 21 (Java/21.0.7) - response size is 34 bytes
    public static final String defaultFsGoodUrl = "http://httpbin.org/user-agent";
    public static final String defaultFsGoodUrlArchiveName = "621ad63a8e2c6e8c98584284265858e3_user-agent";
    public static final long defaultFsGoodUrlResponseSize = 34;
    public static final File defaultFsGetFile = Paths.get(testArchiveDir, "defaultFsGetFile.txt").toFile();
}