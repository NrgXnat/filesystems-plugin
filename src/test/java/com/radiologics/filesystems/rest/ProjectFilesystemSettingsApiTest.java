// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radiologics.filesystems.config.RestApiTestConfig;
import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.model.entity.RemoteFilesItemState;
import com.radiologics.filesystems.services.ProjectFilesystemSettingsService;
import com.radiologics.filesystems.services.RemoteCatalogService;
import com.radiologics.filesystems.services.RemoteFilesPluginService;
import org.hibernate.HibernateException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatAbstractresource;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.auto.AutoXnatAbstractresource;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static com.radiologics.filesystems.config.SharedStrings.supportedProject;
import static com.radiologics.filesystems.model.entity.RemoteFilesItemState.Status.Locked;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = {RestApiTestConfig.class})
public class ProjectFilesystemSettingsApiTest {
    private Authentication AUTH;
    private UserI nonAdmin;
    private MockMvc mockMvc;

    private final MediaType JSON = MediaType.APPLICATION_JSON;

    // Make some ProjectFilesystemSettings POJOS for serialization (can't easily serialize mocks)
    private final ProjectFilesystemSettings pojo1 = new ProjectFilesystemSettings(1, "project", 7, true, null);
    private final ProjectFilesystemSettings pojo2 = new ProjectFilesystemSettings(2, "project2", 3, false, null);

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Autowired private ProjectFilesystemSettingsService mockProjectFilesystemSettingsService;

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        // Mock the user
        // Rely on catalogService.getResourceDataFromUri for security control
        nonAdmin = mock(UserI.class);
        final String nonAdminUsername = "non-admin";
        final String nonAdminPassword = "non-admin-pass";
        when(nonAdmin.getLogin()).thenReturn(nonAdminUsername);
        when(nonAdmin.getPassword()).thenReturn(nonAdminPassword);
        when(mockRoleService.isSiteAdmin(nonAdmin)).thenReturn(false);
        when(mockUserManagementServiceI.getUser(nonAdminUsername)).thenReturn(nonAdmin);
        AUTH = new TestingAuthenticationToken(nonAdmin, nonAdminPassword);
    }

    @After
    public void validate() {
        validateMockitoUsage();
    }

    @Test
    public void testGetAll() throws Exception {
        final String path = "/project_filesystem_settings";
        final List<ProjectFilesystemSettings> projectFilesystemSettingsList = new ArrayList<>();
        projectFilesystemSettingsList.add(pojo1);
        projectFilesystemSettingsList.add(pojo2);

        Mockito.when(mockProjectFilesystemSettingsService.getPojoForAllProjects(nonAdmin)).thenReturn(projectFilesystemSettingsList);

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is(mapper.writeValueAsString(projectFilesystemSettingsList)));
    }

    @Test
    @DirtiesContext
    public void testGetAll500() throws Exception {
        final String path = "/project_filesystem_settings";

        Mockito.when(mockProjectFilesystemSettingsService.getPojoForAllProjects(nonAdmin))
                .thenThrow(new RuntimeException("Runtime exception"));

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().is5xxServerError());
    }

    @Test
    public void testUpdate() throws Exception {
        final String path = "/project_filesystem_settings/update";

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(pojo1)).contentType(JSON)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Success!"));
        Mockito.verify(mockProjectFilesystemSettingsService, Mockito.times(1))
                .createOrUpdateFromPojo(eq(pojo1));
    }

    @Test
    @DirtiesContext
    public void testUpdateInvalid() throws Exception {
        final String path = "/project_filesystem_settings/update";
        final String msg = "Unable to store entity";
        doThrow(new InvalidEntityException(msg))
                .when(mockProjectFilesystemSettingsService).createOrUpdateFromPojo(eq(pojo1));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(pojo1)).contentType(JSON)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is4xxClientError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is(msg));
    }

    @Test
    @DirtiesContext
    public void testUpdateNotFound() throws Exception {
        final String path = "/project_filesystem_settings/update";
        final String msg = "Could not find entity with ID " + pojo1.getId();
        doThrow(new NotFoundException(msg))
                .when(mockProjectFilesystemSettingsService).createOrUpdateFromPojo(eq(pojo1));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(pojo1)).contentType(JSON)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is4xxClientError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is(msg));
    }

    @Test
    @DirtiesContext
    public void testUpdateHibernateException() throws Exception {
        final String path = "/project_filesystem_settings/update";
        final String msg = "Hibernate exception";
        doThrow(new HibernateException(msg))
                .when(mockProjectFilesystemSettingsService).createOrUpdateFromPojo(eq(pojo1));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(pojo1)).contentType(JSON)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is4xxClientError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is(msg));
    }

    @Test
    @DirtiesContext
    public void testUpdate500() throws Exception {
        final String path = "/project_filesystem_settings/update";
        final String msg = "Runtime exception";
        doThrow(new RuntimeException(msg))
                .when(mockProjectFilesystemSettingsService).createOrUpdateFromPojo(eq(pojo1));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(pojo1)).contentType(JSON)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is5xxServerError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is(msg));
    }
}
