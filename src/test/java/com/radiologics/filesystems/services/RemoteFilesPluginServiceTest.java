// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;


import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.TransferProgress;
import com.google.common.collect.ImmutableMap;
import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityService;
import com.radiologics.filesystems.aws.s3.services.AwsS3FilesystemService;
import com.radiologics.filesystems.config.IntegrationTestConfig;
import com.radiologics.filesystems.config.MockAwsS3;
import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.model.entity.RemoteFilesItemState;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceStatus;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceTask;
import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import com.radiologics.filesystems.utils.TestingUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.model.CatEntryI;
import org.nrg.xdat.model.CatEntryMetafieldI;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatMrscandata;
import org.nrg.xdat.om.XnatMrsessiondata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.auto.AutoXnatAbstractresource;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xft.ItemI;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.exceptions.UnsupportedRemoteFilesOperationException;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.impl.ExptScanURI;
import org.nrg.xnat.helpers.uri.archive.impl.ExptURI;
import org.nrg.xnat.helpers.uri.archive.impl.ResourcesExptURI;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.node.services.impl.HibernateXnatNodeInfoService;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.utils.CatalogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.radiologics.filesystems.config.SharedStrings.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.io.FileMatchers.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {IntegrationTestConfig.class, TestConfig.class})
public class RemoteFilesPluginServiceTest {
    @Autowired private HibernateXnatNodeInfoService xnatNodeInfoService;
    @Autowired private XnatNodeInfo xnatNodeInfo;
    @Autowired private SiteConfigPreferences siteConfigPreferences;
    @Autowired private PermissionsServiceI mockPermissionsService;
    @Autowired private CatalogService catalogService;
    @Autowired private RemoteFilesTrackerEntityService remoteFilesTrackerEntityService;
    @Autowired private XnatUserProvider primaryAdminUserProvider;   //mocked but has to be named this way
    @Autowired private AwsS3FilesystemService awsS3FilesystemService;
    @Autowired private ProjectFilesystemSettingsService projectFilesystemSettingsService;
    @Autowired private AwsS3ConfigEntityService awsS3ConfigEntityService;

    @Autowired private AwsS3Config awsArchiverConfig;
    @Autowired private AwsS3Config awsAltConfig;

    @Autowired private RemoteFilesPluginService remoteFilesPluginService;
    private RemoteFilesPluginServiceImpl remoteFilesPluginServiceImpl;

    private UserI mockUser;

    private XnatMrsessiondata session;
    private XnatMrscandata scan;
    private String mockSesUri;
    private String mockScanUri;
    private String mockCatResUri;
    private String mockSesArchivePath;
    private String mockSesArchivePathExpected;
    private String mockSesArchivePathScansRemote;
    private XnatResourcecatalog catRes;
    private XnatResourcecatalog scanCatRes;
    private XnatResourcecatalog localRes;
    private ExptURI mockSesUriObj;
    private ResourcesExptURI mockCatResUriObj;
    private ExptScanURI mockScanUriObj;
    private String localResLabel = "LOCAL";

    private MockAwsS3 mockAwsS3;

    // Mockito 5 static mocks
    private MockedStatic<EventUtils> mockedEventUtils;
    private MockedStatic<PersistentWorkflowUtils> mockedPersistentWorkflowUtils;
    private MockedStatic<UriParserUtils> mockedUriParserUtils;
    private MockedStatic<AutoXnatAbstractresource> mockedAutoXnatAbstractresource;

    // Mockito 5 construction mocks for AWS SDK builders
    private org.mockito.MockedConstruction<com.amazonaws.services.s3.AmazonS3Client> mockedS3Client;
    private org.mockito.MockedConstruction<com.amazonaws.services.s3.transfer.TransferManager> mockedTransferManager;

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Before
    public void setup() throws Exception {
        // needed for association
        xnatNodeInfoService.create(xnatNodeInfo);

        // Needed for to run start/stop Task methods
        remoteFilesPluginServiceImpl = (RemoteFilesPluginServiceImpl) remoteFilesPluginService;

        Files.createDirectories(Paths.get(writableArchivePath));

        mockUser = Mockito.mock(UserI.class);
        Mockito.when(mockUser.getLogin()).thenReturn("mockUser");

        // Permissions
        Mockito.when(mockPermissionsService.can(any(UserI.class), any(ItemI.class), anyString()))
                .thenReturn(Boolean.TRUE);

        // No workflows - Mock static methods with Mockito 5
        mockedEventUtils = Mockito.mockStatic(EventUtils.class);
        mockedEventUtils.when(() -> EventUtils.getAddModifyAction(anyString(), anyBoolean()))
                .thenReturn("");
        mockedEventUtils.when(() -> EventUtils.newEventInstance(
                any(EventUtils.CATEGORY.class), any(EventUtils.TYPE.class),
                anyString(), anyString(), anyString()))
                .thenReturn(Mockito.mock(EventDetails.class));

        PersistentWorkflowI mockWrk = Mockito.mock(PersistentWorkflowI.class);
        EventMetaI mockEventMeta = Mockito.mock(EventMetaI.class);
        Mockito.when(mockWrk.buildEvent()).thenReturn(mockEventMeta);
        Mockito.when(mockEventMeta.getEventId()).thenReturn(1);
        mockedPersistentWorkflowUtils = Mockito.mockStatic(PersistentWorkflowUtils.class);
        mockedPersistentWorkflowUtils.when(() -> PersistentWorkflowUtils.getOpenWorkflows(
                any(), any()))
                .thenReturn(Collections.emptyList());
        mockedPersistentWorkflowUtils.when(() -> PersistentWorkflowUtils.getOrCreateWorkflowData(
                any(), any(), any(), any()))
                .thenReturn(mockWrk);
        mockedPersistentWorkflowUtils.when(() -> PersistentWorkflowUtils.buildOpenWorkflow(
                any(), any(), any()))
                .thenReturn(mockWrk);

        // URI parsing - Mock static method with Mockito 5
        mockedUriParserUtils = Mockito.mockStatic(UriParserUtils.class);

        //Mock objects
        scan = Mockito.mock(XnatMrscandata.class);
        Mockito.when(scan.getId()).thenReturn("1");
        //Mockito.when(scan.getFile()).thenReturn(Collections.singletonList(scanCatRes));

        session = Mockito.mock(XnatMrsessiondata.class);
        Mockito.when(session.getId()).thenReturn("LOCAL_E00001");
        Mockito.when(session.getXSIType()).thenReturn("xnat:mrSessionData");
        mockSesArchivePathExpected = Paths.get(siteConfigPreferences.getArchivePath(),
                "projectExpected", "arc001", "ses1").toString();
        mockSesArchivePathScansRemote = Paths.get(siteConfigPreferences.getArchivePath(),
                "projectScansRemote", "arc001", "ses1").toString();
        mockSesArchivePath = Paths.get(siteConfigPreferences.getArchivePath(), supportedProject, "arc001", "ses1")
                .toString();
        Mockito.when(session.getExpectedCurrentDirectory()).thenReturn(new File(mockSesArchivePath));
        Mockito.when(session.getArchiveRootPath()).thenReturn(testArchiveDir);

        // Create necessary directories for ThreadAndProcessFileLock
        Files.createDirectories(Paths.get(mockSesArchivePath));
        Files.createDirectories(Paths.get(mockSesArchivePathExpected));
        Files.createDirectories(Paths.get(mockSesArchivePathScansRemote));
        Files.createDirectories(Paths.get(siteConfigPreferences.getCachePath()));
        //Mockito.when(session.getScans_scan()).thenReturn(Collections.singletonList(scan));
        Mockito.when(session.getProject()).thenReturn(supportedProject);

        catRes = Mockito.mock(XnatResourcecatalog.class);
        int catResId = 1;
        Mockito.when(catRes.getXnatAbstractresourceId()).thenReturn(catResId);
        Mockito.when(catRes.getLabel()).thenReturn("DEBUG_OUTPUT");
        String catResUri = Paths.get(mockSesArchivePath, "RESOURCES",
                catRes.getLabel(), catRes.getLabel() + "_catalog.xml").toString();
        Mockito.when(catRes.getUri()).thenReturn(catResUri);

        scanCatRes = Mockito.mock(XnatResourcecatalog.class);
        int scanCatResId = 2;
        Mockito.when(scanCatRes.getXnatAbstractresourceId()).thenReturn(scanCatResId);
        Mockito.when(scanCatRes.getLabel()).thenReturn("DICOM");
        String scanCatResUri = Paths.get(mockSesArchivePath, "SCANS", scan.getId(),
                scanCatRes.getLabel(), "scan_" + scan.getId() + "_catalog.xml").toString();
        Mockito.when(scanCatRes.getUri()).thenReturn(scanCatResUri);

        // local res
        localRes = Mockito.mock(XnatResourcecatalog.class);
        int localResId = 3;
        Mockito.when(localRes.getXnatAbstractresourceId()).thenReturn(localResId);
        Mockito.when(localRes.getLabel()).thenReturn(localResLabel);
        String localResUri = Paths.get(mockSesArchivePath, "RESOURCES",
                localRes.getLabel(), localRes.getLabel() + "_catalog.xml").toString();
        Mockito.when(localRes.getUri()).thenReturn(localResUri);

        List<XnatAbstractresourceI> resources = Arrays.asList(catRes, scanCatRes, localRes);

        mockSesUriObj = Mockito.mock(ExptURI.class);
        mockCatResUriObj = Mockito.mock(ResourcesExptURI.class);
        mockScanUriObj = Mockito.mock(ExptScanURI.class);
        mockSesUri = "/archive/experiments/" + session.getId();
        mockCatResUri = mockSesUri + "/resources/" + catRes.getLabel();
        mockScanUri = mockSesUri + "/scans/" + scan.getId();
        mockedUriParserUtils.when(() -> UriParserUtils.parseURI(mockSesUri)).thenReturn(mockSesUriObj);
        mockedUriParserUtils.when(() -> UriParserUtils.parseURI(mockCatResUri)).thenReturn(mockCatResUriObj);
        mockedUriParserUtils.when(() -> UriParserUtils.parseURI(mockScanUri)).thenReturn(mockScanUriObj);
        Mockito.when(mockSesUriObj.getSecurityItem()).thenReturn(session);
        Mockito.when(mockCatResUriObj.getSecurityItem()).thenReturn(session);
        Mockito.when(mockScanUriObj.getSecurityItem()).thenReturn(session);
        Mockito.when(mockSesUriObj.getResources(true)).thenReturn(resources);
        Mockito.when(mockSesUriObj.getResources(false)).thenReturn(Arrays.asList(catRes, localRes));
        Mockito.when(mockCatResUriObj.getResourceFilePath()).thenReturn("");
        Mockito.when(mockCatResUriObj.getXnatResource()).thenReturn(catRes);
        Mockito.when(mockScanUriObj.getResources(anyBoolean())).thenReturn(Collections.singletonList(scanCatRes));

        // Mock resource check - Mock static method with Mockito 5
        Mockito.when(primaryAdminUserProvider.get()).thenReturn(mockUser);
        mockedAutoXnatAbstractresource = Mockito.mockStatic(AutoXnatAbstractresource.class);
        mockedAutoXnatAbstractresource.when(() -> AutoXnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(
                anyInt(), eq(mockUser), eq(false)))
                .thenReturn(null);
        mockedAutoXnatAbstractresource.when(() -> AutoXnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(
                catResId, mockUser, false))
                .thenReturn(catRes);
        mockedAutoXnatAbstractresource.when(() -> AutoXnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(
                scanCatResId, mockUser, false))
                .thenReturn(scanCatRes);

        //Mock catalog - Mock static methods and use reflection instead of Whitebox
        // Set _checksumConfig field using Java reflection instead of Whitebox
        Field checksumConfigField = CatalogUtils.class.getDeclaredField("_checksumConfig");
        checksumConfigField.setAccessible(true);
        checksumConfigField.set(null, new AtomicBoolean(false));

        // Note: queryResourceProject() is a private method in CatalogUtils.CatalogData
        // We cannot directly mock private methods with Mockito 5
        // The method will use its real implementation during tests

        // Project settings (need the POJO with id set)
        mockAwsS3 = new MockAwsS3();

        // Mock AWS SDK builders to return our mock clients
        mockedS3Client = MockAwsS3.mockS3ClientConstruction(mockAwsS3);
        mockedTransferManager = MockAwsS3.mockTransferManagerConstruction(mockAwsS3);

        AwsS3Config archiverConfig = awsS3ConfigEntityService.createOrUpdateFromPojo(awsArchiverConfig,
                true, awsS3FilesystemService);
        awsS3ConfigEntityService.createOrUpdateFromPojo(awsAltConfig, true, awsS3FilesystemService);
        TestingUtils.setupProjectSettings(projectFilesystemSettingsService, archiverConfig);
    }

