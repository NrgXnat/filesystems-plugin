// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.hibernate;

import com.amazonaws.SdkClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.TransferProgress;
import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.aws.s3.model.entity.AwsS3ConfigEntity;
import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityService;
import com.radiologics.filesystems.aws.s3.services.AwsS3FilesystemService;
import com.radiologics.filesystems.config.AwsS3MockTestConfig;
import com.radiologics.filesystems.config.MockAwsS3;
import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.exceptions.RemoteApiException;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import com.radiologics.filesystems.services.ProjectFilesystemSettingsEntityService;
import com.radiologics.filesystems.utils.TestingUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xft.security.UserI;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.InputStream;
import java.util.*;

import static com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config.WRITE_CHECK_FILENAME;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static com.radiologics.filesystems.config.SharedStrings.*;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*", "org.xml.sax.*"})
@PrepareForTest({AmazonS3ClientBuilder.class, TransferManagerBuilder.class, BasicAWSCredentials.class,
        AWSStaticCredentialsProvider.class, AwsS3FilesystemService.class, TransferProgress.class, AwsS3Config.class,
        MockAwsS3.class, AutoXnatProjectdata.class
})
@ContextConfiguration(classes = {TestConfig.class, AwsS3MockTestConfig.class})
public class AwsS3ConfigEntityServiceTest {
    @Autowired private AwsS3ConfigEntityService awsS3ConfigEntityService;
    @Autowired private AwsS3Config awsArchiverConfig;
    @Autowired private AwsS3Config awsAltConfig;
    @Autowired private AwsS3Config awsSubdirConfig;
    @Autowired private AwsS3Config awsReadonlyConfig;
    @Autowired private AwsS3Config awsReadonlyPermitAllConfig;
    @Autowired private AwsS3Config noVersionAwsConfig;
    @Autowired private AwsS3Config noVersionArchiverAwsConfig;
    @Autowired private AwsS3Config notWritableArchiverAwsConfig;
    @Autowired private AwsS3Config invalidAwsConfig;
    @Autowired private AwsS3Config invalidCredsAwsConfig;
    @Autowired private ProjectFilesystemSettingsEntityService projectFilesystemSettingsEntityService;

    @Mock private AwsS3FilesystemService awsS3FilesystemService;

    private MockAwsS3 mockAwsS3;

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Before
    public void setup() throws Exception {
        mockAwsS3 = new MockAwsS3(); //need to reset each time

        PowerMockito.mockStatic(AutoXnatProjectdata.class);
        ArrayList<XnatProjectdata> projectList = new ArrayList<>();
        for (String project : allProjectArrayList) {
            XnatProjectdata xnatProjectdata = Mockito.mock(XnatProjectdata.class);
            Mockito.when(xnatProjectdata.getId()).thenReturn(project);
            projectList.add(xnatProjectdata);
        }
        PowerMockito.doReturn(projectList).when(AutoXnatProjectdata.class,
                "getAllXnatProjectdatas", any(UserI.class), anyBoolean());
    }

    @Test
    @DirtiesContext
    public void testCreateFromValidConfig1() throws Exception {
        testCreateEntity(awsArchiverConfig, true);
    }

    @Test
    @DirtiesContext
    public void testCreateFromValidConfig2() throws Exception {
        testCreateEntity(awsSubdirConfig, true);
    }

    @Test
    @DirtiesContext
    public void testCreateFromValidConfig3() throws Exception {
        testCreateEntity(noVersionAwsConfig, false);
    }

    @Test
    @DirtiesContext
    public void testCreateFromValidConfig4() throws Exception {
        testCreateEntity(awsReadonlyConfig, false);
    }

