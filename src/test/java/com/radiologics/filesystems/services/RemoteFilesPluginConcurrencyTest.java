// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.nrg.framework.node.XnatNode;
import org.nrg.xdat.om.XnatMrsessiondata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.auto.AutoXnatAbstractresource;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.node.services.XnatNodeInfoService;
import org.nrg.xnat.node.services.impl.HibernateXnatNodeInfoService;
import org.nrg.xnat.utils.CatalogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.radiologics.filesystems.config.SharedStrings.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.eq;


@RunWith(SpringJUnit4ClassRunner.class)
@Slf4j
@ContextConfiguration(classes = {TestConfig.class})
public class RemoteFilesPluginConcurrencyTest {
    @Autowired private HibernateXnatNodeInfoService xnatNodeInfoService;
    @Autowired private SiteConfigPreferences siteConfigPreferences;
    @Autowired private RemoteFilesTrackerEntityService remoteFilesTrackerEntityService;

    @Mock ThreadPoolExecutorFactoryBean threadPoolExecutorFactoryBean;
    @Mock XnatNodeInfoService nodeInfoService;
    @Mock private ProjectFilesystemSettingsService projectFilesystemSettingsService;

    private List<FilesystemService> filesystemServices = new ArrayList<>();
    private UserI mockUser;
    private XnatMrsessiondata session;
    private XnatResourcecatalog catRes;

    private int threads = 10;
    private List<XnatNode> nodes = new ArrayList<>();

    // Mockito 5 static mocks
    private MockedStatic<EventUtils> mockedEventUtils;
    private MockedStatic<PersistentWorkflowUtils> mockedPersistentWorkflowUtils;
    private MockedStatic<UriParserUtils> mockedUriParserUtils;