    @After
    public void cleanup() throws IOException {
        FileUtils.deleteDirectory(new File(writableArchivePath));

        // Close all static mocks to prevent memory leaks
        if (mockedEventUtils != null) {
            mockedEventUtils.close();
        }
        if (mockedPersistentWorkflowUtils != null) {
            mockedPersistentWorkflowUtils.close();
        }
        if (mockedUriParserUtils != null) {
            mockedUriParserUtils.close();
        }
        if (mockedAutoXnatAbstractresource != null) {
            mockedAutoXnatAbstractresource.close();
        }

        // Close Mockito 5 construction mocks
        if (mockedS3Client != null) {
            mockedS3Client.close();
        }
        if (mockedTransferManager != null) {
            mockedTransferManager.close();
        }
    }

    @Test
    @DirtiesContext
    public  void testPullFileAws() throws Exception {
        String name = "testPullFileAws.txt";
        String path = Paths.get(writableArchivePath, name).toString();
        File file = remoteFilesPluginService.pullFile(awsGoodUrl, path, supportedProject);
        assertNotNull(file);
        assertThat(file, anExistingFile());
        assertThat(file, aFileWithSize(awsGoodUrlFileSize));
        assertThat(file, aFileWithAbsolutePath(equalTo(path)));
        assertThat(file, aFileNamed(equalTo(name)));
        assertTrue("File content doesn't match expected", FileUtils.contentEquals(file, awsGoodUrlFile));
    }

    @Test
    @DirtiesContext
    public  void testPullFileAwsException() throws Exception {
        String name = "testPullFileAwsException.txt";
        String path = Paths.get(writableArchivePath, name).toString();
        exceptionRule.expect(FileNotFoundException.class);
        exceptionRule.expectMessage("No configured filesystem can pull URL " + exceptionThrowerUrl);
        remoteFilesPluginService.pullFile(exceptionThrowerUrl, path, supportedProject);
    }

    @Test
    @DirtiesContext
    public  void testPullFileAwsMissing() throws Exception {
        String name = "testPullFileAwsMissing.txt";
        String path = Paths.get(writableArchivePath, name).toString();
        exceptionRule.expect(FileNotFoundException.class);
        exceptionRule.expectMessage("No configured filesystem can pull URL " + awsMissingUrl);
        remoteFilesPluginService.pullFile(awsMissingUrl, path, supportedProject);
    }

    @Test
    @DirtiesContext
    public  void testPullFileDefault() throws Exception {
        String path = Paths.get(writableArchivePath, defaultFsGoodUrlArchiveName).toString();
        File file = remoteFilesPluginService.pullFile(defaultFsGoodUrl, path, supportedProject);
        assertNotNull(file);
        assertThat(file, anExistingFile());
        assertThat(file, aFileWithSize(defaultFsGoodUrlResponseSize));
        assertThat(file, aFileWithAbsolutePath(equalTo(path)));
        assertThat(file, aFileNamed(equalTo(defaultFsGoodUrlArchiveName)));
        assertTrue("File content doesn't match expected", FileUtils.contentEquals(file, defaultFsGetFile));
    }

    @Test
    @DirtiesContext
    public  void testPullFileNonUrl() throws Exception {
        String path = Paths.get(writableArchivePath, nonUrl).toString();
        exceptionRule.expect(FileNotFoundException.class);
        exceptionRule.expectMessage(nonUrl + " is not a remote URL");
        remoteFilesPluginService.pullFile(nonUrl, path, supportedProject);
    }

    @Test
    @DirtiesContext
    public  void testPullFileUnsupportedUrl() throws Exception {
        String path = Paths.get(writableArchivePath, "junk.txt").toString();
        exceptionRule.expect(FileNotFoundException.class);
        exceptionRule.expectMessage("No configured filesystem can pull URL " + badUrl);
        remoteFilesPluginService.pullFile(badUrl, path, supportedProject);
    }

