// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityService;
import com.radiologics.filesystems.aws.s3.services.AwsS3FilesystemService;
import com.radiologics.filesystems.config.RestApiTestConfig;
import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.exceptions.InvalidArchiverEntityException;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import org.hibernate.HibernateException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xdat.om.base.auto.AutoXnatAbstractresource;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({Permissions.class, AutoXnatAbstractresource.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@WebAppConfiguration
@ContextConfiguration(classes = {RestApiTestConfig.class})
public class AwsS3ConfigApiTest {
    private Authentication AUTH;
    private UserI nonAdmin;
    private MockMvc mockMvc;

    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Autowired private AwsS3FilesystemService mockAwsS3FilesystemService;
    @Autowired private AwsS3ConfigEntityService mockAwsS3ConfigEntityService;

    // Make some AwsS3Config POJOS for serialization (can't easily serialize mocks)
    private final AwsS3Config config1 = new AwsS3Config(1, "bucket", "access", "secret",
            "subdir", "name", true, true, false, null);
    private final AwsS3Config config2 = new AwsS3Config(2, "bucket2", null, null,
            null, "name2", true, true, true, null);

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
    @DirtiesContext
    public void testGetAll() throws Exception {
        final String path = "/filesystems/aws_s3/config";
        final List<AwsS3Config> awsS3ConfigList = new ArrayList<>();
        awsS3ConfigList.add(config1);
        awsS3ConfigList.add(config2);

        Mockito.when(mockAwsS3ConfigEntityService.getPojoForAllConfigs()).thenReturn(awsS3ConfigList);

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

        assertThat(response, is(mapper.writerFor(new TypeReference<List<AwsS3Config>>() {} )
                .writeValueAsString(awsS3ConfigList)));
    }

    @Test
    @DirtiesContext
    public void testGetAll500() throws Exception {
        final String path = "/filesystems/aws_s3/config";

        Mockito.when(mockAwsS3ConfigEntityService.getPojoForAllConfigs())
                .thenThrow(new RuntimeException("Runtime exception"));

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DirtiesContext
    public void testUpdate() throws Exception {
        final String path = "/filesystems/aws_s3/config/update";

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(config1)).contentType(JSON)
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
        Mockito.verify(mockAwsS3ConfigEntityService, Mockito.times(1))
                .createOrUpdateFromPojo(config1, false, mockAwsS3FilesystemService);
    }

    @Test
    @DirtiesContext
    public void testUpdateInvalid() throws Exception {
        final String path = "/filesystems/aws_s3/config/update";
        final String msg = "Unable to store entity";

        Mockito.when(mockAwsS3ConfigEntityService.createOrUpdateFromPojo(config1, false,
                        mockAwsS3FilesystemService)).thenThrow(new InvalidEntityException(msg));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(config1)).contentType(JSON)
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
        final String path = "/filesystems/aws_s3/config/update";
        final String msg = "Could not find entity with ID " + config1.getId();

        Mockito.when(mockAwsS3ConfigEntityService.createOrUpdateFromPojo(config1, false,
                mockAwsS3FilesystemService)).thenThrow(new NotFoundException(msg));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(config1)).contentType(JSON)
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
        final String path = "/filesystems/aws_s3/config/update";
        final String msg = "Hibernate exception";

        Mockito.when(mockAwsS3ConfigEntityService.createOrUpdateFromPojo(config1, false,
                mockAwsS3FilesystemService)).thenThrow(new HibernateException(msg));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(config1)).contentType(JSON)
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
        final String path = "/filesystems/aws_s3/config/update";
        final String msg = "Runtime exception";

        Mockito.when(mockAwsS3ConfigEntityService.createOrUpdateFromPojo(config1, false,
                        mockAwsS3FilesystemService)).thenThrow(new RuntimeException(msg));

        final MockHttpServletRequestBuilder request = put(path)
                .content(mapper.writeValueAsString(config1)).contentType(JSON)
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

    @Test
    @DirtiesContext
    public void testDelete() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id;

        final MockHttpServletRequestBuilder request = delete(path)
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
        Mockito.verify(mockAwsS3ConfigEntityService, Mockito.times(1))
                .deleteById(id, mockAwsS3FilesystemService);
    }

    @Test
    @DirtiesContext
    public void testDelete500() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id;
        final String msg = "Runtime exception";
        doThrow(new RuntimeException(msg))
                .when(mockAwsS3ConfigEntityService).deleteById(id, mockAwsS3FilesystemService);

        final MockHttpServletRequestBuilder request = delete(path)
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

    @Test
    @DirtiesContext
    public void testDeleteHibernateException() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id;
        final String msg = "Hibernate exception";
        doThrow(new HibernateException(msg))
                .when(mockAwsS3ConfigEntityService).deleteById(id, mockAwsS3FilesystemService);

        final MockHttpServletRequestBuilder request = delete(path)
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
    public void testUpdatePermittedProjects() throws Exception {
        final boolean permitted = true;
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/permitted_projects";

        final String p1 = "p1";
        final String p2 = "p2";
        final List<String> projects = Arrays.asList(p1, p2);

        final MockHttpServletRequestBuilder request = post(path)
                .param("permitted", Boolean.toString(permitted))
                .param("projects[]", p1)
                .param("projects[]", p2)
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
        Mockito.verify(mockAwsS3ConfigEntityService, Mockito.times(1))
                .updatePermittedProjects(id, permitted, projects, mockAwsS3FilesystemService);
    }

    @Test
    @DirtiesContext
    public void testUpdatePermittedProjectsNull() throws Exception {
        final boolean permitted = true;
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/permitted_projects";

        final MockHttpServletRequestBuilder request = post(path)
                .param("permitted", Boolean.toString(permitted))
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
        Mockito.verify(mockAwsS3ConfigEntityService, Mockito.times(1))
                .updatePermittedProjects(id, permitted, null, mockAwsS3FilesystemService);
    }

    @Test
    @DirtiesContext
    public void testUpdatePermittedProjectsNotFound() throws Exception {
        final boolean permitted = true;
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/permitted_projects";

        final String msg = "Could not find entity with ID " + id;

        Mockito.when(mockAwsS3ConfigEntityService.updatePermittedProjects(id, permitted, null,
                mockAwsS3FilesystemService)).thenThrow(new NotFoundException(msg));

        final MockHttpServletRequestBuilder request = post(path)
                .param("permitted", Boolean.toString(permitted))
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
    public void testUpdatePermittedProjectsInvalid() throws Exception {
        final boolean permitted = true;
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/permitted_projects";

        final String msg = "Invalid";

        Mockito.when(mockAwsS3ConfigEntityService.updatePermittedProjects(id, permitted, null,
                mockAwsS3FilesystemService)).thenThrow(new InvalidEntityException(msg));

        final MockHttpServletRequestBuilder request = post(path)
                .param("permitted", Boolean.toString(permitted))
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
    public void testUpdatePermittedProjectsHibernateException() throws Exception {
        final boolean permitted = true;
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/permitted_projects";

        final String msg = "Hibernate";

        Mockito.when(mockAwsS3ConfigEntityService.updatePermittedProjects(id, permitted, null,
                mockAwsS3FilesystemService)).thenThrow(new HibernateException(msg));

        final MockHttpServletRequestBuilder request = post(path)
                .param("permitted", Boolean.toString(permitted))
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
    public void testRefresh() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/refresh";
        final MockHttpServletRequestBuilder request = get(path)
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
        Mockito.verify(mockAwsS3ConfigEntityService, Mockito.times(1))
                .refresh(id, mockAwsS3FilesystemService);
    }

    @Test
    @DirtiesContext
    public void testRefresh500() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/refresh";

        final String msg = "Runtime";

        Mockito.doThrow(new RuntimeException(msg)).when(mockAwsS3ConfigEntityService)
                .refresh(id, mockAwsS3FilesystemService);

        final MockHttpServletRequestBuilder request = get(path)
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

    @Test
    @DirtiesContext
    public void testRefreshInvalidArchiver() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/refresh";

        final String msg = "Invalid";

        Mockito.doThrow(new InvalidArchiverEntityException(msg)).when(mockAwsS3ConfigEntityService)
                .refresh(id, mockAwsS3FilesystemService);

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is(HttpStatus.PARTIAL_CONTENT.value()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Warning: " + msg));
    }

    @Test
    @DirtiesContext
    public void testRefreshInactive() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/refresh";

        final String msg = "Inactive";

        Mockito.doThrow(new InvalidEntityException(msg)).when(mockAwsS3ConfigEntityService)
                .refresh(id, mockAwsS3FilesystemService);

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is(HttpStatus.PARTIAL_CONTENT.value()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Warning: " + msg));
    }

    @Test
    @DirtiesContext
    public void testRefreshInvalid() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/refresh";

        final String msg = "Invalid";

        Mockito.doThrow(new InvalidEntityException(msg)).when(mockAwsS3ConfigEntityService)
                .refresh(id, mockAwsS3FilesystemService);

        final MockHttpServletRequestBuilder request = get(path)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is(HttpStatus.PARTIAL_CONTENT.value()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Warning: " + msg));
    }

    @Test
    @DirtiesContext
    public void testRefreshNotFound() throws Exception {
        final long id = config1.getId();
        final String path = "/filesystems/aws_s3/config/" + id + "/refresh";

        final String msg = "Not found";

        Mockito.doThrow(new NotFoundException(msg)).when(mockAwsS3ConfigEntityService)
                .refresh(id, mockAwsS3FilesystemService);

        final MockHttpServletRequestBuilder request = get(path)
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
}
