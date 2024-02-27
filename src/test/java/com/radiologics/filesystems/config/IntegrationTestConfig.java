// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.config;

import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityService;
import com.radiologics.filesystems.aws.s3.services.AwsS3FilesystemService;
import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;
import com.radiologics.filesystems.services.*;
import org.mockito.Mockito;
import org.nrg.framework.node.XnatNode;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.services.cache.UserDataCache;
import org.nrg.xnat.node.services.XnatNodeInfoService;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.services.archive.RemoteFilesService;
import org.nrg.xnat.services.archive.impl.legacy.DefaultCatalogService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.List;

@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
@Import({AwsS3MockTestConfig.class, TestConfig.class})
public class IntegrationTestConfig {

    @Bean
    public RemoteCatalogService remoteCatalogService(final CatalogService localCatalogService,
                                                     final RemoteFilesPluginService remoteFilesService,
                                                     final List<FilesystemService> filesystemServices) {
        return new RemoteCatalogServiceImpl(localCatalogService, remoteFilesService, filesystemServices);
    }

    @Bean
    public CatalogService catalogService(final RemoteFilesService remoteFilesService) {
        DefaultCatalogService cs = Mockito.spy(new DefaultCatalogService(Mockito.mock(SiteConfigPreferences.class),
                Mockito.mock(NamedParameterJdbcTemplate.class), Mockito.mock(CacheManager.class), Mockito.mock(UserDataCache.class)));
        cs.setRemoteFilesService(remoteFilesService);
        return cs;
    }

    @Bean
    public RemoteFilesPluginService remoteFilesService(final RemoteFilesTrackerEntityService remoteFilesTrackerEntityService,
                                                       final ProjectFilesystemSettingsService projectFilesystemSettingsService,
                                                       final List<FilesystemService> filesystemServices,
                                                       final SiteConfigPreferences siteConfigPreferences,
                                                       @Qualifier("threadPoolExecutorFactoryBean")
                                                           final ThreadPoolExecutorFactoryBean threadPoolExecutorFactoryBean,
                                                       final XnatNodeInfoService nodeInfoService,
                                                       final XnatNode xnatNode) {
        return new RemoteFilesPluginServiceImpl(remoteFilesTrackerEntityService, projectFilesystemSettingsService,
                filesystemServices, siteConfigPreferences, threadPoolExecutorFactoryBean, nodeInfoService, xnatNode);
    }

    @Bean
    public XnatNode mockXnatNode() {
        XnatNode mockNode = Mockito.mock(XnatNode.class);
        Mockito.when(mockNode.getNodeId()).thenReturn("1");
        return mockNode;
    }


    @Bean
    public ProjectFilesystemSettingsService projectFilesystemSettingsService(final ProjectFilesystemSettingsEntityService projectFilesystemSettingsEntityService,
                                                                             final List<FilesystemService> filesystemServices,
                                                                             final List<FilesystemConfigEntityService<? extends FilesystemConfigEntity>> filesystemConfigEntityServices) {
        return new ProjectFilesystemSettingsServiceImpl(projectFilesystemSettingsEntityService,
                filesystemServices,
                filesystemConfigEntityServices);
    }

    @Bean
    public AwsS3FilesystemService awsS3FilesystemService(XnatAppInfo mockPrimaryXnatAppInfo,
                                                         AwsS3ConfigEntityService awsS3ConfigEntityService,
                                                         SiteConfigPreferences siteConfigPreferences,
                                                         @Qualifier("filesystemsThreadPoolExecutorFactoryBean")
                                                                     ThreadPoolExecutorFactoryBean filesystemsThreadPoolExecutorFactoryBean) {
        return new AwsS3FilesystemService(mockPrimaryXnatAppInfo, awsS3ConfigEntityService, siteConfigPreferences,
                filesystemsThreadPoolExecutorFactoryBean);
    }

    @Bean
    public XnatAppInfo mockXnatAppInfo() {
        XnatAppInfo mockAppInfo = Mockito.mock(XnatAppInfo.class);
        Mockito.when(mockAppInfo.isPrimaryNode()).thenReturn(true);
        return mockAppInfo;
    }
}
