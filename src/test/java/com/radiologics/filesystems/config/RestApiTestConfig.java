// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.config;

import com.radiologics.filesystems.aws.s3.rest.AwsS3ConfigApi;
import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityService;
import com.radiologics.filesystems.aws.s3.services.AwsS3FilesystemService;
import com.radiologics.filesystems.rest.ProjectFilesystemSettingsApi;
import com.radiologics.filesystems.rest.RemoteFilesApi;
import com.radiologics.filesystems.services.ProjectFilesystemSettingsService;
import com.radiologics.filesystems.services.RemoteCatalogService;
import com.radiologics.filesystems.services.RemoteFilesPluginService;
import org.mockito.Mockito;
import org.nrg.xdat.security.UserGroupServiceI;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
@EnableWebSecurity
@Import(RestTestConfig.class)
public class RestApiTestConfig extends WebSecurityConfigurerAdapter {

    @Bean
    public AwsS3ConfigApi awsS3ConfigApi(@Qualifier(value = "mockAwsS3FilesystemService") final AwsS3FilesystemService mockAwsS3FilesystemService,
                                         @Qualifier(value = "mockAwsS3ConfigEntityService") final AwsS3ConfigEntityService mockAwsS3ConfigEntityService,
                                         final UserManagementServiceI userManagementService,
                                         final RoleHolder roleHolder) {
        return new AwsS3ConfigApi(mockAwsS3FilesystemService, mockAwsS3ConfigEntityService,
                userManagementService, roleHolder);
    }

    @Bean(name = "mockAwsS3ConfigEntityService")
    public AwsS3ConfigEntityService mockAwsS3ConfigEntityService() {
        return Mockito.mock(AwsS3ConfigEntityService.class);
    }

    @Bean(name = "mockAwsS3FilesystemService")
    public AwsS3FilesystemService mockAwsS3FilesystemService() {
        return Mockito.mock(AwsS3FilesystemService.class);
    }

    @Bean
    public ProjectFilesystemSettingsApi projectFilesystemSettingsApi(final ProjectFilesystemSettingsService projectFilesystemSettingsService,
                                                       final UserManagementServiceI userManagementService,
                                                       final RoleHolder roleHolder) {
        return new ProjectFilesystemSettingsApi(projectFilesystemSettingsService,
                userManagementService, roleHolder);
    }

    @Bean
    public ProjectFilesystemSettingsService mockProjectFilesystemSettingsService() {
        return Mockito.mock(ProjectFilesystemSettingsService.class);
    }

    @Bean
    public RemoteFilesApi remoteFilesApi(final CatalogService catalogService,
                                         final RemoteCatalogService remoteCatalogService,
                                         final RemoteFilesPluginService remoteFilesService,
                                         final UserManagementServiceI userManagementService,
                                         final RoleHolder roleHolder) {
        return new RemoteFilesApi(catalogService, remoteCatalogService, remoteFilesService,
                userManagementService, roleHolder);
    }

    @Bean
    public RemoteCatalogService mockRemoteCatalogService() {
        return Mockito.mock(RemoteCatalogService.class);
    }

    @Bean
    public CatalogService mockCatalogService() {
        return Mockito.mock(CatalogService.class);
    }

    @Bean
    public RemoteFilesPluginService mockRemoteFilesPluginService() {
        return Mockito.mock(RemoteFilesPluginService.class);
    }

    @Bean(name = "mockRoleService")
    public RoleServiceI mockRoleService() {
        return Mockito.mock(RoleServiceI.class);
    }

    @Bean
    public RoleHolder mockRoleHolder(@Qualifier("mockRoleService") final RoleServiceI roleServiceI,
                                     final NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        return new RoleHolder(roleServiceI, namedParameterJdbcTemplate);
    }

    @Bean
    public NamedParameterJdbcTemplate mockNamedParameterJdbcTemplate() {
        return Mockito.mock(NamedParameterJdbcTemplate.class);
    }

    @Bean
    public UserManagementServiceI mockUserManagementServiceI() {
        return Mockito.mock(UserManagementServiceI.class);
    }

    @Bean
    public UserGroupServiceI mockUserGroupService() {
        return Mockito.mock(UserGroupServiceI.class);
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(new TestingAuthenticationProvider());
    }
}
