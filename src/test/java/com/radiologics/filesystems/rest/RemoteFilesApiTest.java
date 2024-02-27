// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radiologics.filesystems.config.RestApiTestConfig;
import com.radiologics.filesystems.model.entity.RemoteFilesItemState;
import com.radiologics.filesystems.services.RemoteCatalogService;
import com.radiologics.filesystems.services.RemoteFilesPluginService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatAbstractresource;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.auto.AutoXnatAbstractresource;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
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

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PrepareForTest({Permissions.class, AutoXnatAbstractresource.class})
@PowerMockIgnore({"org.apache.*", "java.*", "javax.*", "org.w3c.*", "com.sun.*"})
@WebAppConfiguration
@ContextConfiguration(classes = {RestApiTestConfig.class})
public class RemoteFilesApiTest {
    private Authentication AUTH;
    private UserI nonAdmin;
    private MockMvc mockMvc;

    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;

    @Mock private ArchivableItem mockItem;
    @Mock private XnatResourcecatalog mockRes;
    @Mock private XnatAbstractresource nonCatalogResource;
    private List<XnatAbstractresourceI> resList;
    private final String uri = "uri";
    private final Integer resId = 1;
    private final Integer resIdAlt = 2;

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper mapper;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;

    @Autowired private CatalogService mockCatalogService;
    @Autowired private RemoteFilesPluginService mockRemoteFilesService;
    @Autowired private RemoteCatalogService mockRemoteCatalogService;

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

        // Mock some items
        when(mockItem.getId()).thenReturn("ITEM_E00001");
        ResourceData mockResourceData = Mockito.mock(ResourceData.class);
        when(mockCatalogService.getResourceDataFromUri(uri)).thenReturn(mockResourceData);
        when(mockResourceData.getItem()).thenReturn(mockItem);
        URIManager.ArchiveItemURI archiveItemURI = Mockito.mock(URIManager.ArchiveItemURI.class);
        resList = Arrays.asList(mockRes, Mockito.mock(XnatResourcecatalog.class));
        when(archiveItemURI.getResources(true)).thenReturn(resList);
        when(mockResourceData.getXnatUri()).thenReturn(archiveItemURI);

