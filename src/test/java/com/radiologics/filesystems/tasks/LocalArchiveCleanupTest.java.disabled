// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.tasks;


import com.radiologics.filesystems.aws.s3.services.AwsS3FilesystemService;
import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.services.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.DatabaseHelper;
import org.nrg.framework.task.services.XnatTaskService;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.*;
import org.nrg.xdat.om.base.BaseXnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xft.ItemI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.task.AbstractXnatTask;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.radiologics.filesystems.config.SharedStrings.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;

@Slf4j
@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*", "org.xml.sax.*"})
@PrepareForTest({UriParserUtils.class, AbstractXnatTask.class, DatabaseHelper.class, BaseXnatProjectdata.class,
        AutoXnatProjectdata.class})
@ContextConfiguration(classes = {TestConfig.class})
public class LocalArchiveCleanupTest {
    @Autowired private XnatUserProvider primaryAdminUserProvider;
    @Autowired private PermissionsServiceI mockPermissionsService;

    @Mock private XnatTaskService taskService;
    @Mock private XnatAppInfo appInfo;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RemoteFilesPluginService mockRemoteFilesService;
    @Mock private AwsS3FilesystemService mockAwsS3FilesystemService;
    @Mock private DefaultFilesystemService mockDefaultFilesystemService;
    @Mock private ProjectFilesystemSettingsService mockProjectFilesystemSettingsService;
    @Mock private CatalogService mockCatalogService;
    @Mock private ResourceData mockResourceData;
    @Mock private UserI mockUser;
    @Mock private XnatProjectdata project;
    @Mock private XnatMrsessiondata session;
    @Mock private XnatSubjectdata subject;
    @Mock private URIManager.ArchiveItemURI itemURIObj;
    @Mock private ProjectFilesystemSettings mockSettings;

    private String uri = "uri";
    @Mock private List<XnatAbstractresourceI> resources;
    private List<FilesystemService> filesystemServices;
    private int cleanupInterval = 1;


    @Before
    public void setup() throws Exception {
        Mockito.when(mockUser.getLogin()).thenReturn("mockUser");
        Mockito.when(primaryAdminUserProvider.get()).thenReturn(mockUser);

        // Permissions
        Mockito.when(mockPermissionsService.can(any(UserI.class), any(ItemI.class), anyString()))
                .thenReturn(Boolean.TRUE);

        DatabaseHelper databaseHelper = Mockito.mock(DatabaseHelper.class);
        PowerMockito.whenNew(DatabaseHelper.class).withParameterTypes(JdbcTemplate.class)
                .withArguments(any(JdbcTemplate.class)).thenReturn(databaseHelper);

        // XNAT objects
        PowerMockito.mockStatic(UriParserUtils.class);
        PowerMockito.when(UriParserUtils.getArchiveUri(project)).thenReturn(uri);
        PowerMockito.when(UriParserUtils.getArchiveUri(subject)).thenReturn(uri);
        PowerMockito.when(UriParserUtils.getArchiveUri(session)).thenReturn(uri);
        Mockito.when(mockCatalogService.getResourceDataFromUri(uri)).thenReturn(mockResourceData);
        Mockito.when(mockResourceData.getXnatUri()).thenReturn(itemURIObj);
        Mockito.when(itemURIObj.getResources(true)).thenReturn(resources);

        Mockito.when(project.getId()).thenReturn(supportedProject);
        ArrayList<XnatSubjectdata> sub = new ArrayList<>();
        sub.add(subject);
        Mockito.when(project.getParticipants_participant()).thenReturn(sub);
        ArrayList<XnatExperimentdata> exp = new ArrayList<>();
        exp.add(session);
        Mockito.when(project.getExperiments()).thenReturn(exp);
        PowerMockito.mockStatic(BaseXnatProjectdata.class);
        PowerMockito.doReturn(project).when(BaseXnatProjectdata.class, "getProjectByIDorAlias",
                eq(supportedProject), any(UserI.class), anyBoolean());

        filesystemServices = Arrays.asList(mockAwsS3FilesystemService, mockDefaultFilesystemService);

        Mockito.when(mockSettings.getCleanupInterval()).thenReturn(cleanupInterval);
        Mockito.when(mockProjectFilesystemSettingsService.getArchiverFilesystemForProject(project.getId(), false))
                .thenReturn(mockAwsS3FilesystemService);

        Mockito.when(mockProjectFilesystemSettingsService.getSettingsForProject(supportedProject))
                .thenReturn(mockSettings);

        PowerMockito.mockStatic(AutoXnatProjectdata.class);
        ArrayList<XnatProjectdata> projectList = new ArrayList<>();
        projectList.add(project);
        PowerMockito.doReturn(projectList).when(AutoXnatProjectdata.class,
                "getAllXnatProjectdatas", any(UserI.class), anyBoolean());
    }

    @Test
    @DirtiesContext
    public void testCleanupNoFilesystems() throws Exception {
        Mockito.when(mockProjectFilesystemSettingsService.getSettingsForProject(any(String.class)))
                .thenThrow(new NotFoundException("Not found"));
        LocalArchiveCleanup lac = new LocalArchiveCleanup(primaryAdminUserProvider, mockRemoteFilesService,
                mockProjectFilesystemSettingsService, mockCatalogService, taskService, appInfo, jdbcTemplate);
        lac.runTask();
        Mockito.verify(mockRemoteFilesService, Mockito.never()).pushItem(any(ArchivableItem.class),
                anyListOf(XnatAbstractresourceI.class), any(UserI.class), any(FilesystemService.class), anyInt());
    }

    @Test
    public void testCleanup() {
        LocalArchiveCleanup lac = new LocalArchiveCleanup(primaryAdminUserProvider, mockRemoteFilesService,
                mockProjectFilesystemSettingsService, mockCatalogService, taskService, appInfo, jdbcTemplate);
        lac.runTask();

        // ensure called once per type (project, subject, session)
        Mockito.verify(mockRemoteFilesService, Mockito.times(3)).pushItem(any(ArchivableItem.class),
                anyListOf(XnatAbstractresourceI.class), any(UserI.class), any(FilesystemService.class), anyInt());
        // ensure called only once with these exact params
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).pushItem(project,
                resources, mockUser, mockAwsS3FilesystemService, cleanupInterval);
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).pushItem(subject,
                resources, mockUser, mockAwsS3FilesystemService, cleanupInterval);
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).pushItem(session,
                resources, mockUser, mockAwsS3FilesystemService, cleanupInterval);
    }

}
