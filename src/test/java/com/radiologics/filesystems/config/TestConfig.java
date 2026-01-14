// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.config;

import com.google.common.util.concurrent.MoreExecutors;
import com.radiologics.filesystems.aws.s3.dao.AwsS3ConfigEntityDao;
import com.radiologics.filesystems.aws.s3.model.entity.AwsS3ConfigEntity;
import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityService;
import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityServiceImpl;
import com.radiologics.filesystems.dao.ProjectFilesystemSettingsEntityDao;
import com.radiologics.filesystems.dao.RemoteFilesTrackerEntityDao;
import com.radiologics.filesystems.model.entity.FilesystemConfigEntity;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import com.radiologics.filesystems.services.*;
import org.apache.commons.dbcp2.BasicDataSource;
import org.hibernate.SessionFactory;
import org.mockito.Mockito;
import org.nrg.config.services.ConfigService;
import org.nrg.framework.node.XnatNode;
import org.nrg.framework.services.ContextService;
import org.nrg.framework.services.SerializerService;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xnat.node.dao.XnatNodeInfoDAO;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.node.services.impl.HibernateXnatNodeInfoService;
import org.nrg.xnat.task.entities.XnatTaskInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.PropertiesFactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.hibernate5.HibernateTransactionManager;
import org.springframework.orm.hibernate5.LocalSessionFactoryBean;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

import static com.radiologics.filesystems.config.SharedStrings.testArchiveDir;
import static org.mockito.ArgumentMatchers.any;

@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
@Import(ObjectMapperTestConfig.class)
public class TestConfig {

    @Bean(name = "defaultFilesystemService")
    public FilesystemService defaultFilesystemService(@Qualifier("filesystemsThreadPoolExecutorFactoryBean")
                                                                  ThreadPoolExecutorFactoryBean filesystemsThreadPoolExecutorFactoryBean,
                                                      SiteConfigPreferences siteConfigPreferences) {
        return new DefaultFilesystemService(siteConfigPreferences,
                filesystemsThreadPoolExecutorFactoryBean);
    }

    @Bean(name = "filesystemsThreadPoolExecutorFactoryBean")
    public ThreadPoolExecutorFactoryBean filesystemsThreadPoolExecutorFactoryBean() {
        ThreadPoolExecutorFactoryBean tBean = new ThreadPoolExecutorFactoryBean();
        tBean.setCorePoolSize(5);
        tBean.setThreadNamePrefix("filesystems-plugin-");
        return tBean;
    }

    @Bean(name = "threadPoolExecutorFactoryBean")
    public ThreadPoolExecutorFactoryBean threadPoolExecutorFactoryBean() {
        ThreadPoolExecutorFactoryBean tBean = Mockito.mock(ThreadPoolExecutorFactoryBean.class);
        ExecutorService ec = MoreExecutors.newDirectExecutorService(); //synchronous execution for testing
        Mockito.when(tBean.getObject()).thenReturn(ec);
        return tBean;

        // Only works when synchronous -- why? This works in the non-test environment. Something to do with H2 db?
        //ThreadPoolExecutorFactoryBean tBean = new ThreadPoolExecutorFactoryBean();
        //tBean.setCorePoolSize(5);
        //tBean.setThreadNamePrefix("filesystems-plugin-alt-");
        //return tBean;
    }

    /*
    Hibernate entity service and dependencies
     */
    @Bean
    public AwsS3ConfigEntityService awsS3ConfigEntityService() {
        return new AwsS3ConfigEntityServiceImpl();
    }

    @Bean
    public AwsS3ConfigEntityDao awsS3ConfigEntityDao() {
        return new AwsS3ConfigEntityDao();
    }


    @Bean
    public ProjectFilesystemSettingsEntityService projectFilesystemSettingsEntityService() {
        return new ProjectFilesystemSettingsEntityServiceImpl();
    }

    @Bean
    public ProjectFilesystemSettingsEntityDao projectFilesystemSettingsEntityDao() {
        return new ProjectFilesystemSettingsEntityDao();
    }

    @Bean
    public RemoteFilesTrackerEntityService remoteFilesTrackerEntityService() {
        return new RemoteFilesTrackerEntityServiceImpl();
    }

    @Bean
    public RemoteFilesTrackerEntityDao remoteFilesTrackerEntityDao() {
        return new RemoteFilesTrackerEntityDao();
    }


    @Bean
    public XnatNodeInfoDAO xnatNodeInfoDAO() {
        return new XnatNodeInfoDAO();
    }

