// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.collect.ImmutableMap;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public class SharedStrings {
    public static String testArchiveDir;

    static {
        try {
            // Copy the source resources/filesystemServicesTest/ to a writable temp dir.
            // We need to rewrite DATA_catalog.xml so its URL points at our WireMock
            // stub instead of httpbin.org, and we don't want to mutate the source tree.
            java.nio.file.Path srcDir = Paths.get(ClassLoader.getSystemResource("filesystemServicesTest").toURI());
            java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("filesystemServicesTest-");
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(srcDir)) {
                walk.forEach(src -> {
                    try {
                        java.nio.file.Path dst = tmpDir.resolve(srcDir.relativize(src));
                        if (java.nio.file.Files.isDirectory(src)) {
                            java.nio.file.Files.createDirectories(dst);
                        } else {
                            java.nio.file.Files.copy(src, dst,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            testArchiveDir = tmpDir.toString();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(tmpDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {}
            }));
        } catch (URISyntaxException | IOException e) {
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

    // Previously hit httpbin.org/user-agent — that response varies with the JVM's
    // default User-Agent (which embeds the Java version), so the byte-exact size
    // assertions broke whenever the runner's Temurin patch version drifted from
    // the developer's local JDK. We now serve the same canned bytes from a local
    // WireMock instance — completely network-independent. See defaultFsGetFile.txt
    // for the response body. Size + archive filename derive from the bytes / URL,
    // so updating the canned file does not require touching constants.
    public static final File defaultFsGetFile = Paths.get(testArchiveDir, "defaultFsGetFile.txt").toFile();
    private static final WireMockServer HTTPBIN_STUB;
    public static final String defaultFsGoodUrl;
    public static final String defaultFsGoodUrlArchiveName;
    public static final long defaultFsGoodUrlResponseSize;

    static {
        final byte[] cannedResponse;
        try {
            cannedResponse = Files.readAllBytes(defaultFsGetFile.toPath());
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        defaultFsGoodUrlResponseSize = cannedResponse.length;

        HTTPBIN_STUB = new WireMockServer(options().dynamicPort());
        HTTPBIN_STUB.start();
        // any() matches both GET (used by pullFile / getInputStream / getMetadata body fetch)
        // and HEAD (used by service.getUrlHeaders to populate Content-Length).
        HTTPBIN_STUB.stubFor(any(urlPathEqualTo("/user-agent")).willReturn(
                aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Length", String.valueOf(cannedResponse.length))
                        .withBody(cannedResponse)));
        Runtime.getRuntime().addShutdownHook(new Thread(HTTPBIN_STUB::stop));

        defaultFsGoodUrl = "http://localhost:" + HTTPBIN_STUB.port() + "/user-agent";

        try {
            byte[] md5 = MessageDigest.getInstance("MD5")
                    .digest(defaultFsGoodUrl.getBytes(StandardCharsets.UTF_8));
            defaultFsGoodUrlArchiveName = HexFormat.of().formatHex(md5) + "_user-agent";
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }

        // DATA_catalog.xml is read by testInitiatePullResourceFilesAndPollPullResource;
        // it embeds the URL + its MD5 + the cachePath. Patch the copy in testArchiveDir.
        java.nio.file.Path catalog = Paths.get(testArchiveDir, "DATA_catalog.xml");
        try {
            String xml = new String(Files.readAllBytes(catalog), StandardCharsets.UTF_8);
            xml = xml.replace("http://httpbin.org/user-agent", defaultFsGoodUrl)
                    .replace("621ad63a8e2c6e8c98584284265858e3_user-agent", defaultFsGoodUrlArchiveName);
            Files.write(catalog, xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}