    @Before
    public void setup() throws Exception {
        // Initialize @Mock fields
        MockitoAnnotations.openMocks(this);

        Files.createDirectories(Paths.get(writableArchivePath));

        mockUser = Mockito.mock(UserI.class);
        Mockito.when(mockUser.getLogin()).thenReturn("mockUser");

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
                eq(mockUser), anyString()))
                .thenReturn(Collections.emptyList());
        mockedPersistentWorkflowUtils.when(() -> PersistentWorkflowUtils.getOrCreateWorkflowData(
                anyInt(), eq(mockUser), any(XFTItem.class), any(EventDetails.class)))
                .thenReturn(mockWrk);
        mockedPersistentWorkflowUtils.when(() -> PersistentWorkflowUtils.buildOpenWorkflow(
                eq(mockUser), any(XFTItem.class), any(EventDetails.class)))
                .thenReturn(mockWrk);

        // URI parsing - Mock static method with Mockito 5
        mockedUriParserUtils = Mockito.mockStatic(UriParserUtils.class);

        //Mock objects
        session = Mockito.mock(XnatMrsessiondata.class);
        Mockito.when(session.getId()).thenReturn("LOCAL_E00001");
        Mockito.when(session.getXSIType()).thenReturn(XnatMrsessiondata.SCHEMA_ELEMENT_NAME);
        String mockSesArchivePath = Paths.get(siteConfigPreferences.getArchivePath(),
                supportedProject, "arc001", "ses1").toString();
        Mockito.when(session.getExpectedCurrentDirectory()).thenReturn(new File(mockSesArchivePath));
        Mockito.when(session.getArchiveRootPath()).thenReturn(testArchiveDir);
        //Mockito.when(session.getScans_scan()).thenReturn(Collections.singletonList(scan));

        catRes = Mockito.mock(XnatResourcecatalog.class);
        int catResId = 1;
        Mockito.when(catRes.getXnatAbstractresourceId()).thenReturn(catResId);
        Mockito.when(catRes.getLabel()).thenReturn("DEBUG_OUTPUT");
        String catResUri = Paths.get(mockSesArchivePath, "RESOURCES",
                catRes.getLabel(), catRes.getLabel() + "_catalog.xml").toString();
        Mockito.when(catRes.getUri()).thenReturn(catResUri);

        // create nodes
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, -90);
        Date init = c.getTime();
        for (int t = 0; t < threads; ++t) {
            final String threadNum = String.valueOf(t + 100);
            XnatNode xnatNode = Mockito.mock(XnatNode.class);
            Mockito.when(xnatNode.getNodeId()).thenReturn(threadNum);
            XnatNodeInfo xnatNodeInfo = new XnatNodeInfo(threadNum, "test", "ip", new Date());
            xnatNodeInfo.setIsActive(true);
            xnatNodeInfo.setLastCheckIn(new Date());
            xnatNodeInfo.setLastInitialized(init);
            xnatNodeInfoService.create(xnatNodeInfo);
            Mockito.when(nodeInfoService.getXnatNodeInfoByNodeIdAndHostname(eq(threadNum),
                    anyString())).thenReturn(xnatNodeInfo);
            nodes.add(t, xnatNode);
        }
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
    }

    @Test
    @DirtiesContext
    public void testConcurrentWithExisting() throws Exception {
        //fake archive to create an entity (bug doesn't occur without it bc hibernate chokes on the dupe foreign key)
        final RemoteFilesPluginServiceImpl remoteFilesServiceParent = new RemoteFilesPluginServiceImpl(
                remoteFilesTrackerEntityService, projectFilesystemSettingsService, filesystemServices,
                siteConfigPreferences, threadPoolExecutorFactoryBean, nodeInfoService, nodes.get(0));
        MutableBoolean firstPush = new MutableBoolean(false);
        Assert.assertThat(remoteFilesServiceParent.startPush(session, catRes, mockUser, 1, firstPush),
                is(true));
        Assert.assertTrue("First push should be true", firstPush.booleanValue());
        remoteFilesServiceParent.completePush(session, catRes, true);
        Assert.assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Archived));
        runConcurrently();
    }

    @Test
    @DirtiesContext
    public void testInSeriesWithExisting() {
        //fake archive to create an entity (bug doesn't occur without it bc hibernate chokes on the dupe foreign key)
        final RemoteFilesPluginServiceImpl remoteFilesServiceParent = new RemoteFilesPluginServiceImpl(
                remoteFilesTrackerEntityService, projectFilesystemSettingsService, filesystemServices,
                siteConfigPreferences, threadPoolExecutorFactoryBean, nodeInfoService, nodes.get(0));
        MutableBoolean firstPush = new MutableBoolean(false);
        Assert.assertThat(remoteFilesServiceParent.startPush(session, catRes, mockUser, 1, firstPush),
                is(true));
        Assert.assertTrue("First push should be true", firstPush.booleanValue());
        remoteFilesServiceParent.completePush(session, catRes, true);
        Assert.assertThat(remoteFilesTrackerEntityService.findByResource(catRes).getStatus(),
                is(RemoteFilesResourceStatus.Archived));
        runInSeries();
    }

    @Test
    @DirtiesContext
    public void testConcurrentWithNew() throws Exception {
        runConcurrently();
    }

    @Test
    @DirtiesContext
    public void testInSeriesWithNew() {
        runInSeries();
    }

    private void runConcurrently() throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(threads);
        Collection<Callable<Boolean>> tasks = new ArrayList<>(threads);
        for (int t = 0; t < threads; ++t) {
            final XnatNode xnatNode = nodes.get(t);
            tasks.add(() -> startPush(xnatNode));
        }

        List<Future<Boolean>> futures = service.invokeAll(tasks);
        int startedTask = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                startedTask++;
            }
        }
        service.shutdown();
        assertThat(startedTask, equalTo(1));
    }

    private void runInSeries() {
        int startedTask = 0;
        for (int t = 0; t < threads; ++t) {
            final XnatNode xnatNode = nodes.get(t);
            if (startPush(xnatNode)) {
                startedTask++;
            }
        }
        assertThat(startedTask, equalTo(1));
    }

    boolean startPush(XnatNode xnatNode) {
        final RemoteFilesPluginServiceImpl remoteFilesService = new RemoteFilesPluginServiceImpl(
                remoteFilesTrackerEntityService, projectFilesystemSettingsService, filesystemServices,
                siteConfigPreferences, threadPoolExecutorFactoryBean, nodeInfoService, xnatNode);
        return remoteFilesService.startPush(session, catRes, mockUser, 1, new MutableBoolean(false));
    }
}