    @Test
    @DirtiesContext
    public  void testCanPullFile() {
        assertThat(remoteFilesPluginService.canPullFile(defaultFsGoodUrl, supportedProject), is(true));
        assertThat(remoteFilesPluginService.canPullFile(awsGoodUrl, supportedProject), is(true));
        assertThat(remoteFilesPluginService.canPullFile(awsGoodUrl2, supportedProject), is(true));
        assertThat(remoteFilesPluginService.canPullFile(awsMissingUrl, supportedProject), is(false)); //valid but missing
        assertThat(remoteFilesPluginService.canPullFile(badUrl, supportedProject), is(false));
        assertThat(remoteFilesPluginService.canPullFile(nonUrl, supportedProject), is(false));
        assertThat(remoteFilesPluginService.canPullFile(nonUrl, supportedProject), is(false));

        assertThat(remoteFilesPluginService.canPullFile(defaultFsGoodUrl, otherProject), is(true));
        assertThat(remoteFilesPluginService.canPullFile(awsGoodUrl, otherProject), is(false));
        assertThat(remoteFilesPluginService.canPullFile(awsGoodUrl2, otherProject), is(true)); // permit all
        assertThat(remoteFilesPluginService.canPullFile(awsMissingUrl, otherProject), is(false)); //valid but missing
        assertThat(remoteFilesPluginService.canPullFile(badUrl, otherProject), is(false));
        assertThat(remoteFilesPluginService.canPullFile(nonUrl, otherProject), is(false));
    }

    @Test
    @DirtiesContext
    public  void testDeleteFileAws() {
        assertThat(remoteFilesPluginService.deleteFile(awsGoodUrl, supportedProject), is(true));
    }

    @Test
    @DirtiesContext
    public  void testDeleteFileAwsMissing() {
        assertThat(remoteFilesPluginService.deleteFile(awsMissingUrl, supportedProject), is(false));
    }

    @Test
    @DirtiesContext
    public  void testDeleteFileAwsException() {
        assertThat(remoteFilesPluginService.deleteFile(exceptionThrowerUrl, supportedProject), is(false));
    }

    @Test
    @DirtiesContext
    public  void testDeleteFileDefault() {
        assertThat(remoteFilesPluginService.deleteFile(defaultFsGoodUrl, supportedProject), is(false));
    }

    @Test
    @DirtiesContext
    public  void testDeleteFileNonUrl() {
        assertThat(remoteFilesPluginService.deleteFile(nonUrl, supportedProject), is(false));
    }

    @Test
    @DirtiesContext
    public  void testDeleteFileUnsupportedUrl() {
        assertThat(remoteFilesPluginService.deleteFile(badUrl, supportedProject), is(false));
    }