    @Test
    @DirtiesContext
    public void testCreateFromInvalidConfigBadBucket() throws Exception {
        AwsS3Config badBucket = new AwsS3Config(0L, fakeBucketNameBad, fakeAccessKey, fakeSecretKey,
                null, "invalid", true, false, false, null);
        TestingUtils.expectException(InvalidEntityException.class, "No such bucket: \"" + badBucket.getBucketName() + "\"", () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(badBucket, true,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.findByUniqueProperty("bucketName", fakeBucketNameBad), nullValue());
    }

    @Test
    @DirtiesContext
    public void testCreateFromNotVersionedConfigArchiver() throws Exception {
        String name = noVersionArchiverAwsConfig.getBucketName();
        String msg = "Versioning must be turned on for AWS S3 bucket \"" +
                name + "\" in order to use it for archival";
        TestingUtils.expectException(InvalidEntityException.class, msg, () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(noVersionArchiverAwsConfig, true,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.findByUniqueProperty("bucketName", name), nullValue());
    }

    @Test
    @DirtiesContext
    public void testCreateFromNotWritableConfigArchiver() throws Exception {
        exceptionRule.expect(InvalidEntityException.class);
        exceptionRule.expectMessage("You must be able to write to the AWS S3 bucket \"" +
                notWritableArchiverAwsConfig.getBucketName() + "\" in order to use it for archival");
        awsS3ConfigEntityService.createOrUpdateFromPojo(notWritableArchiverAwsConfig, true,
                awsS3FilesystemService);
    }

    @Test
    @DirtiesContext
    public void testCreateFromInvalidConfigConnectivity() throws Exception {
        AwsS3Config connectivity = new AwsS3Config(0L, exceptionThrowerBucket, fakeAccessKey, fakeSecretKey,
                null, "invalid", false, false, false, null);
        TestingUtils.expectException(InvalidEntityException.class, connectivityExceptionMessage, () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(connectivity, true,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.findByUniqueProperty("bucketName", exceptionThrowerBucket), nullValue());
    }

    @Test
    @DirtiesContext
    public void testCreateFromInvalidConfigBadCreds() throws Exception {
        TestingUtils.expectException(InvalidEntityException.class, badCredsExceptionMsg, () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(invalidCredsAwsConfig, true,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.findByUniqueProperty("bucketName", invalidCredsAwsConfig.getBucketName()),
                nullValue());
    }

    @Test
    @DirtiesContext
    public void testCreateFromDupeConfig() throws Exception {
        testCreateEntity(awsArchiverConfig, true);
        TestingUtils.expectException(InvalidEntityException.class, "A config for bucket " + awsArchiverConfig.getBucketName() +
                " already exists", () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(awsArchiverConfig, true,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.findByUniqueProperty("bucketName", invalidCredsAwsConfig.getBucketName()),
                nullValue());
    }

    @Test
    @DirtiesContext
    public void testCreateNewArchiver() throws Exception {
        long id1 = testCreateEntity(awsArchiverConfig, true);
        long id2 = testCreateEntity(awsSubdirConfig, true);
        assertThat(awsS3ConfigEntityService.get(id1).isArchiver(), is(true));
        assertThat(awsS3ConfigEntityService.get(id2).isArchiver(), is(true));
    }

    @Test
    @DirtiesContext
    public void testUpdate() throws Exception {
        long id = testCreateEntity(awsArchiverConfig, true);
        testUpdateEntity(id, awsSubdirConfig, true);
    }

    @Test
    @DirtiesContext
    public void testUpdateArchiverOff() throws Exception {
        long id = testCreateEntity(awsArchiverConfig, true);
        testUpdateEntity(id, awsReadonlyConfig, false);
        assertThat(awsS3ConfigEntityService.get(id).isArchiver(), is(false));
    }

    @Test
    @DirtiesContext
    public void testUpdateArchiverOn() throws Exception {
        long id = testCreateEntity(awsReadonlyConfig, false);
        testUpdateEntity(id, awsArchiverConfig, true);
        assertThat(awsS3ConfigEntityService.get(id).isArchiver(), is(true));
    }

    @Test
    @DirtiesContext
    public void testUpdateBadCreds1() throws Exception {
        AwsS3Config configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(awsReadonlyConfig,
                true, awsS3FilesystemService);
        configWithS3.setAccessKey(fakeAccessKeyBad);
        TestingUtils.expectException(InvalidEntityException.class, badCredsExceptionMsg, () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(configWithS3, false,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.get(configWithS3.getId()).getAccessKey(),
                is(awsReadonlyConfig.getAccessKey()));
    }

    @Test
    @DirtiesContext
    public void testUpdateBadCreds2() throws Exception {
        AwsS3Config configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(awsReadonlyConfig,
                true, awsS3FilesystemService);
        configWithS3.setSecretKey(fakeSecretKeyBad);
        TestingUtils.expectException(InvalidEntityException.class, badCredsExceptionMsg, () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(configWithS3, false,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.get(configWithS3.getId()).getSecretKey(),
                is(awsReadonlyConfig.getSecretKey()));
    }

    @Test
    @DirtiesContext
    public void testUpdateConnectivityIssue() throws Exception {
        AwsS3Config configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(awsReadonlyConfig,
                true, awsS3FilesystemService);
        Mockito.when(mockAwsS3.mockS3client.doesBucketExistV2(configWithS3.getBucketName()))
                .thenThrow(new SdkClientException(connectivityExceptionMessage));
        String newName = "newname";
        configWithS3.setName(newName);
        AwsS3Config configWithS3New = awsS3ConfigEntityService.createOrUpdateFromPojo(configWithS3, false,
                awsS3FilesystemService);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(configWithS3New.getId());
        assertThat(entity.getName(), is(newName));
        assertThat(entity.isActive(), is(false));
        assertThat(configWithS3New.isActive(), is(false));
    }

    @Test
    @DirtiesContext
    public void testUpdateBadBucket() throws Exception {
        AwsS3Config configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(awsReadonlyConfig,
                true, awsS3FilesystemService);
        configWithS3.setBucketName(fakeBucketNameBad);
        TestingUtils.expectException(InvalidEntityException.class, "No such bucket: \"" + fakeBucketNameBad + "\"", () -> {
            awsS3ConfigEntityService.createOrUpdateFromPojo(configWithS3, false,
                    awsS3FilesystemService);
            return null;
        });
        assertThat(awsS3ConfigEntityService.get(configWithS3.getId()).getBucketName(),
                is(awsReadonlyConfig.getBucketName()));
    }

    @Test
    @DirtiesContext
    public void testUpdateNotVersionedConfigArchiver() throws Exception {
        AwsS3Config configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(awsArchiverConfig,
                true, awsS3FilesystemService);
        configWithS3.setBucketName(fakeBucketNameNoVersion);
        exceptionRule.expect(InvalidEntityException.class);
        exceptionRule.expectMessage("Versioning must be turned on for AWS S3 bucket \"" +
                fakeBucketNameNoVersion + "\" in order to use it for archival");
        awsS3ConfigEntityService.createOrUpdateFromPojo(noVersionArchiverAwsConfig, true,
                awsS3FilesystemService);
        assertThat(awsS3ConfigEntityService.get(configWithS3.getId()).getBucketName(),
                is(awsArchiverConfig.getBucketName()));
    }

    @Test
    @DirtiesContext
    public void testUpdateNotWritableConfigArchiver() throws Exception {
        AwsS3Config configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(awsArchiverConfig,
                true, awsS3FilesystemService);
        configWithS3.setBucketName(fakeBucketNameNotWritable);
        exceptionRule.expect(InvalidEntityException.class);
        exceptionRule.expectMessage("You must be able to write to the AWS S3 bucket \"" +
                fakeBucketNameNotWritable + "\" in order to use it for archival");
        awsS3ConfigEntityService.createOrUpdateFromPojo(notWritableArchiverAwsConfig, true,
                awsS3FilesystemService);
        assertThat(awsS3ConfigEntityService.get(configWithS3.getId()).getBucketName(),
                is(awsArchiverConfig.getBucketName()));
    }

    @Test
    @DirtiesContext
    public void testUpdatePermittedProjects() throws Exception {
        long id = testCreateEntity(awsArchiverConfig, true);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(0));

        String p1 = "p1";
        String p2 = "p2";
        awsS3ConfigEntityService.updatePermittedProjects(id, true,
                Arrays.asList(p1, p2), awsS3FilesystemService);
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(2));
        assertThat(entity.getPermittedProjects(), containsInAnyOrder(p1, p2));
        awsS3ConfigEntityService.updatePermittedProjects(id, false,
                Arrays.asList(p1), awsS3FilesystemService);

        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(1));
        assertThat(entity.getPermittedProjects(), hasItem(p2));
        Mockito.verify(awsS3FilesystemService, Mockito.times(2))
                .updateProjectsForConfig(any());
    }

    @Test
    @DirtiesContext
    public void testUpdatePermittedProjectsPermitAll() throws Exception {
        long id = testCreateEntity(awsArchiverConfig, true);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(0));
        assertThat(entity.isPermitAllProjects(), is(false));
        AwsS3Config pojo = entity.toPojo();
        pojo.setPermitAllProjects(true);
        testUpdateEntity(id, pojo, true);

        List<String> toRemove = new ArrayList<>(allProjectArrayList);
        toRemove.remove(supportedProject);
        toRemove.remove(otherProject);

        awsS3ConfigEntityService.updatePermittedProjects(id, false,
                toRemove, awsS3FilesystemService);
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(2));
        assertThat(entity.getPermittedProjects(), containsInAnyOrder(supportedProject, otherProject));

        String p1 = "p1";
        addArchivesForProject(entity, p1);
        awsS3ConfigEntityService.updatePermittedProjects(id, false,
                Arrays.asList(otherProject, p1), awsS3FilesystemService);
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(2));
        assertThat(entity.getPermittedProjects(), containsInAnyOrder(supportedProject, p1));

        Mockito.verify(awsS3FilesystemService, Mockito.times(3))
                .updateProjectsForConfig(any());
    }

    @Test
    @DirtiesContext
    public void testUpdatePermittedProjectsAll() throws Exception {
        long id = testCreateEntity(awsArchiverConfig, true);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(0));
        assertThat(entity.isPermitAllProjects(), is(false));
        awsS3ConfigEntityService.updatePermittedProjects(id, true, null,
                awsS3FilesystemService);

        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isPermitAllProjects(), is(true));

