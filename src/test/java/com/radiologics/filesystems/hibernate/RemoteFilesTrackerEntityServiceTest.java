// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.hibernate;

import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceTask;
import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import com.radiologics.filesystems.services.RemoteFilesTrackerEntityService;
import com.radiologics.filesystems.services.RemoteFilesTrackerEntityServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nrg.xdat.om.XnatMrscandata;
import org.nrg.xdat.om.XnatMrsessiondata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.auto.AutoXnatAbstractresource;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.node.services.impl.HibernateXnatNodeInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {TestConfig.class})
public class RemoteFilesTrackerEntityServiceTest {
    @Autowired private SiteConfigPreferences siteConfigPreferences;
    @Autowired private HibernateXnatNodeInfoService xnatNodeInfoService;
    @Autowired private XnatNodeInfo xnatNodeInfo;
    @Autowired private RemoteFilesTrackerEntityService remoteFilesTrackerEntityService;
    @Autowired private XnatUserProvider primaryAdminUserProvider;   //mocked but has to be named this way

    private UserI mockUser;

    private XnatMrsessiondata session;
    private XnatMrscandata scan;
    private String mockSesArchivePath;
    private XnatResourcecatalog catRes;
    private XnatResourcecatalog scanCatRes;

    // Mockito 5 static mock
    private MockedStatic<AutoXnatAbstractresource> mockedAutoXnatAbstractresource;

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Transactional
    @Before
    public void setup() throws Exception {
        mockUser = Mockito.mock(UserI.class);
        Mockito.when(mockUser.getLogin()).thenReturn("mockUser");

        //Mock objects
        scan = Mockito.mock(XnatMrscandata.class);
        Mockito.when(scan.getId()).thenReturn("1");
        //Mockito.when(scan.getFile()).thenReturn(Collections.singletonList(scanCatRes));

        session = Mockito.mock(XnatMrsessiondata.class);
        Mockito.when(session.getId()).thenReturn("LOCAL_E00001");
        Mockito.when(session.getXSIType()).thenReturn("xnat:mrSessionData");
        mockSesArchivePath = Paths.get(siteConfigPreferences.getArchivePath(), "project", "arc001", "ses1")
                .toString();
        Mockito.when(session.getExpectedCurrentDirectory()).thenReturn(new File(mockSesArchivePath));
        //Mockito.when(session.getScans_scan()).thenReturn(Collections.singletonList(scan));

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

        // Mock resource check with Mockito 5
        Mockito.when(primaryAdminUserProvider.get()).thenReturn(mockUser);

        // Mock static method with Mockito 5
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

        // Need nodeinfo for association
        xnatNodeInfoService.create(xnatNodeInfo);
    }

    @After
    public void teardown() {
        if (mockedAutoXnatAbstractresource != null) {
            mockedAutoXnatAbstractresource.close();
        }
    }

    @Test
    @DirtiesContext
    public void testCreateRemoteFilesTrackerEntity() {
        remoteFilesTrackerEntityService.createRemoteFilesTrackerEntity(session, catRes, xnatNodeInfo,
                RemoteFilesResourceTask.Push, null);
        RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource(catRes);
        assertNotNull(entity);
        List<RemoteFilesTrackerEntity> entities = remoteFilesTrackerEntityService.findByItemIdAndXsiType(session);
        assertNotNull(entities);
        assertThat(entities, hasSize(1));
        assertThat(entities, containsInAnyOrder(entity));
        remoteFilesTrackerEntityService.delete(entity);
        // After deletion, findByItemIdAndXsiType returns empty list rather than null
        assertThat(remoteFilesTrackerEntityService.findByItemIdAndXsiType(session), hasSize(0));
        assertNull(remoteFilesTrackerEntityService.findByResource(catRes));
    }

    @Test
    @DirtiesContext
    public void testDeletedResource() {
        XnatResourcecatalog cat = Mockito.mock(XnatResourcecatalog.class);
        int id = 3;
        Mockito.when(cat.getXnatAbstractresourceId()).thenReturn(id);
        Mockito.when(cat.getLabel()).thenReturn("TMP");
        String uri = Paths.get(mockSesArchivePath, "RESOURCES",
                cat.getLabel(), cat.getLabel() + "_catalog.xml").toString();
        Mockito.when(cat.getUri()).thenReturn(uri);
        remoteFilesTrackerEntityService.createRemoteFilesTrackerEntity(session, cat, xnatNodeInfo,
                RemoteFilesResourceTask.Push, null);
        RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource(cat);
        assertNotNull(entity);
        List<RemoteFilesTrackerEntity> entities = remoteFilesTrackerEntityService.findByItemIdAndXsiType(session);
        // currently, a deleted resource will cause findByItemIdAndXsiType to return an empty list rather than null
        assertThat(entities, hasSize(0));
    }
}