    @Test
    @DirtiesContext
    public void testStartingAndCompletingTasks() {
        // No remote files, don't pull
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false), is(false));
        // No remote files, but pulling outside archive
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, true), is(true));

        // normal pushing
        MutableBoolean firstPush = new MutableBoolean(false);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush), is(true));
        assertTrue("First push should be true", firstPush.booleanValue());
        remoteFilesPluginServiceImpl.completePush(session, catRes, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Archived));

        // normal pulling
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false), is(true));
        remoteFilesPluginServiceImpl.completePull(session, catRes, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Local));

        // attempt push with no change
        firstPush = new MutableBoolean(false);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush), is(true));
        assertFalse("First push should be false", firstPush.booleanValue());
        remoteFilesPluginServiceImpl.completePush(session, catRes, false);
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Local));

        //For now, can pull even if local as long as there have been remote files in past
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false), is(true));

        // ensure we prevent concurrent ops when pulling except pull outside archive
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, true), is(true));
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush), is(false));
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes), is(false));

        remoteFilesPluginServiceImpl.completePull(session, catRes, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Local));

        // ensure we prevent concurrent ops when pushing
        firstPush = new MutableBoolean(true);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush), is(true));
        assertFalse("First push should be false", firstPush.booleanValue());
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false), is(false));
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, true), is(false));
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes), is(false));
        remoteFilesPluginServiceImpl.completePush(session, catRes, true);

        // ensure we  prevent concurrent ops when adding
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes), is(true));
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false), is(false));
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, true), is(false));
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush), is(false));
        remoteFilesPluginServiceImpl.completeAdd(session, catRes, true);

        // ensure we allow concurrent ops when pulling outside archive
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, true), is(true));
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush), is(true));
        assertFalse("First push should be false", firstPush.booleanValue());
        remoteFilesPluginServiceImpl.completePush(session, catRes, true);

        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, true), is(true));
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false), is(true));
        remoteFilesPluginServiceImpl.completePull(session, catRes, true);

        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, true), is(true));
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes), is(true));
        remoteFilesPluginServiceImpl.completeAdd(session, catRes, true);
    }

    @Test
    @DirtiesContext
    public void testCatalogHasRemoteFiles() {
        // Test that new resource is not considered remote
        XnatResourcecatalog catResAlt = Mockito.mock(XnatResourcecatalog.class);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catResAlt), is(false));

        MutableBoolean firstPush = new MutableBoolean(false);

        // Test that starting a task makes resource remote
        remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush);
        assertTrue("First push should be true", firstPush.booleanValue());
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));

        // Since we completed the task without changing the status, the resource shouldn't be considered remote
        remoteFilesPluginServiceImpl.completePush(session, catRes, false);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(false));

        // Now try again, but do change the status and ensure resource is now remote
        firstPush = new MutableBoolean(false);
        remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush);
        assertTrue("First push should be true", firstPush.booleanValue());
        remoteFilesPluginServiceImpl.completePush(session, catRes, true);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));

        // Ensure that even if a future task doesn't alter status, resource remains remote
        remoteFilesPluginServiceImpl.startPull(session, catRes, false);
        remoteFilesPluginServiceImpl.completePull(session, catRes, false);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));

        // Test that local status is recognized when no task running
        remoteFilesPluginServiceImpl.startPull(session, catRes, false);
        remoteFilesPluginServiceImpl.completePull(session, catRes, true);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(false));

        // Test that pull outside archive doesn't affect remote status
        remoteFilesPluginServiceImpl.startPull(session, catRes, true);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(false));

        // Test that local status is ignored when there's a task
        remoteFilesPluginServiceImpl.startAdd(session, catRes);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));
        remoteFilesPluginServiceImpl.completeAdd(session, catRes, false);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(false));

        // Test that pull outside archive doesn't affect remote status, again
        remoteFilesPluginServiceImpl.startAdd(session, catRes);
        remoteFilesPluginServiceImpl.completeAdd(session, catRes, true);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));
        remoteFilesPluginServiceImpl.startPull(session, catRes, true);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));
    }

    @Test
    @DirtiesContext
    public void testLocalCheckBlocksPush() {
        fakeArchiveResource(catRes);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));
        fakePullResource(catRes);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(false));

        // Test that having just checked if files are local ensures that we can't push
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, new MutableBoolean(false)),
                is(false));
    }

    @Test
    @DirtiesContext
    public void testLocalCheckOnlyAffectsLocalResource() {
        fakeArchiveResource(catRes);
        ZonedDateTime lastCheck = remoteFilesTrackerEntityService.findByResource(catRes).getLastLocalCheck();
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getLastLocalCheck(), is(lastCheck));
    }

    @Test
    @DirtiesContext
    public void testLocalCheckOnlyAffectsUntaskedResource() {
        fakeArchiveResource(catRes);
        fakePullResource(catRes);
        ZonedDateTime lastCheck = remoteFilesTrackerEntityService.findByResource(catRes).getLastLocalCheck();
        MutableBoolean firstPush = new MutableBoolean(false);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush), is(true));
        assertFalse("First push should be false", firstPush.booleanValue());
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(catRes), is(true));
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getLastLocalCheck(), is(lastCheck));
    }

    @Test
    @DirtiesContext
    public void testMonitorPullProgressLocal() throws Exception {
        // never been remote, ensure this returns 100
        assertThat(remoteFilesPluginService.monitorPullProgress(catRes), is(100.0));

        // has been remote but is currently local
        fakeArchiveResource(catRes);
        fakePullResource(catRes);
        assertThat(remoteFilesPluginService.monitorPullProgress(catRes), is(100.0));
    }

    @Test
    @DirtiesContext
    public void testMonitorPullProgressException() throws Exception {
        // not being pulled
        fakeArchiveResource(catRes);
        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Pull failed, direct admin to filesystems.log");
        remoteFilesPluginService.monitorPullProgress(catRes);
    }

    @Test
    @DirtiesContext
    public void testMonitorPullProgress() throws Exception {
        fakeArchiveResource(catRes);
        remoteFilesPluginServiceImpl.startPull(session, catRes, false);
        RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource(catRes);
        for (double p : Arrays.asList(50.0, 75.0, 100.0)) {
            remoteFilesTrackerEntityService.setPullProgress(entity, p);
            assertThat(remoteFilesPluginService.monitorPullProgress(catRes), is(p));
        }

        // Fake a failed pull
        remoteFilesPluginServiceImpl.completePull(session, catRes, false);
        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Pull failed, direct admin to filesystems.log");
        remoteFilesPluginService.monitorPullProgress(catRes);
    }

    @Test
    @DirtiesContext
    public void testInitiatePullItemResourceLocal() throws Exception {
        // not remote, returns true
        Map<Integer, Future<Boolean>> jobs = remoteFilesPluginService.initiatePullItem(session,
                Collections.singletonList(catRes), null, null);
        assertThat(jobs.keySet(), hasSize(0));

        // was remote but is local, no pulling to archive
        fakeArchiveResource(catRes);
        fakePullResource(catRes);
        jobs = remoteFilesPluginService.initiatePullItem(session,
                Collections.singletonList(catRes), null, null);
        assertThat(jobs.keySet(), hasSize(0));

        // same test
        jobs = remoteFilesPluginService.initiatePullItem(session,
                Collections.singletonList(catRes), null, mockSesArchivePath);
        assertThat(jobs.keySet(), hasSize(0));

        // same test
        jobs = remoteFilesPluginService.initiatePullItem(session,
                Collections.singletonList(catRes), mockSesArchivePath, null);
        assertThat(jobs.keySet(), hasSize(0));


        // same test
        jobs = remoteFilesPluginService.initiatePullItem(session,
                Collections.singletonList(catRes), mockSesArchivePath, mockSesArchivePath);
        assertThat(jobs.keySet(), hasSize(0));

        // but allow outside archive pull
        jobs = remoteFilesPluginService.initiatePullItem(session,
                Collections.singletonList(catRes), null, writableArchivePath);
        assertThat(jobs.get(catRes.getXnatAbstractresourceId()).get(), is(notNullValue()));
    }


    @Test
    @DirtiesContext
    public void integrationTestPullItemSession() throws Exception {
        // Pretend to archive all resources
        fakeArchiveResources(mockSesUriObj.getResources(true));

        // Now pull them
        String outPath = mockSesArchivePath.replace(siteConfigPreferences.getArchivePath(), writableArchivePath);
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, mockSesArchivePath, outPath);
        checkFiles(mockSesArchivePath, writableArchivePath);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemSessionPartial() throws Exception {
        // Pretend to archive scan resources
        fakeArchiveResource(scanCatRes);

        // Now pull all session resources, which will be a mix of copy (catRes) & pull (scanRes)
        String catResUri = catRes.getUri().replace(mockSesArchivePath, mockSesArchivePathScansRemote);
        String scanResUri = scanCatRes.getUri().replace(mockSesArchivePath, mockSesArchivePathScansRemote);
        Mockito.when(session.getExpectedCurrentDirectory()).thenReturn(new File(mockSesArchivePathScansRemote));
        Mockito.when(catRes.getUri()).thenReturn(catResUri);
        Mockito.when(scanCatRes.getUri()).thenReturn(scanResUri);
        String outPath = mockSesArchivePathScansRemote.replace(siteConfigPreferences.getArchivePath(), writableArchivePath);
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, mockSesArchivePathScansRemote, outPath);
        checkFiles(catResUri, writableArchivePath, false);
        checkFiles(scanResUri, writableArchivePath, true);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemSessionLocal() throws Exception {
        // Pretend to archive resources and then pull them
        List<XnatAbstractresourceI> resources = mockSesUriObj.getResources(true);
        fakeArchiveResources(resources);
        fakePullResources(resources);

        // Update uris
        updateResourceUris(mockSesArchivePathExpected, resources);

        // Now pull all session resources to new location
        String outPath = mockSesArchivePathExpected.replace(siteConfigPreferences.getArchivePath(), writableArchivePath);
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, mockSesArchivePathExpected, outPath);
        checkFiles(mockSesArchivePathExpected, writableArchivePath, false);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemResource() throws Exception {
        // Pretend to archive the resource
        fakeArchiveResource(catRes);

        pullItemResourceSingle();
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemRelative() throws Exception {
        // Pretend to archive all remote resources
        fakeArchiveResources(mockSesUriObj.getResources(true));

        // Now pull them
        String outPath = mockSesArchivePath.replace(siteConfigPreferences.getArchivePath(), writableArchivePath);
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, null, outPath);
        checkFiles(mockSesArchivePath, writableArchivePath);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemArchive() throws Exception {
        // Pretend to archive all resources
        List<XnatAbstractresourceI> resources = mockSesUriObj.getResources(true);
        fakeArchiveResources(resources);

        // Update uris
        copyFilesToWritableLocAndUpdateUris(mockSesArchivePath, resources);

        // Now pull them
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, null, null);
        checkFiles(mockSesArchivePath, writableArchivePath);

        // Ensure entity status updated
        for (XnatAbstractresourceI res : resources) {
            if (!(res instanceof XnatResourcecatalog) || res.getLabel().equals(localResLabel)) {
                continue;
            }
            RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource((XnatResourcecatalog) res);
            assertThat(entity, is(notNullValue()));
            assertThat(entity.getStatus(), is(RemoteFilesResourceStatus.Local));
            assertThat(entity.getTask(), is(RemoteFilesResourceTask.None));
            assertThat(entity.getPullProgress(), is(nullValue()));
        }
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemScan() throws Exception {
        // Pretend to archive all resources
        fakeArchiveResources(mockSesUriObj.getResources(true));

        // Now pull them
        String archivePath = Paths.get(scanCatRes.getUri()).getParent().getParent().toString();
        String outPath = archivePath.replace(siteConfigPreferences.getArchivePath(), writableArchivePath);
        catalogService.pullResourceCatalogsToDestination(mockUser, mockScanUri, archivePath, outPath);
        checkFiles(archivePath, writableArchivePath);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemException() throws Exception {
        // Pretend to start pushing the resource
        MutableBoolean firstPush = new MutableBoolean(false);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush),
                is(true));
        assertTrue("First push should be true", firstPush.booleanValue());
        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Unable to pull some or all resources for item " + session.getId() +
                ": resId " + catRes.getXnatAbstractresourceId() + " is in another task or the xnat_node_info for the server couldn't be determined");
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, null, null);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemException2() throws Exception {
        // Pretend to start pushing the resource
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes),
                is(true));
        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Unable to pull some or all resources for item " + session.getId() +
                ": resId " + catRes.getXnatAbstractresourceId() + " is in another task or the xnat_node_info for the server couldn't be determined");
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, null, null);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemException3() throws Exception {
        // Pretend to start adding to the resource
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes),
                is(true));
        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Unable to pull some or all resources for item " + session.getId() +
                ": resId " + catRes.getXnatAbstractresourceId() + " is in another task or the xnat_node_info for the server couldn't be determined");
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, null, writableArchivePath);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemException4() throws Exception {
        fakeArchiveResource(catRes);
        // Pretend to start pulling the resource
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false),
                is(true));
        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Unable to pull some or all resources for item " + session.getId() +
                ": resId " + catRes.getXnatAbstractresourceId() + " is in another task or the xnat_node_info for the server couldn't be determined");
        catalogService.pullResourceCatalogsToDestination(mockUser, mockSesUri, null, null);
    }

    @Test
    @DirtiesContext
    public void integrationTestPullItemDuringPull() throws Exception {
        fakeArchiveResource(catRes);
        // Pretend to start pulling the resource
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false),
                is(true));

        // pull outside archive should succeed
        pullItemResourceSingle();
    }

    @Test
    @DirtiesContext
    public  void testInitiatePushItemException1() throws Exception {
        String unsupportedProj = "asdf";
        Mockito.when(session.getProject()).thenReturn(unsupportedProj);
        exceptionRule.expect(ClientException.class);
        exceptionRule.expectMessage("Project " + unsupportedProj + " not configured for filesystems plugin");
        remoteFilesPluginServiceImpl.initiatePushItem(session, Arrays.asList(catRes), mockUser);
    }

    @Test
    @DirtiesContext
    public void testInitiatePushItemException2() throws Exception {
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes), is(true));
        exceptionRule.expect(ClientException.class);
        exceptionRule.expectMessage("Item " + session.getId() + " resource " + catRes.getLabel() +
                " (" + catRes.getXnatAbstractresourceId() + ") cannot be pushed due to other task");
        remoteFilesPluginServiceImpl.initiatePushItem(session, Arrays.asList(catRes), mockUser);
    }

    @Test
    @DirtiesContext
    public void testInitiatePushItemException3() {
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, catRes), is(true));
        try {
            remoteFilesPluginServiceImpl.initiatePushItem(session, Arrays.asList(catRes, scanCatRes), mockUser);
        } catch (ClientException e) {
            assertThat(e.getMessage(), is("Item " + session.getId() + " resource " + catRes.getLabel() +
                    " (" + catRes.getXnatAbstractresourceId() + ") cannot be pushed due to other task"));
        }
        assertNull("Entire push should have been aborted when one res was in another task",
                remoteFilesTrackerEntityService.findByResource(scanCatRes));
    }

    @Test
    @DirtiesContext
    public void testInitiatePushItem() throws Exception {
        Map<String, File> expdResFiles = new HashMap<>();
        Map<String, File> notExpdResFiles = new HashMap<>();
        File catFile = setUpForPush(expdResFiles, notExpdResFiles, null);

        // since the executor service for testing is synchronous, this will complete immediately
        remoteFilesPluginService.initiatePushItem(session, Collections.singletonList(localRes), mockUser);

        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, localRes, supportedProject);
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(remoteFilesTrackerEntityService.findByResource(localRes).getStatus(),
                is(RemoteFilesResourceStatus.Archived));
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(localRes), is(true));
        String catPathRel = Paths.get(siteConfigPreferences.getArchivePath()).relativize(Paths.get(catalogData.catPath))
                .toString();
        // Assert file is pushed regardless of expiration
        for (String relPath : expdResFiles.keySet()) {
            assertFilePushed(catalogMap, catPathRel, relPath, expdResFiles.get(relPath));
        }
        for (String relPath : notExpdResFiles.keySet()) {
            assertFilePushed(catalogMap, catPathRel, relPath, notExpdResFiles.get(relPath));
        }
    }

    @Test
    @DirtiesContext
    public void testGetItemState() {
        RemoteFilesItemState state = remoteFilesPluginService.getItemState(session);
        assertThat(state.getStatus(), is(RemoteFilesItemState.Status.Inactive));
        assertThat(state.getResources(), hasSize(0));

        //fake start push one res
        MutableBoolean firstPush = new MutableBoolean(false);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush),
                is(true));
        assertTrue("First push should be true", firstPush.booleanValue());
        state = remoteFilesPluginService.getItemState(session);
        assertThat(state.getStatus(), is(RemoteFilesItemState.Status.Locked));
        assertThat(state.getResources(), hasSize(1));
        RemoteFilesItemState.Resource res = state.getResources().get(0);
        assertThat(res.getId(), is(catRes.getXnatAbstractresourceId().toString()));
        assertThat(res.getLabel(), is(catRes.getLabel()));
        assertThat(res.getStatus(), is(RemoteFilesResourceTask.Push.toString()));

        //end push
        remoteFilesPluginServiceImpl.completePush(session, catRes, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Archived));
        state = remoteFilesPluginService.getItemState(session);
        assertThat(state.getStatus(), is(RemoteFilesItemState.Status.Archived));
        assertThat(state.getResources(), hasSize(1));
        res = state.getResources().get(0);
        assertThat(res.getId(), is(catRes.getXnatAbstractresourceId().toString()));
        assertThat(res.getLabel(), is(catRes.getLabel()));
        assertThat(res.getStatus(), is(RemoteFilesResourceStatus.Archived.toString()));

        //start pull
        assertThat(remoteFilesPluginServiceImpl.startPull(session, catRes, false),
                is(true));
        state = remoteFilesPluginService.getItemState(session);
        assertThat(state.getStatus(), is(RemoteFilesItemState.Status.Locked));
        assertThat(state.getResources(), hasSize(1));
        res = state.getResources().get(0);
        assertThat(res.getId(), is(catRes.getXnatAbstractresourceId().toString()));
        assertThat(res.getLabel(), is(catRes.getLabel()));
        assertThat(res.getStatus(), is(RemoteFilesResourceTask.Pull.toString()));

        //end pull
        remoteFilesPluginServiceImpl.completePull(session, catRes, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Local));
        state = remoteFilesPluginService.getItemState(session);
        assertThat(state.getStatus(), is(RemoteFilesItemState.Status.Local));
        assertThat(state.getResources(), hasSize(1));
        res = state.getResources().get(0);
        assertThat(res.getId(), is(catRes.getXnatAbstractresourceId().toString()));
        assertThat(res.getLabel(), is(catRes.getLabel()));
        assertThat(res.getStatus(), is(RemoteFilesResourceStatus.Local.toString()));

        //start add
        assertThat(remoteFilesPluginServiceImpl.startAdd(session, scanCatRes),
                is(true));
        state = remoteFilesPluginService.getItemState(session);
        assertThat(state.getStatus(), is(RemoteFilesItemState.Status.Locked));
        assertThat(state.getResources(), hasSize(2));
        RemoteFilesItemState.Resource scanRes = null;
        res = null;
        for (RemoteFilesItemState.Resource r : state.getResources()) {
            if (Integer.parseInt(r.getId()) == catRes.getXnatAbstractresourceId()) {
                res = r;
            } else {
                scanRes = r;
            }
        }
        assertNotNull(res);
        assertNotNull(scanRes);
        assertThat(res.getId(), is(catRes.getXnatAbstractresourceId().toString()));
        assertThat(res.getLabel(), is(catRes.getLabel()));
        assertThat(res.getStatus(), is(RemoteFilesResourceStatus.Local.toString()));
        assertThat(scanRes.getId(), is(scanCatRes.getXnatAbstractresourceId().toString()));
        // RemoteFilesTrackerEntity#getInformativeLabelForResource
        assertThat(scanRes.getLabel(), is("scan_" + scan.getId() + "_" + scanCatRes.getLabel()));
        assertThat(scanRes.getStatus(), is(RemoteFilesResourceTask.Add.toString()));

        //end add
        remoteFilesPluginServiceImpl.completeAdd(session, scanCatRes, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(scanCatRes).getStatus(),
                is(RemoteFilesResourceStatus.Archived));
        state = remoteFilesPluginService.getItemState(session);
        assertThat(state.getStatus(), is(RemoteFilesItemState.Status.Archived));
        assertThat(state.getResources(), hasSize(2));
        res = null;
        scanRes = null;
        for (RemoteFilesItemState.Resource r : state.getResources()) {
            if (Integer.parseInt(r.getId()) == catRes.getXnatAbstractresourceId()) {
                res = r;
            } else {
                scanRes = r;
            }
        }
        assertNotNull(res);
        assertNotNull(scanRes);
        assertThat(res.getId(), is(catRes.getXnatAbstractresourceId().toString()));
        assertThat(res.getLabel(), is(catRes.getLabel()));
        assertThat(res.getStatus(), is(RemoteFilesResourceStatus.Local.toString()));
        assertThat(scanRes.getId(), is(scanCatRes.getXnatAbstractresourceId().toString()));
        // RemoteFilesTrackerEntity#getInformativeLabelForResource
        assertThat(scanRes.getLabel(), is("scan_" + scan.getId() + "_" + scanCatRes.getLabel()));
        assertThat(scanRes.getStatus(), is(RemoteFilesResourceStatus.Archived.toString()));
    }

    @Test
    @DirtiesContext
    public  void testAddUrlsToCatalogException() throws Exception {
        File catFile = new File(catRes.getUri());
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, supportedProject);
        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Catalog resource not specified, cannot add urls");
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, null, true, null);
    }

    @Test
    @DirtiesContext
    public void testAddUrlsToCatalogException2() throws Exception {
        File catFile = new File(catRes.getUri());
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);

        MutableBoolean firstPush = new MutableBoolean(false);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, firstPush),
                is(true));
        assertTrue("First push should be true", firstPush.booleanValue());

        exceptionRule.expect(ServerException.class);
        exceptionRule.expectMessage("Unable to add urls to resource because it is performing another task");
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, null, true, null);
    }

    @Test
    @DirtiesContext
    public void testAddUrlsToCatalogNew() throws Exception {
        File catFile = getWritableCatalog();
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        Map<String, CatalogUtils.CatalogEntryAttributes> urlMap = new HashMap<>();
        urlMap.put(awsGoodUrl3, new CatalogUtils.CatalogEntryAttributes(testFileName, testFile.getName(),
                testFile.length(), new Date(testFile.lastModified()), null, null, null));
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, urlMap,
                false, null);

        catalogData = new CatalogUtils.CatalogData(catFile, supportedProject);
        assertThat(catalogData.catBean.getEntries_entry(), hasSize(2));
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(catalogMap.containsKey(testFileName), is(true));
        assertThat(catalogMap.get(testFileName).entry.getUri(), is(awsGoodUrl3));
        assertThat(catalogMap.get(catResFileName).entry.getUri(),
                is(catResFileUrl));
    }

    @Test
    @DirtiesContext
    public void testAddUrlsToCatalogOverwriteToChangeUrl() throws Exception {
        File catFile = getWritableCatalog();
        // put the existing catalog entry file into the catalog dir
        File fileToOverwrite = Paths.get(catFile.getParent(), catResFileName).toFile();
        Files.createDirectories(fileToOverwrite.getParentFile().toPath());
        Files.copy(testFile.toPath(), fileToOverwrite.toPath());
        assertThat(fileToOverwrite, anExistingFile());

        // perform the overwrite
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        Map<String, CatalogUtils.CatalogEntryAttributes> urlMap = new HashMap<>();
        urlMap.put(awsGoodUrl3, new CatalogUtils.CatalogEntryAttributes(catResFileName, catResFileName,
                testFile.length(), new Date(testFile.lastModified()), null, null, null));
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, urlMap,
                true, null);

        catalogData = new CatalogUtils.CatalogData(catFile, supportedProject);
        assertThat(catalogData.catBean.getEntries_entry(), hasSize(1));
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(catalogMap.containsKey(catResFileName), is(true));
        assertThat(catalogMap.get(catResFileName).entry.getUri(), is(awsGoodUrl3));
        assertThat(fileToOverwrite, not(anExistingFile()));
    }

    @Test
    @DirtiesContext
    public void testAddUrlsToCatalogOverwriteToChangeRelPath() throws Exception {
        File catFile = getWritableCatalog();
        // put the existing catalog entry file into the catalog dir
        File fileToOverwrite = Paths.get(catFile.getParent(), catResFileName).toFile();
        Files.createDirectories(fileToOverwrite.getParentFile().toPath());
        Files.copy(testFile.toPath(), fileToOverwrite.toPath());
        assertThat(fileToOverwrite, anExistingFile());
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        Map<String, CatalogUtils.CatalogEntryAttributes> urlMap = new HashMap<>();
        urlMap.put(catResFileUrl, new CatalogUtils.CatalogEntryAttributes(testFileName, testFile.getName(),
                testFile.length(), new Date(testFile.lastModified()), null, null, null));
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, urlMap,
                true, null);

        catalogData = new CatalogUtils.CatalogData(catFile, supportedProject);
        assertThat(catalogData.catBean.getEntries_entry(), hasSize(1));
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(catalogMap.containsKey(testFileName), is(true));
        assertThat(catalogMap.get(testFileName).entry.getUri(), is(catResFileUrl));
        assertThat(fileToOverwrite, not(anExistingFile()));
    }

    @Test
    @DirtiesContext
    public void testAddUrlsToCatalogOverwriteException() throws Exception {
        File catFile = getWritableCatalog();
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        Map<String, CatalogUtils.CatalogEntryAttributes> urlMap = new HashMap<>();
        urlMap.put(awsGoodUrl3, new CatalogUtils.CatalogEntryAttributes(catResFileName, catResFileName,
                testFile.length(), new Date(testFile.lastModified()), null, null, null));

        exceptionRule.expect(ClientException.class);
        exceptionRule.expectMessage("Refusing to overwrite existing catalog entry with url " + awsGoodUrl3 +
                " and/or relative path " + catResFileName);
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, urlMap,
                false, null);
    }

    @Test
    @DirtiesContext
    public void testAddUrlsToCatalogOverwriteException2() throws Exception {
        File catFile = getWritableCatalog();
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        Map<String, CatalogUtils.CatalogEntryAttributes> urlMap = new HashMap<>();
        urlMap.put(awsGoodUrl3, new CatalogUtils.CatalogEntryAttributes(testFileName, testFile.getName(),
                testFile.length(), new Date(testFile.lastModified()), null, null, null));
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, urlMap,
                true, null);

        catalogData = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        assertThat(catalogData.catBean.getEntries_entry(), hasSize(2));
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(catalogMap.containsKey(testFileName), is(true));
        assertThat(catalogMap.get(testFileName).entry.getUri(), is(awsGoodUrl3));
        assertThat(catalogMap.get(catResFileName).entry.getUri(),
                is(catResFileUrl));

        // add url map entry that attempts to overwrite both existing catalog entries, which should throw an exception
        urlMap.clear();
        urlMap.put(awsGoodUrl3, new CatalogUtils.CatalogEntryAttributes(catResFileName, catResFileName,
                testFile.length(), new Date(testFile.lastModified()), null, null, null));

        exceptionRule.expect(ClientException.class);
        exceptionRule.expectMessage("Found separate catalog entries, one with url " + awsGoodUrl3 +
                " and another with relative path " + catResFileName + ", unable to determine " +
                "which to update.");
        remoteFilesPluginService.addUrlsToCatalog(mockUser, session, catalogData, null, urlMap,
                true, null);
    }

    @Test
    @DirtiesContext
    public  void testPushFilesAndAddUrlsToCatalogException() throws Exception {
        String unsupportedProj = "badProj";
        Mockito.when(session.getProject()).thenReturn(unsupportedProj);
        exceptionRule.expect(UnsupportedRemoteFilesOperationException.class);
        exceptionRule.expectMessage("Project " + unsupportedProj + " not configured for filesystems plugin");
        remoteFilesPluginService.pushProcessingOutputsAndAddUrlsToCatalog(mockUser, session, catRes,
                testFileMap, false, null);
    }

    @Test
    @DirtiesContext
    public  void testPushFilesAndAddUrlsToCatalogException2() throws Exception {
        exceptionRule.expect(ClientException.class);
        exceptionRule.expectMessage("Nothing found to upload");
        remoteFilesPluginService.pushProcessingOutputsAndAddUrlsToCatalog(mockUser, session, catRes,
                Collections.emptyMap(), false, null);
    }

    @Test
    @DirtiesContext
    public  void testPushFilesAndAddUrlsToCatalogNoUploadProcessing() throws Exception {
        String project = projectArrayList.get(1);
        Mockito.when(session.getProject()).thenReturn(project);
        exceptionRule.expect(UnsupportedRemoteFilesOperationException.class);
        exceptionRule.expectMessage("Project " + project + " not configured to automatically " +
                "upload processing outputs to remote filesystem");
        remoteFilesPluginService.pushProcessingOutputsAndAddUrlsToCatalog(mockUser, session, catRes,
                testFileMap, false, null);
    }

    @Test
    @DirtiesContext
    public void testPushFilesAndAddUrls() throws Exception {
        Mockito.when(session.getProject()).thenReturn(supportedProject);
        XnatResourcecatalog newRes = Mockito.mock(XnatResourcecatalog.class);
        int id = 3;
        Mockito.when(newRes.getXnatAbstractresourceId()).thenReturn(id);
        Mockito.when(newRes.getLabel()).thenReturn("NEW");
        File catFile = Paths.get(mockSesArchivePath, "RESOURCES",
                newRes.getLabel(), newRes.getLabel() + "_catalog.xml").toFile();
        Mockito.when(newRes.getUri()).thenReturn(catFile.getAbsolutePath());
        FileUtils.deleteDirectory(catFile.getParentFile());

        remoteFilesPluginService.pushProcessingOutputsAndAddUrlsToCatalog(mockUser, session, newRes, testFileMap,
                true, null);
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, newRes, supportedProject);
        assertThat(catalogData.catBean.getEntries_entry(), hasSize(1));
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        String name = testFile.getName();
        assertThat(catalogMap.containsKey(name), is(true));

        String awsTestFilePushUrl = "s3://" + fakeBucketName + "/" +
                Paths.get(testArchiveDir).relativize(catFile.getParentFile().toPath()).resolve(name);
        assertThat(catalogMap.get(name).entry.getUri(), is(awsTestFilePushUrl));
        FileUtils.deleteDirectory(catFile.getParentFile());
    }

    @Test
    @DirtiesContext
    public void testPushFilesAndAddUrlsDir() throws Exception {
        Mockito.when(session.getProject()).thenReturn(supportedProject);
        XnatResourcecatalog newRes = Mockito.mock(XnatResourcecatalog.class);
        int id = 3;
        Mockito.when(newRes.getXnatAbstractresourceId()).thenReturn(id);
        Mockito.when(newRes.getLabel()).thenReturn("NEW");
        File catFile = Paths.get(mockSesArchivePath, "RESOURCES",
                newRes.getLabel(), newRes.getLabel() + "_catalog.xml").toFile();
        Mockito.when(newRes.getUri()).thenReturn(catFile.getAbsolutePath());
        FileUtils.deleteDirectory(catFile.getParentFile());

        File parent = testFile.getParentFile();
        final Map<String, FileSystemResource> inputs = ImmutableMap.of(parent.getName(),
                new FileSystemResource(parent));
        remoteFilesPluginService.pushProcessingOutputsAndAddUrlsToCatalog(mockUser, session, newRes, inputs,
                true, null);
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, newRes, supportedProject);
        assertThat(catalogData.catBean.getEntries_entry(), hasSize(2));
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(catalogMap.containsKey(testFileName), is(true));
        assertThat(catalogMap.containsKey(awsGoodUrlFilePath), is(true));

        String awsTestFilePushUrl = "s3://" + fakeBucketName + "/" +
                Paths.get(testArchiveDir).relativize(catFile.getParentFile().toPath()).resolve(testFileName);
        assertThat(catalogMap.get(testFileName).entry.getUri(), is(awsTestFilePushUrl));
        assertThat(catalogMap.get(awsGoodUrlFilePath).entry.getUri(), is(not(awsGoodUrl))); // make sure we mod it bc the other exists, content of mod is tested in makeUri method
        FileUtils.deleteDirectory(catFile.getParentFile());
    }

    @Test
    @DirtiesContext
    public void testPushFilesAndAddUrlsDir2() throws Exception {
        Mockito.when(session.getProject()).thenReturn(supportedProject);
        XnatResourcecatalog newRes = Mockito.mock(XnatResourcecatalog.class);
        int id = 3;
        Mockito.when(newRes.getXnatAbstractresourceId()).thenReturn(id);
        Mockito.when(newRes.getLabel()).thenReturn("NEW");
        File catFile = Paths.get(mockSesArchivePath, "RESOURCES",
                newRes.getLabel(), newRes.getLabel() + "_catalog.xml").toFile();
        Mockito.when(newRes.getUri()).thenReturn(catFile.getAbsolutePath());
        FileUtils.deleteDirectory(catFile.getParentFile());

        File parent = testFile.getParentFile();
        final Map<String, FileSystemResource> inputs = ImmutableMap.of(parent.getName(),
                new FileSystemResource(parent));
        remoteFilesPluginService.pushProcessingOutputsAndAddUrlsToCatalog(mockUser, session, newRes, inputs,
                false, null);
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, newRes, supportedProject);
        assertThat(catalogData.catBean.getEntries_entry(), hasSize(2));
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        String name = testFile.getName();
        assertThat(catalogMap.containsKey(name), is(true));
        assertThat(catalogMap.containsKey(awsGoodUrlFileName), is(true));

        String awsTestFilePushUrl = "s3://" + fakeBucketName + "/" +
                Paths.get(testArchiveDir).relativize(catFile.getParentFile().toPath()).resolve(name);
        assertThat(catalogMap.get(name).entry.getUri(), is(awsTestFilePushUrl));
        assertThat(catalogMap.get(awsGoodUrlFileName).entry.getUri(), is(not(awsGoodUrl))); // make sure we mod it bc the other exists, content of mod is tested in makeUri method
        FileUtils.deleteDirectory(catFile.getParentFile());
    }

    @Test
    @DirtiesContext
    public void testPushFilesAndAddUrlsDirException() throws Exception {
        Mockito.when(session.getProject()).thenReturn(supportedProject);
        XnatResourcecatalog newRes = Mockito.mock(XnatResourcecatalog.class);
        int id = 3;
        Mockito.when(newRes.getXnatAbstractresourceId()).thenReturn(id);
        Mockito.when(newRes.getLabel()).thenReturn("NEW");
        File catFile = Paths.get(mockSesArchivePath, "RESOURCES",
                newRes.getLabel(), newRes.getLabel() + "_catalog.xml").toFile();
        Mockito.when(newRes.getUri()).thenReturn(catFile.getAbsolutePath());

        File emptyDir = Paths.get(mockSesArchivePath,"emptydir").toFile();
        Files.createDirectories(emptyDir.toPath());
        final Map<String, FileSystemResource> inputs = ImmutableMap.of(emptyDir.getName(),
                new FileSystemResource(emptyDir));
        exceptionRule.expect(ClientException.class);
        exceptionRule.expectMessage("No files found to upload");
        remoteFilesPluginService.pushProcessingOutputsAndAddUrlsToCatalog(mockUser, session, newRes, inputs,
                false, null);
    }

    @Test
    @DirtiesContext
    public void testPushItem() throws Exception {
        int cleanupInterval = 1;
        Map<String, File> expdResFiles = new HashMap<>();
        Map<String, File> notExpdResFiles = new HashMap<>();
        File catFile = setUpForPush(expdResFiles, notExpdResFiles, cleanupInterval);
        remoteFilesPluginService.pushItem(session, Collections.singletonList(localRes), mockUser, awsS3FilesystemService,
                cleanupInterval);

        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, localRes, supportedProject);
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(remoteFilesTrackerEntityService.findByResource(localRes).getStatus(),
                is(RemoteFilesResourceStatus.Archived));
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(localRes), is(true));
        String catPathRel = Paths.get(siteConfigPreferences.getArchivePath()).relativize(Paths.get(catalogData.catPath))
                .toString();
        for (String relPath : expdResFiles.keySet()) {
            assertFilePushed(catalogMap, catPathRel, relPath, expdResFiles.get(relPath));
        }
        for (String relPath : notExpdResFiles.keySet()) {
            assertNotPushed(catalogMap, relPath, notExpdResFiles.get(relPath));
        }
    }

    @Test
    @DirtiesContext
    public void testPushItemSkipsIfRecentlyCheckedForLocality() throws Exception {
        int cleanupInterval = 2;
        Map<String, File> expdResFiles = new HashMap<>();
        Map<String, File> notExpdResFiles = new HashMap<>();
        File catFile = setUpForPush(expdResFiles, notExpdResFiles, cleanupInterval);

        // this catalogHasRemoteFiles check should prevent any pushing, but only if the localRes has a remote entity
        fakeArchiveResource(localRes);
        fakePullResource(localRes);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(localRes), is(false));
        remoteFilesPluginService.pushItem(session, Collections.singletonList(localRes), mockUser, awsS3FilesystemService,
                cleanupInterval);

        // confirm nothing was pushed
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, localRes, supportedProject);
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);
        assertThat(remoteFilesPluginService.catalogHasRemoteFiles(localRes), is(false));
        for (String relPath : expdResFiles.keySet()) {
            assertNotPushed(catalogMap, relPath, expdResFiles.get(relPath));
        }
        for (String relPath : notExpdResFiles.keySet()) {
            assertNotPushed(catalogMap, relPath, notExpdResFiles.get(relPath));
        }
    }

    /**
     * Because supportedProject archives to <em>fakeBucketName</em> but the catalog contains a URL within
     * <em>altBucketName</em>, the push process will upload the file to fakeBucketName and update the URL accordingly.
     * It should also store the original URL in a metafield for provenance.
     *
     * @throws Exception if exception is thrown, test is failing
     */
    @Test
    @DirtiesContext
    public void testPushItemUpdatesButStoresPreviousUrl() throws Exception {
        int cleanupInterval = 1;
        fakeArchiveResource(catRes);

        // Copy files and update uris
        copyFilesToWritableLocAndUpdateUris(mockSesArchivePathExpected, mockSesUriObj.getResources(false));
        Mockito.when(siteConfigPreferences.getArchivePath()).thenReturn(writableArchivePath);

        // set access time
        File catFile = new File(catRes.getUri());
        CatalogUtils.CatalogData catalogDataOrig = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        FileTime expdTime = FileTime.fromMillis(ZonedDateTime.now().minusDays(cleanupInterval+1).toInstant()
                .toEpochMilli());
        for (CatEntryI entry : catalogDataOrig.catBean.getEntries_entry()) {
            String relPath = CatalogUtils.getRelativePathForCatalogEntry(entry, catalogDataOrig.catPath);
            Path outLoc = Paths.get(catalogDataOrig.catPath, relPath);
            File resFile = outLoc.toFile();
            assertThat(resFile, anExistingFile());
            // set access time to be expired
            Files.setAttribute(outLoc, "basic:lastAccessTime", expdTime, NOFOLLOW_LINKS);
        }
        remoteFilesPluginService.pushItem(session, Collections.singletonList(catRes), mockUser, awsS3FilesystemService,
                cleanupInterval);
        CatalogUtils.CatalogData catalogDataNew = new CatalogUtils.CatalogData(catFile, catRes, supportedProject);
        Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogDataNew);
        for (CatEntryI entry : catalogDataOrig.catBean.getEntries_entry()) {
            String relPath = CatalogUtils.getRelativePathForCatalogEntry(entry, catalogDataOrig.catPath);
            Path outLoc = Paths.get(catalogDataOrig.catPath, relPath);
            File resFile = outLoc.toFile();
            // file removed
            assertThat(resFile, not(anExistingFile()));
            // entry still in catalog
            CatalogUtils.CatalogMapEntry mapEntry = catalogMap.get(relPath);
            assertNotNull(mapEntry);

            String prevUri = entry.getUri();
            // with prev uri stored
            CatEntryMetafieldI mf = CatalogUtils.getMetaFieldByName(mapEntry.entry, CatalogUtils.ORIG_URI);
            assertThat(mf, notNullValue());
            assertThat(mf.getMetafield(), is(prevUri));
            // and an updated uri
            assertThat(mapEntry.entry.getUri(), is(prevUri.replace("s3://" + altBucketName,
                    "s3://" + fakeBucketName)));
        }
    }

    private void assertFilePushed(Map<String, CatalogUtils.CatalogMapEntry> catalogMap,
                                  String catPathRel,
                                  String relPath,
                                  File file) {
        assertThat(catalogMap.containsKey(relPath), is(true));
        assertThat(catalogMap.get(relPath).entry.getUri(), is("s3://" + fakeBucketName + "/" +
                catPathRel + "/" + relPath));
        assertThat(file, not(anExistingFile()));
    }

    private void assertNotPushed(Map<String, CatalogUtils.CatalogMapEntry> catalogMap, String relPath, File file) {
        assertThat(catalogMap.containsKey(relPath), is(true));
        assertThat(catalogMap.get(relPath).entry.getUri(), is(relPath));
        assertThat(file, anExistingFile());
    }

    private File setUpForPush(Map<String, File> expdResFiles, Map<String, File> notExpdResFiles,
                              @Nullable Integer cleanupInterval)
            throws Exception {
        cleanupInterval = cleanupInterval == null ? 1 : cleanupInterval;
        String expectedCatPath = Paths.get(localRes.getUri()).getParent().toString();
        File catFile = getWritableCatalog(localRes);
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catFile, localRes, supportedProject);
        Files.createDirectories(Paths.get(catalogData.catPath));
        FileTime expdTime = FileTime.fromMillis(ZonedDateTime.now().minusDays(cleanupInterval+1).toInstant()
                .toEpochMilli());

        for (CatEntryI entry : catalogData.catBean.getEntries_entry()) {
            // copy files into archive
            String relPath = CatalogUtils.getRelativePathForCatalogEntry(entry, catalogData.catPath);
            assertThat(entry.getUri(), is(relPath)); // assert that it's local
            Path outLoc = Paths.get(catalogData.catPath, relPath);
            Files.createDirectories(outLoc.getParent());
            Files.copy(Paths.get(expectedCatPath, relPath), outLoc);

            File resFile = outLoc.toFile();
            assertThat(resFile, anExistingFile());
            if (relPath.contains("notExpired")) {
                Files.setAttribute(outLoc, "basic:lastAccessTime",
                        FileTime.fromMillis(System.currentTimeMillis()), NOFOLLOW_LINKS);
                notExpdResFiles.put(relPath, resFile);
            } else {
                // set their access time to be expired
                Files.setAttribute(outLoc, "basic:lastAccessTime", expdTime, NOFOLLOW_LINKS);
                expdResFiles.put(relPath, resFile);
            }
        }
        return catFile;
    }

    private File getWritableCatalog(XnatResourcecatalog res) throws IOException {
        File catFile = new File(res.getUri().replace(siteConfigPreferences.getArchivePath(), writableArchivePath));
        Files.createDirectories(catFile.getParentFile().toPath());
        Files.copy(Paths.get(res.getUri()), catFile.toPath());
        Mockito.when(res.getUri()).thenReturn(catFile.getAbsolutePath());
        return catFile;
    }

    private File getWritableCatalog() throws IOException {
        return getWritableCatalog(catRes);
    }

    private void pullItemResourceSingle() throws Exception {
        // Now pull them
        String archivePath = Paths.get(catRes.getUri()).getParent().toString();
        String outPath = archivePath.replace(siteConfigPreferences.getArchivePath(), writableArchivePath);
        catalogService.pullResourceCatalogsToDestination(mockUser, mockCatResUri, archivePath, outPath);
        checkFiles(archivePath, writableArchivePath);

        // Test that having just pulled ensures we can't push
        assertThat(remoteFilesPluginServiceImpl.startPush(session, catRes, mockUser, 1, new MutableBoolean(false)),
                is(false));
    }

    private void checkFiles(final String expectedPath, final String actualPathBase) throws Exception {
        checkFiles(expectedPath, actualPathBase, true);
    }

    private void checkFiles(final String archivePathUsed, final String actualPathBase, boolean pulled) throws Exception {
        String sesPathUsed = session.getExpectedCurrentDirectory().toString();
        String expectedPath = archivePathUsed.replace(sesPathUsed, mockSesArchivePathExpected);
        Path archivePath = Paths.get(siteConfigPreferences.getArchivePath());
        Files.walkFileTree(Paths.get(expectedPath), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (CatalogUtils.isCatalogFile(file.toFile())) {
                    return FileVisitResult.CONTINUE;
                }
                // Account for path change
                Path filePath = Paths.get(file.toString().replace(mockSesArchivePathExpected, sesPathUsed));
                File corrFile = Paths.get(actualPathBase).resolve(archivePath.relativize(filePath)).toFile();
                assertThat("File " + corrFile.toString() + " doesn't exist", corrFile, anExistingFile());
                if (pulled && !file.toAbsolutePath().toString().contains("/" + localResLabel + "/")) {
                    // Our mock S3 actually just copies the same file over and over
                    assertTrue("File content doesn't match expected",
                            FileUtils.contentEquals(corrFile, awsGoodUrlFile));
                } else {
                    assertTrue("File content doesn't match expected",
                            FileUtils.contentEquals(corrFile, file.toFile()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyFilesToWritableLocAndUpdateUris(String originLoc, List<XnatAbstractresourceI> resources)
            throws Exception {
        String writableSesArchivePath = session.getExpectedCurrentDirectory().getAbsolutePath()
                .replace(siteConfigPreferences.getArchivePath(), writableArchivePath);
        updateResourceUris(writableSesArchivePath, resources);

        // copy files from originLoc to writableSesArchivePath
        // Use originLoc=mockSesArchivePath to copy local files and catalog files;
        // Use originLoc=mockSesArchivePathExpected to copy all files
        Files.walkFileTree(Paths.get(originLoc), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path fl = Paths.get(writableSesArchivePath).resolve(Paths.get(originLoc).relativize(file));
                Files.createDirectories(fl.getParent());
                Files.copy(file, fl);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void updateResourceUris(String newSesArchivePath, List<XnatAbstractresourceI> resources) throws Exception {
        Mockito.when(session.getExpectedCurrentDirectory()).thenReturn(new File(newSesArchivePath));
        for (XnatAbstractresourceI res : resources) {
            if (res instanceof XnatResourcecatalog) {
                XnatResourcecatalog cr = (XnatResourcecatalog) res;
                String newUri = cr.getUri().replace(mockSesArchivePath, newSesArchivePath);
                Mockito.when(cr.getUri()).thenReturn(newUri);
            }
        }
    }

    private void fakeArchiveResources(List<XnatAbstractresourceI> resources) {
        for (XnatAbstractresourceI r : resources) {
            if (r.getLabel().equals(localResLabel)) {
                // don't mark as archived
                continue;
            }
            fakeArchiveResource(r);
        }
    }

    private void fakeArchiveResource(XnatAbstractresourceI r) {
        if (!(r instanceof XnatResourcecatalog)) {
            return;
        }
        XnatResourcecatalog res = (XnatResourcecatalog) r;
        MutableBoolean firstPush = new MutableBoolean(false);
        assertThat(remoteFilesPluginServiceImpl.startPush(session, res, mockUser, 1, firstPush),
                is(true));
        assertTrue("First push should be true", firstPush.booleanValue());
        remoteFilesPluginServiceImpl.completePush(session, res, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(res).getStatus(),
                is(RemoteFilesResourceStatus.Archived));
    }

    private void fakePullResources(List<XnatAbstractresourceI> resources) {
        for (XnatAbstractresourceI r : resources) {
            if (r.getLabel().equals(localResLabel)) {
                // don't pull
                continue;
            }
            fakePullResource(r);
        }
    }

    private void fakePullResource(XnatAbstractresourceI r) {
        if (!(r instanceof XnatResourcecatalog)) {
            return;
        }
        XnatResourcecatalog res = (XnatResourcecatalog) r;
        assertThat(remoteFilesPluginServiceImpl.startPull(session, res, false), is(true));
        remoteFilesPluginServiceImpl.completePull(session, res, true);
        assertThat(remoteFilesTrackerEntityService.findByResource(res).getStatus(),
                is(RemoteFilesResourceStatus.Local));
    }
}