        PowerMockito.mockStatic(AutoXnatAbstractresource.class);
        PowerMockito.when(AutoXnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(eq(resId),
                any(UserI.class), eq(false))).thenReturn(mockRes);
        PowerMockito.when(AutoXnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(eq(resIdAlt),
                any(UserI.class), eq(false))).thenReturn(nonCatalogResource);
    }

    @Test
    public void testCheckAccess() throws Exception {
        final String path = "/remote_files/check_access";

        String url = "url";
        String urlBad = "bad";
        Mockito.when(mockRemoteFilesService.canPullFile(url, supportedProject)).thenReturn(true);
        Mockito.when(mockRemoteFilesService.canPullFile(urlBad, supportedProject)).thenReturn(false);


        final MockHttpServletRequestBuilder request = get(path)
                .param("url", url)
                .param("project", supportedProject)
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

        assertThat(response, is("true"));

        final MockHttpServletRequestBuilder request2 = get(path)
                .param("url", urlBad)
                .param("project", supportedProject)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response2 =
                mockMvc.perform(request2)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response2, is("false"));
    }

    @Test
    @DirtiesContext
    public void testCheckAccess500() throws Exception {
        final String path = "/remote_files/check_access";
        final String msg = "Runtime exception";

        String url = "url";
        Mockito.when(mockRemoteFilesService.canPullFile(url, supportedProject)).thenThrow(new RuntimeException(msg));

        final MockHttpServletRequestBuilder request = get(path)
                .param("url", url)
                .param("project", supportedProject)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().is5xxServerError());
    }

    @Test
    public void testPullProgress() throws Exception {
        final String path = "/remote_files/pull_progress";

        Mockito.when(mockRemoteFilesService.monitorPullProgress(mockRes)).thenReturn(50.0);

        final MockHttpServletRequestBuilder request = get(path)
                .param("resource", resId.toString())
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("50%"));
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).monitorPullProgress(mockRes);
    }

    @Test
    public void testPullProgress400() throws Exception {
        final String path = "/remote_files/pull_progress";
        final String msg = "Non-catalog resource " + resIdAlt;

        final MockHttpServletRequestBuilder request = get(path)
                .param("resource", resIdAlt.toString())
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
    public void testPullProgress500() throws Exception {
        final String path = "/remote_files/pull_progress";
        final String msg = "Server exception";

        Mockito.when(mockRemoteFilesService.monitorPullProgress(mockRes))
                .thenThrow(new ServerException(msg));

        final MockHttpServletRequestBuilder request = get(path)
                .param("resource", resId.toString())
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
    public void testInitiatePullRes() throws Exception {
        final String path = "/remote_files/pull";

        Map<Integer, Future<Boolean>> jobs = new HashMap<>();
        jobs.put(resId, CompletableFuture.completedFuture(true));

        Mockito.when(mockRemoteFilesService.initiatePullItem(mockItem,
                Collections.singletonList(mockRes), null, null)).thenReturn(jobs);

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .param("resource", resId.toString())
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Pull initiated"));
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).initiatePullItem(mockItem,
                Collections.singletonList(mockRes), null, null);
    }

    @Test
    public void testInitiatePullResException() throws Exception {
        final String path = "/remote_files/pull";

        Map<Integer, Future<Boolean>> jobs = new HashMap<>();
        jobs.put(resId, null);

        Mockito.when(mockRemoteFilesService.initiatePullItem(mockItem,
                Collections.singletonList(mockRes), null, null)).thenReturn(jobs);

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .param("resource", resId.toString())
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().is4xxClientError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Cannot perform pull: item " + mockItem.getId() + " resource " +
                resId + " is in another task or the xnat_node_info for the server cannot be determined."));
    }

    @Test
    @DirtiesContext
    public void testInitiatePull500() throws Exception {
        final String path = "/remote_files/pull";
        final String msg = "Server exception";

        Mockito.when(mockRemoteFilesService.initiatePullItem(mockItem,
                Collections.singletonList(mockRes), null, null))
                .thenThrow(new ServerException(msg));

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .param("resource", resId.toString())
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
    public void testInitiatePull() throws Exception {
        final String path = "/remote_files/pull";

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Pull initiated"));
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).initiatePullItem(mockItem,
                resList, null, null);
    }

    @Test
    public void testGetItemStatus() throws Exception {
        RemoteFilesItemState itemState = new RemoteFilesItemState();
        itemState.setStatus(Locked);
        itemState.addResourceWithStatus(itemState.makeResourceKey(1, "1"), "Archived");
        itemState.addResourceWithStatus(itemState.makeResourceKey(2, "2"), "Local");
        when(mockRemoteFilesService.getItemState(mockItem)).thenReturn(itemState);

        final String path = "/remote_files/item_status";

        final MockHttpServletRequestBuilder request = get(path)
                .param("item", uri)
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

        final RemoteFilesItemState state = mapper.readValue(response, RemoteFilesItemState.class);
        assertThat(state.getStatus(), is(itemState.getStatus()));
        assertThat(state.getResources(), hasSize(2));
    }

    @Test
    @DirtiesContext
    public void testGetItemStatus400() throws Exception {
        String msg = "Client exception";
        Mockito.when(mockCatalogService.getResourceDataFromUri(uri)).thenThrow(new ClientException(msg));

        final String path = "/remote_files/item_status";

        final MockHttpServletRequestBuilder request = get(path)
                .param("item", uri)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DirtiesContext
    public void testGetItemStatus500() throws Exception {
        String msg = "Runtime exception";
        Mockito.when(mockCatalogService.getResourceDataFromUri(uri)).thenThrow(new RuntimeException(msg));

        final String path = "/remote_files/item_status";

        final MockHttpServletRequestBuilder request = get(path)
                .param("item", uri)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        mockMvc.perform(request)
                .andExpect(status().is5xxServerError());
    }

    @Test
    public void testInitiatePush() throws Exception {
        final String path = "/remote_files/push";

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Push initiated"));
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).initiatePushItem(mockItem,
                resList, nonAdmin);
    }

    @Test
    public void testInitiatePushRes() throws Exception {
        final String path = "/remote_files/push";

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .param("resource", resId.toString())
                .with(authentication(AUTH))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, is("Push initiated"));
        Mockito.verify(mockRemoteFilesService, Mockito.times(1)).initiatePushItem(mockItem,
                Collections.singletonList(mockRes), nonAdmin);
    }

    @Test
    public void testInitiatePushResException() throws Exception {
        final String path = "/remote_files/push";

        final String msg = "Project XXX not configured to push to remote filesystem";
        doThrow(new ClientException(msg)).when(mockRemoteFilesService).initiatePushItem(mockItem,
                Collections.singletonList(mockRes), nonAdmin);

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .param("resource", resId.toString())
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
    public void testInitiatePush500() throws Exception {
        final String path = "/remote_files/push";
        final String msg = "Runtime exception";

        doThrow(new RuntimeException(msg)).when(mockRemoteFilesService).initiatePushItem(mockItem,
                Collections.singletonList(mockRes), nonAdmin);

        final MockHttpServletRequestBuilder request = post(path)
                .param("item", uri)
                .param("resource", resId.toString())
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
    public void testAddRemoteFilesToCatalog() throws Exception {
        addRemoteFiles(null, null);
    }

    @Test
    public void testAddRemoteFilesToCatalogOverwrite() throws Exception {
        addRemoteFiles(true, null);
    }

    @Test
    public void testAddRemoteFilesToCatalogNoOverwrite() throws Exception {
        addRemoteFiles(false, null);
    }

    @Test
    public void testAddRemoteFilesToCatalogCreate() throws Exception {
        addRemoteFiles(null, true);
    }

    @Test
    public void testAddRemoteFilesToCatalogNoCreate() throws Exception {
        addRemoteFiles(null, false);
    }

    @Test
    public void testAddRemoteFilesToCatalogCreateOverwrite() throws Exception {
        addRemoteFiles(true, false);
    }

    private void addRemoteFiles(Boolean overwrite, Boolean create) throws Exception {
        final String path = "/remote_files/add_to_catalog";
        final String resUri = "fakeUri";
        final Map<String, String> urls = new HashMap<>();
        urls.put("one", "one");
        urls.put("two", "two");
        final String urlsJson = mapper.writeValueAsString(urls);
        boolean expectCreate = true;
        boolean expectOverwrite = false;

        MockHttpServletRequestBuilder request = put(path)
                .param("resource", resUri);

        if (create != null) {
            request = request.param("create", Boolean.toString(create));
            expectCreate = create;
        }
        if (overwrite != null) {
            request = request.param("overwrite", Boolean.toString(overwrite));
            expectOverwrite = overwrite;
        }
        request.content(urlsJson).contentType(JSON)
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
        Mockito.verify(mockRemoteCatalogService, Mockito.times(1))
                .addRemoteFilesToResourceCatalog(nonAdmin, resUri, urls, expectCreate, expectOverwrite);
    }

    @Test
    @DirtiesContext
    public void testAddRemoteFilesToCatalogException400() throws Exception {
        final String path = "/remote_files/add_to_catalog";
        final String resUri = "fakeUri";
        final boolean create = true;
        final Map<String, String> urls = new HashMap<>();
        urls.put("one", "one");
        urls.put("two", "two");
        final String urlsJson = mapper.writeValueAsString(urls);

        final String msg = "Client exception";
        doThrow(new ClientException(msg)).when(mockRemoteCatalogService)
                .addRemoteFilesToResourceCatalog(nonAdmin, resUri, urls, create, false);

        final MockHttpServletRequestBuilder request = put(path)
                .param("resource", resUri)
                .param("create", Boolean.toString(create))
                .content(urlsJson).contentType(JSON)
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
    public void testAddRemoteFilesToCatalogException500() throws Exception {
        final String path = "/remote_files/add_to_catalog";
        final String resUri = "fakeUri";
        final boolean create = true;
        final Map<String, String> urls = new HashMap<>();
        urls.put("one", "one");
        urls.put("two", "two");
        final String urlsJson = mapper.writeValueAsString(urls);

        final String msg = "Server exception";
        doThrow(new ServerException(msg)).when(mockRemoteCatalogService)
                .addRemoteFilesToResourceCatalog(nonAdmin, resUri, urls, create, false);

        final MockHttpServletRequestBuilder request = put(path)
                .param("resource", resUri)
                .param("create", Boolean.toString(create))
                .content(urlsJson).contentType(JSON)
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