    @Bean
    public HibernateXnatNodeInfoService xnatNodeInfoService(XnatNodeInfo xnatNodeInfo, XnatNode xnatNode, JdbcTemplate mockJdbcTemplate, XnatNodeInfoDAO xnatNodeInfoDAO) {
        HibernateXnatNodeInfoService nodeInfoService = new HibernateXnatNodeInfoService(xnatNode, mockJdbcTemplate);
        nodeInfoService.setDao(xnatNodeInfoDAO); // Not sure why this isn't getting autowired
        HibernateXnatNodeInfoService nodeInfoServiceSpy = Mockito.spy(nodeInfoService);
        Mockito.doReturn(xnatNodeInfo)
                .when(nodeInfoServiceSpy).getXnatNodeInfoByNodeIdAndHostname(any(String.class), any(String.class));
        return nodeInfoServiceSpy;
    }

    @Bean JdbcTemplate mockJdbcTemplate() {
        return Mockito.mock(JdbcTemplate.class);
    }

    @Bean
    public XnatNodeInfo xnatNodeInfo() {
        XnatNodeInfo xnatNodeInfo = new XnatNodeInfo("1", "test", "ip", new Date());
        xnatNodeInfo.setIsActive(true);
        xnatNodeInfo.setLastCheckIn(new Date());
        return xnatNodeInfo;
    }

    @Bean
    public XnatNode xnatNode() {
        return Mockito.mock(XnatNode.class);
    }

    /*
    XNAT
     */
    @Bean
    public SerializerService serializerService(Jackson2ObjectMapperBuilder objectMapperBuilder)
            throws SAXNotSupportedException, SAXNotRecognizedException, ParserConfigurationException {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        return new SerializerService(objectMapperBuilder, DocumentBuilderFactory.newInstance(),
                spf, TransformerFactory.newInstance(), (SAXTransformerFactory) SAXTransformerFactory.newInstance());
    }

    @Bean
    public AliasTokenService aliasTokenService() {
        return Mockito.mock(AliasTokenService.class);
    }

    @Bean
    public SiteConfigPreferences siteConfigPreferences() {
        SiteConfigPreferences prefs = Mockito.mock(SiteConfigPreferences.class);
        Mockito.when(prefs.getArchivePath()).thenReturn(testArchiveDir);
        return prefs;
    }

    @Bean
    public NrgPreferenceService nrgPreferenceService() {
        return Mockito.mock(NrgPreferenceService.class);
    }

    @Bean
    public PermissionsServiceI permissionsService() {
        return Mockito.mock(PermissionsServiceI.class);
    }

    @Bean
    public ContextService contextService(final ApplicationContext applicationContext) {
        final ContextService contextService = new ContextService();
        contextService.setApplicationContext(applicationContext);
        return contextService;
    }

    @Bean
    public XnatUserProvider primaryAdminUserProvider() {
        return Mockito.mock(XnatUserProvider.class);
    }

    @Bean
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
    }

    /*
    Session factory
     */
    @Bean
    public ResourceTransactionManager transactionManager(final SessionFactory sessionFactory) throws Exception {
        return new HibernateTransactionManager(sessionFactory);
    }

    @Bean
    public LocalSessionFactoryBean sessionFactory(final DataSource dataSource,
                                                  @Qualifier("hibernateProperties") final Properties properties) {
        final LocalSessionFactoryBean bean = new LocalSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setHibernateProperties(properties);
        bean.setAnnotatedClasses(
                AwsS3ConfigEntity.class,
                FilesystemConfigEntity.class,
                ProjectFilesystemSettingsEntity.class,
                RemoteFilesTrackerEntity.class,
                XnatNodeInfo.class,
                XnatTaskInfo.class
        );
        return bean;
    }

    @Bean
    public Properties hibernateProperties() throws IOException {
        Properties properties = new Properties();
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.hbm2ddl.auto", "create");
        properties.put("hibernate.cache.use_second_level_cache", false);
        properties.put("hibernate.cache.use_query_cache", false);

        PropertiesFactoryBean hibernate = new PropertiesFactoryBean();
        hibernate.setProperties(properties);
        hibernate.afterPropertiesSet();
        return hibernate.getObject();
    }

    @Bean
    public DataSource dataSource() {
        BasicDataSource basicDataSource = new BasicDataSource();
        basicDataSource.setDriverClassName(org.h2.Driver.class.getName());
        basicDataSource.setUrl("jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        basicDataSource.setUsername("sa");
        return basicDataSource;
    }
}