        ProjectFilesystemSettingsEntity projectEntity = addArchivesForProject(entity, supportedProject);
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(1));
        assertThat(entity.getPermittedProjects(), hasItem(projectEntity.getProjectId()));
        assertThat(entity.isPermitAllProjects(), is(true));

        awsS3ConfigEntityService.updatePermittedProjects(id, false, null,
                awsS3FilesystemService);
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.getPermittedProjects(), hasSize(1));
        assertThat(entity.getPermittedProjects(), hasItem(projectEntity.getProjectId()));
        assertThat(entity.isPermitAllProjects(), is(false));

        Mockito.verify(awsS3FilesystemService, Mockito.times(3))
                .updateProjectsForConfig(any());
    }

    @Test
    @DirtiesContext
    public void testGetPojoForAllConfigs() throws Exception {
        AwsS3Config pojo1 = createEntityAndReturnPojo(awsArchiverConfig);
        AwsS3Config pojo2 = createEntityAndReturnPojo(awsSubdirConfig);
        AwsS3Config pojo3 = createEntityAndReturnPojo(awsAltConfig);
        List<AwsS3Config> configs = awsS3ConfigEntityService.getPojoForAllConfigs();
        assertThat(configs, hasSize(3));
        assertThat(configs, containsInAnyOrder(pojo1, pojo2, pojo3));

        awsS3ConfigEntityService.delete(pojo2.getId());
        pojo3.setName("newname");
        awsS3ConfigEntityService.createOrUpdateFromPojo(pojo3, false,
                awsS3FilesystemService);

        configs = awsS3ConfigEntityService.getPojoForAllConfigs();
        assertThat(configs, hasSize(2));
        assertThat(configs, containsInAnyOrder(pojo1, pojo3));
    }

    @Test
    @DirtiesContext
    public void testRefreshInvalidArchiver() throws Exception {
        AwsS3Config pojo = createEntityAndReturnPojo(awsArchiverConfig);
        long id = pojo.getId();
        awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(true));
        assertThat(entity.isArchiver(), is(true));
        Mockito.when(mockAwsS3.mockS3client.putObject(eq(awsArchiverConfig.getBucketName()),
                eq(WRITE_CHECK_FILENAME), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new AmazonServiceException(permissionsMsg));
        try {
            awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
            fail("refresh method should have thrown an exception indicating that fs config cannot archive");
        } catch (InvalidEntityException e) {
            assertThat(e.getMessage(), is("You must be able to write to the AWS S3 bucket \"" + pojo.getBucketName() + "\" " +
                    "in order to use it for archival"));
        }
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(true));
        assertThat(entity.isArchiver(), is(false));
    }

    @Test
    @DirtiesContext
    public void testRefreshInvalidBucketArchiver() throws Exception {
        AwsS3Config pojo = createEntityAndReturnPojo(awsArchiverConfig);
        long id = pojo.getId();
        awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(true));
        assertThat(entity.isArchiver(), is(true));
        Mockito.when(mockAwsS3.mockS3client.doesBucketExistV2(pojo.getBucketName()))
                .thenReturn(false);
        try {
            awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
            fail("refresh method should have thrown an exception indicating that bucket doesn't exist");
        } catch (InvalidEntityException e) {
            assertThat(e.getMessage(), is("No such bucket: \"" + pojo.getBucketName() + "\""));
        }
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(false));
        assertThat(entity.isArchiver(), is(false));
    }

    @Test
    @DirtiesContext
    public void testRefreshInactive() throws Exception {
        AwsS3Config pojo = createEntityAndReturnPojo(awsReadonlyConfig);
        long id = pojo.getId();
        awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(true));
        Mockito.when(mockAwsS3.mockS3client.doesBucketExistV2(pojo.getBucketName()))
                .thenReturn(false);
        try {
            awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
            fail("refresh method should have thrown an exception indicating that fs config isn't active");
        } catch (InvalidEntityException e) {
            assertThat(e.getMessage(), is("No such bucket: \"" + pojo.getBucketName() + "\""));
        }
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(false));
    }

    @Test
    @DirtiesContext
    public void testRefreshConnectivity() throws Exception {
        AwsS3Config pojo = createEntityAndReturnPojo(awsReadonlyConfig);
        long id = pojo.getId();
        awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(true));
        Mockito.when(mockAwsS3.mockS3client.doesBucketExistV2(pojo.getBucketName()))
                .thenThrow(new SdkClientException(permissionsMsg));
        String fullMsg = new InvalidEntityException(new RemoteApiException(new SdkClientException(permissionsMsg)))
                .getMessage();
        try {
            awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
            fail("refresh method should have thrown an exception indicating that fs config isn't active");
        } catch (InvalidEntityException e) {
            assertThat(e.getMessage(), is(fullMsg));
        }
        entity = awsS3ConfigEntityService.get(id);
        assertThat(entity.isActive(), is(false));
    }

    private ProjectFilesystemSettingsEntity addArchivesForProject(AwsS3ConfigEntity archiverEntity, String project) throws Exception {
        ProjectFilesystemSettingsEntity entity = new ProjectFilesystemSettingsEntity();
        entity.setProjectId(project);
        entity.setDirectUploadOutputs(true);
        entity.setCleanupInterval(1);
        entity.setArchiverConfig(archiverEntity);
        ProjectFilesystemSettingsEntity created = projectFilesystemSettingsEntityService.create(entity);
        // Manually perform this update, normally taken care of in ProjectFilesystemSettingsServiceImpl.createOrUpdateFromPojo
        awsS3ConfigEntityService.updatePermittedProjects(archiverEntity.getId(), true,
                Collections.singletonList(project), awsS3FilesystemService);
        return created;
    }

    private AwsS3Config createEntityAndReturnPojo(AwsS3Config config)
            throws Exception {
        AwsS3Config configWithS3 = null;
        try {
            configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(config, true,
                    awsS3FilesystemService);
        } catch (InvalidEntityException e) {
            fail("Unexpected exception thrown creating AWS config " + e.getMessage());
        }
        return configWithS3;
    }

    private long testCreateEntity(AwsS3Config config, boolean shouldBeArchiver)
            throws Exception {
        AwsS3Config configWithS3 = null;
        try {
            configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(config, true,
                    awsS3FilesystemService);
        } catch (InvalidEntityException e) {
            fail("Unexpected exception thrown creating AWS config " + e.getMessage());
        }
        validate(config, configWithS3, shouldBeArchiver);
        return configWithS3.getId();
    }

    private void testUpdateEntity(long id, AwsS3Config config, boolean shouldBeArchiver)
            throws Exception {
        AwsS3Config configWithS3 = null;
        config.setId(id);
        try {
            configWithS3 = awsS3ConfigEntityService.createOrUpdateFromPojo(config, true,
                    awsS3FilesystemService);
        } catch (InvalidEntityException e) {
            fail("Unexpected exception thrown creating AWS config " + e.getMessage());
        }
        validate(config, configWithS3, shouldBeArchiver);
    }

    private void validate(AwsS3Config config, AwsS3Config configWithS3, boolean shouldBeArchiver)
            throws Exception {
        AwsS3ConfigEntity entity = awsS3ConfigEntityService.get(configWithS3.getId());
        assertThat(entity, notNullValue());
        assertThat(configWithS3.getS3client(), notNullValue());
        assertThat(configWithS3.getS3transfer(), notNullValue());
        assertThat(configWithS3.isActive(), is(true));
        assertThat(configWithS3.isArchiver(), is(shouldBeArchiver));

        assertThat(configWithS3.getBucketName(), is(config.getBucketName()));
        assertThat(configWithS3.getAccessKey(), is(config.getAccessKey()));
        assertThat(configWithS3.getSecretKey(), is(config.getSecretKey()));
        assertThat(configWithS3.getSubdirectory(), is(config.getSubdirectory()));
        assertThat(configWithS3.isPermitAllProjects(), is(config.isPermitAllProjects()));

        assertThat(entity.isActive(), is(true));
        assertThat(entity.isArchiver(), is(shouldBeArchiver));
        assertThat(entity.getBucketName(), is(config.getBucketName()));
        assertThat(entity.getAccessKey(), is(config.getAccessKey()));
        assertThat(entity.getSecretKey(), is(config.getSecretKey()));
        assertThat(entity.getSubdirectory(), is(config.getSubdirectory()));
        assertThat(entity.isPermitAllProjects(), is(config.isPermitAllProjects()));
    }
}
