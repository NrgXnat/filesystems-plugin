// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.config;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.transfer.*;
import org.apache.commons.io.FileUtils;
import org.apache.http.client.methods.HttpGet;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import reactor.jarjar.jsr166e.extra.AtomicDouble;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config.WRITE_CHECK_FILENAME;
import static com.radiologics.filesystems.config.SharedStrings.*;
import static org.mockito.ArgumentMatchers.*;

public class MockAwsS3 {
    public AmazonS3 mockS3client;
    public TransferManager mockS3transfer;
    public BasicAWSCredentials mockCredBad;

    public MockAwsS3() throws Exception {
        // Mock AWS S3 client
        mockS3client = Mockito.mock(AmazonS3.class);

        // bucket exists
        Mockito.when(mockS3client.doesBucketExistV2(fakeBucketName)).thenReturn(true);
        Mockito.when(mockS3client.doesBucketExistV2(fakeBucketNameSubdir)).thenReturn(true);
        Mockito.when(mockS3client.doesBucketExistV2(altBucketName)).thenReturn(true);
        Mockito.when(mockS3client.doesBucketExistV2(fakeBucketNameNoVersion)).thenReturn(true);
        Mockito.when(mockS3client.doesBucketExistV2(fakeBucketNameReadonly)).thenReturn(true);
        Mockito.when(mockS3client.doesBucketExistV2(fakeBucketNameBad)).thenReturn(false);
        Mockito.when(mockS3client.doesBucketExistV2(fakeBucketNameAlt)).thenReturn(true);
        Mockito.when(mockS3client.doesBucketExistV2(fakeBucketNameNotWritable)).thenReturn(true);

        Mockito.when(mockS3client.doesBucketExistV2(exceptionThrowerBucket))
                .thenThrow(new SdkClientException(connectivityExceptionMessage));

        // bucket versioning
        BucketVersioningConfiguration noversionconf = Mockito.mock(BucketVersioningConfiguration.class);
        Mockito.when(noversionconf.getStatus()).thenReturn(BucketVersioningConfiguration.OFF);
        Mockito.when(mockS3client.getBucketVersioningConfiguration(anyString()))
                .thenReturn(noversionconf); //default

        BucketVersioningConfiguration conf = Mockito.mock(BucketVersioningConfiguration.class);
        Mockito.when(conf.getStatus()).thenReturn(BucketVersioningConfiguration.ENABLED);
        Mockito.when(mockS3client.getBucketVersioningConfiguration(fakeBucketName)).thenReturn(conf);
        Mockito.when(mockS3client.getBucketVersioningConfiguration(fakeBucketNameSubdir)).thenReturn(conf);
        Mockito.when(mockS3client.getBucketVersioningConfiguration(altBucketName)).thenReturn(conf);
        Mockito.when(mockS3client.getBucketVersioningConfiguration(fakeBucketNameNotWritable)).thenReturn(conf);

        // object exists
        Mockito.when(mockS3client.doesObjectExist(eq(fakeBucketNameBad), anyString())).thenReturn(false);
        Mockito.when(mockS3client.doesObjectExist(eq(fakeBucketName), anyString())).thenReturn(false);
        Mockito.when(mockS3client.doesObjectExist(eq(fakeBucketNameSubdir), anyString())).thenReturn(true);
        Mockito.when(mockS3client.doesObjectExist(eq(fakeBucketNameReadonly), anyString())).thenReturn(true);
        Mockito.when(mockS3client.doesObjectExist(anyString(), eq(missingFile))).thenReturn(false);
        Mockito.when(mockS3client.doesObjectExist(eq(fakeBucketName),
                AdditionalMatchers.or(
                        eq(awsGoodUrlFilePath),
                        eq(etagNull)
                )
        )).thenReturn(true);
        Mockito.when(mockS3client.doesObjectExist(fakeBucketName, exceptionThrower)).thenReturn(true);
        Mockito.when(mockS3client.doesObjectExist(eq(altBucketName),
                AdditionalMatchers.not(eq(missingFile))))
                .thenReturn(true);

        // put methods (transfer manager)
        mockS3transfer = Mockito.mock(TransferManager.class);
        Upload mockUpload = Mockito.mock(Upload.class);
        Mockito.when(mockUpload.getState()).thenReturn(Transfer.TransferState.Completed);
        Upload mockUploadFailed = Mockito.mock(Upload.class);
        Mockito.when(mockUploadFailed.getState()).thenReturn(Transfer.TransferState.Failed);
        Mockito.when(mockS3transfer.upload(AdditionalMatchers.not(eq(fakeBucketNameBad)),
                anyString(), any(InputStream.class), argThat(new ObjectMetadataForFileWithContentMatcher())))
                .thenReturn(mockUpload);
        Mockito.when(mockS3transfer.upload(AdditionalMatchers.not(eq(fakeBucketNameBad)),
                eq(etagNull), any(InputStream.class), argThat(new ObjectMetadataForFileWithContentMatcher())))
                .thenReturn(mockUploadFailed);
        Mockito.when(mockS3transfer.upload(AdditionalMatchers.not(eq(fakeBucketNameBad)),
                eq(exceptionThrower), any(InputStream.class), argThat(new ObjectMetadataForFileWithContentMatcher())))
                .thenThrow(new SdkClientException(exceptionMsg));
        Mockito.when(mockS3transfer.upload(eq(fakeBucketNameBad),
                anyString(), any(InputStream.class), argThat(new ObjectMetadataForFileWithContentMatcher())))
                .thenThrow(new AmazonServiceException(permissionsMsg));
        Mockito.when(mockS3transfer.upload(eq(fakeBucketNameReadonly),
                anyString(), any(InputStream.class), argThat(new ObjectMetadataForFileWithContentMatcher())))
                .thenThrow(new AmazonServiceException(permissionsMsg));

        // put methods (writable check)
        PutObjectResult mockputObj = Mockito.mock(PutObjectResult.class);
        Mockito.when(mockputObj.getETag()).thenReturn("etag");
        Mockito.when(mockS3client.putObject(anyString(),
                eq(WRITE_CHECK_FILENAME), any(InputStream.class), any(ObjectMetadata.class)))
                .thenReturn(mockputObj);
        Mockito.when(mockS3client.putObject(eq(fakeBucketNameNotWritable),
                eq(WRITE_CHECK_FILENAME), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new AmazonServiceException(permissionsMsg));

        // delete methods
        Mockito.doThrow(new AmazonServiceException(exceptionMsg)).when(mockS3client)
                .deleteObject(anyString(), eq(exceptionThrower));

        // Throw unexpected exception if unsupported urls get through ? it's a void method so not much else to do
        Mockito.doThrow(new RuntimeException(exceptionMsg)).when(mockS3client)
                .deleteObject(eq(fakeBucketNameBad), anyString());
        Mockito.doThrow(new RuntimeException(exceptionMsg)).when(mockS3client)
                .deleteObject(anyString(), eq(missingFile));

        // get metadata methods
        // Note: With Mockito 5, we don't mock constructors. Instead, we match any GetObjectMetadataRequest.
        ObjectMetadata mockMd = Mockito.mock(ObjectMetadata.class);
        Mockito.when(mockMd.getContentLength()).thenReturn(awsGoodUrlFileSize);
        Mockito.when(mockMd.getContentMD5()).thenReturn(awsGoodUrlFileMd5);
        Mockito.when(mockMd.getLastModified()).thenReturn(new Date());

        // Exception cases first (more specific matchers)
        Mockito.when(mockS3client.getObjectMetadata(anyString(), eq(exceptionThrower)))
                .thenThrow(new AmazonServiceException(exceptionMsg));

        // Default cases
        Mockito.when(mockS3client.getObjectMetadata(any(GetObjectMetadataRequest.class))).thenReturn(mockMd);
        // Support two-argument overload
        Mockito.when(mockS3client.getObjectMetadata(anyString(), anyString())).thenReturn(mockMd);

        // get input stream methods
        // Note: With Mockito 5, we don't mock constructors. Instead, we match any GetObjectRequest.
        S3Object s3obj = Mockito.mock(S3Object.class);
        Mockito.doReturn(
                new S3ObjectInputStream(
                        FileUtils.openInputStream(awsGoodUrlFile),
                        new HttpGet()
                )
        ).when(s3obj).getObjectContent();

        // Exception cases first (more specific matchers)
        Mockito.when(mockS3client.getObject(anyString(), eq(exceptionThrower)))
                .thenThrow(new AmazonServiceException(exceptionMsg));

        // Default cases
        Mockito.when(mockS3client.getObject(any(GetObjectRequest.class))).thenReturn(s3obj);
        // Support two-argument overload
        Mockito.when(mockS3client.getObject(anyString(), anyString())).thenReturn(s3obj);

        // list
        ListObjectsV2Result mockResult = Mockito.mock(ListObjectsV2Result.class);
        List<S3ObjectSummary> s3summs = new ArrayList<>();
        for (String key : Arrays.asList(awsGoodUrlFilePath, etagNull, testFileName, "subdir/deeper/file.txt")) {
            S3ObjectSummary sum = new S3ObjectSummary();
            sum.setKey(key);
            s3summs.add(sum);
        }
        Mockito.when(mockResult.isTruncated()).thenReturn(false);
        Mockito.when(mockResult.getObjectSummaries()).thenReturn(s3summs);
        Mockito.when(mockS3client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(mockResult);

        // pull / download methods (transfer manager)
        TransferProgress mockProg = Mockito.mock(TransferProgress.class);
        AtomicDouble fakeProg = new AtomicDouble(0);
        Mockito.when(mockProg.getPercentTransferred()).thenAnswer((Answer<Double>) inv -> fakeProg.addAndGet(10));
        Download mockDownload = Mockito.mock(Download.class);
        Mockito.when(mockDownload.getProgress()).thenReturn(mockProg);
        Mockito.when(mockS3transfer.download(AdditionalMatchers.or(eq(fakeBucketName), eq(altBucketName)),
                anyString(), any(File.class))).thenAnswer((Answer<Download>) inv -> {
            //String bucket = inv.getArgument(0, String.class);
            //String key = inv.getArgument(1, String.class);
            File file = inv.getArgument(2, File.class);
            file.getParentFile().mkdirs();
            Files.copy(awsGoodUrlFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return mockDownload;
        });
        Mockito.when(mockS3transfer.download(eq(altBucketName), eq(awsGoodUrlFilePath), any(File.class)))
                .thenReturn(mockDownload); // don't do the copy so we can get an exception
        Mockito.when(mockS3transfer.download(AdditionalMatchers.or(eq(fakeBucketName), eq(altBucketName)),
                anyString(), eq(awsGoodUrlFile))).thenReturn(mockDownload); // don't do the copy, rely on existing file
        Mockito.when(mockS3transfer.download(anyString(), eq(exceptionThrower), any(File.class)))
                .thenThrow(new AmazonServiceException(exceptionMsg));

        // Note: With Mockito 5, we don't mock static builder methods or constructors.
        // The test configuration should inject pre-built AmazonS3 and TransferManager mocks directly.
    }

    /**
     * Create a MockedConstruction that intercepts AmazonS3Client constructor calls
     * and returns the pre-configured mock from this MockAwsS3 instance.
     * This allows AmazonS3ClientBuilder.build() to return our mock instead of creating a real client.
     *
     * Usage:
     * <pre>
     * MockedConstruction&lt;AmazonS3Client&gt; mockedS3Client = MockAwsS3.mockS3ClientConstruction(mockAwsS3);
     * try {
     *     // Any code that calls AmazonS3ClientBuilder.build() will get mockAwsS3.mockS3client
     * } finally {
     *     mockedS3Client.close();
     * }
     * </pre>
     */
    public static MockedConstruction<com.amazonaws.services.s3.AmazonS3Client> mockS3ClientConstruction(MockAwsS3 mockAwsS3) {
        return Mockito.mockConstruction(com.amazonaws.services.s3.AmazonS3Client.class,
            (mock, context) -> {
                // Configure the mock to behave like mockAwsS3.mockS3client
                // We need to copy all the stubbing from mockS3client to this new mock
                Mockito.when(mock.doesBucketExistV2(anyString())).thenAnswer(inv ->
                    mockAwsS3.mockS3client.doesBucketExistV2(inv.getArgument(0, String.class)));
                Mockito.when(mock.getBucketVersioningConfiguration(anyString())).thenAnswer(inv ->
                    mockAwsS3.mockS3client.getBucketVersioningConfiguration(inv.getArgument(0, String.class)));
                Mockito.when(mock.doesObjectExist(anyString(), anyString())).thenAnswer(inv ->
                    mockAwsS3.mockS3client.doesObjectExist(inv.getArgument(0, String.class), inv.getArgument(1, String.class)));
                Mockito.when(mock.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class))).thenAnswer(inv ->
                    mockAwsS3.mockS3client.putObject(inv.getArgument(0, String.class), inv.getArgument(1, String.class),
                        inv.getArgument(2, InputStream.class), inv.getArgument(3, ObjectMetadata.class)));
                Mockito.doAnswer(inv -> {
                    mockAwsS3.mockS3client.deleteObject(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
                    return null;
                }).when(mock).deleteObject(anyString(), anyString());

                // getObjectMetadata - both overloads
                Mockito.when(mock.getObjectMetadata(any(GetObjectMetadataRequest.class))).thenAnswer(inv ->
                    mockAwsS3.mockS3client.getObjectMetadata(inv.getArgument(0, GetObjectMetadataRequest.class)));
                Mockito.when(mock.getObjectMetadata(anyString(), anyString())).thenAnswer(inv ->
                    mockAwsS3.mockS3client.getObjectMetadata(inv.getArgument(0, String.class), inv.getArgument(1, String.class)));

                // getObject - both overloads
                Mockito.when(mock.getObject(any(GetObjectRequest.class))).thenAnswer(inv ->
                    mockAwsS3.mockS3client.getObject(inv.getArgument(0, GetObjectRequest.class)));
                Mockito.when(mock.getObject(anyString(), anyString())).thenAnswer(inv ->
                    mockAwsS3.mockS3client.getObject(inv.getArgument(0, String.class), inv.getArgument(1, String.class)));
                Mockito.when(mock.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(inv ->
                    mockAwsS3.mockS3client.listObjectsV2(inv.getArgument(0, ListObjectsV2Request.class)));
            });
    }

    /**
     * Create a MockedConstruction that intercepts TransferManager constructor calls
     * and returns the pre-configured mock from this MockAwsS3 instance.
     * This allows TransferManagerBuilder.build() to return our mock instead of creating a real transfer manager.
     *
     * Usage:
     * <pre>
     * MockedConstruction&lt;TransferManager&gt; mockedTransferManager = MockAwsS3.mockTransferManagerConstruction(mockAwsS3);
     * try {
     *     // Any code that calls TransferManagerBuilder.build() will get mockAwsS3.mockS3transfer
     * } finally {
     *     mockedTransferManager.close();
     * }
     * </pre>
     */
    public static MockedConstruction<TransferManager> mockTransferManagerConstruction(MockAwsS3 mockAwsS3) {
        return Mockito.mockConstruction(TransferManager.class,
            (mock, context) -> {
                // Configure the mock to behave like mockAwsS3.mockS3transfer
                Mockito.when(mock.upload(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class))).thenAnswer(inv ->
                    mockAwsS3.mockS3transfer.upload(inv.getArgument(0, String.class), inv.getArgument(1, String.class),
                        inv.getArgument(2, InputStream.class), inv.getArgument(3, ObjectMetadata.class)));
                Mockito.when(mock.download(anyString(), anyString(), any(File.class))).thenAnswer(inv ->
                    mockAwsS3.mockS3transfer.download(inv.getArgument(0, String.class), inv.getArgument(1, String.class),
                        inv.getArgument(2, File.class)));
            });
    }

    public static class ObjectMetadataForFileWithContentMatcher implements ArgumentMatcher<ObjectMetadata> {
        @Override
        public boolean matches(ObjectMetadata argument) {
            if (argument == null) {
                return false;
            }
            return argument.getContentLength() > 0L;
        }
    }
}
