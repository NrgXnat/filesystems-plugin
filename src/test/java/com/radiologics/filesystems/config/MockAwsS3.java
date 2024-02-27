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
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
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
import static org.mockito.Matchers.*;

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
        GetObjectMetadataRequest mockGetMetadataReq = Mockito.mock(GetObjectMetadataRequest.class);
        PowerMockito.whenNew(GetObjectMetadataRequest.class)
                .withArguments(AdditionalMatchers.or(eq(fakeBucketName), eq(altBucketName)),
                        anyString())
                .thenReturn(mockGetMetadataReq);
        ObjectMetadata mockMd = Mockito.mock(ObjectMetadata.class);
        Mockito.when(mockMd.getContentLength()).thenReturn(awsGoodUrlFileSize);
        Mockito.when(mockMd.getContentMD5()).thenReturn(awsGoodUrlFileMd5);
        Mockito.when(mockMd.getLastModified()).thenReturn(new Date());
        Mockito.when(mockS3client.getObjectMetadata(mockGetMetadataReq)).thenReturn(mockMd);

        GetObjectMetadataRequest mockGetMetadataReqExc = Mockito.mock(GetObjectMetadataRequest.class);
        PowerMockito.whenNew(GetObjectMetadataRequest.class)
                .withArguments(anyString(), eq(exceptionThrower))
                .thenReturn(mockGetMetadataReqExc);
        Mockito.when(mockS3client.getObjectMetadata(mockGetMetadataReqExc))
                .thenThrow(new AmazonServiceException(exceptionMsg));

        // get input stream methods
        GetObjectRequest mockGetObjReq = Mockito.mock(GetObjectRequest.class);
        PowerMockito.whenNew(GetObjectRequest.class)
                .withArguments(AdditionalMatchers.or(eq(fakeBucketName), eq(altBucketName)),
                        anyString())
                .thenReturn(mockGetObjReq);
        S3Object s3obj = Mockito.mock(S3Object.class);
        Mockito.doReturn(
                new S3ObjectInputStream(
                        FileUtils.openInputStream(awsGoodUrlFile),
                        new HttpGet()
                )
        ).when(s3obj).getObjectContent();
        Mockito.when(mockS3client.getObject(mockGetObjReq)).thenReturn(s3obj);

        GetObjectRequest mockGetObjReqExc = Mockito.mock(GetObjectRequest.class);
        PowerMockito.whenNew(GetObjectRequest.class)
                .withArguments(anyString(), eq(exceptionThrower))
                .thenReturn(mockGetObjReqExc);
        Mockito.when(mockS3client.getObject(mockGetObjReqExc))
                .thenThrow(new AmazonServiceException(exceptionMsg));

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
        TransferProgress mockProg = PowerMockito.mock(TransferProgress.class);
        AtomicDouble fakeProg = new AtomicDouble(0);
        Mockito.when(mockProg.getPercentTransferred()).thenAnswer((Answer<Double>) inv -> fakeProg.addAndGet(10));
        Download mockDownload = Mockito.mock(Download.class);
        Mockito.when(mockDownload.getProgress()).thenReturn(mockProg);
        Mockito.when(mockS3transfer.download(AdditionalMatchers.or(eq(fakeBucketName), eq(altBucketName)),
                anyString(), any(File.class))).thenAnswer((Answer<Download>) inv -> {
            //String bucket = inv.getArgumentAt(0, String.class);
            //String key = inv.getArgumentAt(1, String.class);
            File file = inv.getArgumentAt(2, File.class);
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

        // Credentials
        BasicAWSCredentials mockCred = Mockito.mock(BasicAWSCredentials.class);
        PowerMockito.whenNew(BasicAWSCredentials.class).withArguments(fakeAccessKey, fakeSecretKey)
                .thenReturn(mockCred);
        AWSStaticCredentialsProvider mockStaticCred = Mockito.mock(AWSStaticCredentialsProvider.class);
        PowerMockito.whenNew(AWSStaticCredentialsProvider.class).withArguments(mockCred)
                .thenReturn(mockStaticCred);

        PowerMockito.mockStatic(AmazonS3ClientBuilder.class);
        AmazonS3ClientBuilder mockAwsBuilder = PowerMockito.mock(AmazonS3ClientBuilder.class);
        PowerMockito.doReturn(mockAwsBuilder).when(AmazonS3ClientBuilder.class, "standard");
        Mockito.when(mockAwsBuilder.withRegion(Regions.DEFAULT_REGION)).thenReturn(mockAwsBuilder);
        Mockito.when(mockAwsBuilder.withForceGlobalBucketAccessEnabled(true)).thenReturn(mockAwsBuilder);
        Mockito.when(mockAwsBuilder.withCredentials(eq(mockStaticCred))).thenReturn(mockAwsBuilder);
        Mockito.when(mockAwsBuilder.build()).thenReturn(mockS3client);

        TransferManagerBuilder mockTransManBuilder = PowerMockito.mock(TransferManagerBuilder.class);
        PowerMockito.mockStatic(TransferManagerBuilder.class);
        PowerMockito.doReturn(mockTransManBuilder).when(TransferManagerBuilder.class, "standard");
        Mockito.when(mockTransManBuilder.withS3Client(mockS3client)).thenReturn(mockTransManBuilder);
        Mockito.when(mockTransManBuilder.build()).thenReturn(mockS3transfer);

        // bad creds (not really needed since line 221 does this for any creds that aren't explicitly good)
        mockCredBad = Mockito.mock(BasicAWSCredentials.class);
        PowerMockito.whenNew(BasicAWSCredentials.class).withArguments(eq(fakeAccessKeyBad), anyString())
                .thenReturn(mockCredBad);
        PowerMockito.whenNew(BasicAWSCredentials.class).withArguments(anyString(), eq(fakeSecretKeyBad))
                .thenReturn(mockCredBad);
        AWSStaticCredentialsProvider mockStaticCredBad = Mockito.mock(AWSStaticCredentialsProvider.class);
        PowerMockito.whenNew(AWSStaticCredentialsProvider.class).withArguments(mockCredBad)
                .thenReturn(mockStaticCredBad);

        AmazonS3 badS3client = Mockito.mock(AmazonS3.class);
        AmazonS3ClientBuilder awsBuilderBad = PowerMockito.mock(AmazonS3ClientBuilder.class);
        //Mockito.when(mockAwsBuilder.withCredentials(AdditionalMatchers.not(Matchers.eq(mockStaticCred))))
        //        .thenReturn(awsBuilderBad);
        Mockito.when(mockAwsBuilder.withCredentials(Matchers.eq(mockStaticCredBad)))
                .thenReturn(awsBuilderBad);
        Mockito.when(awsBuilderBad.withRegion(Regions.DEFAULT_REGION)).thenReturn(awsBuilderBad);
        Mockito.when(awsBuilderBad.withForceGlobalBucketAccessEnabled(true)).thenReturn(awsBuilderBad);
        Mockito.when(awsBuilderBad.build()).thenReturn(badS3client);
        Mockito.when(badS3client.doesBucketExistV2(anyString()))
                .thenThrow(new AmazonS3Exception(badCredsExceptionMsg));
    }

    public static class ObjectMetadataForFileWithContentMatcher extends ArgumentMatcher<ObjectMetadata> {
        @Override
        public boolean matches(Object argument) {
            if (!(argument instanceof ObjectMetadata)) {
                return false;
            }
            return ((ObjectMetadata) argument).getContentLength() > 0L;
        }
    }
}
