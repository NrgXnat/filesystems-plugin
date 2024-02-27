// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.plugin;

import com.radiologics.filesystems.tasks.LocalArchiveCleanup;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@XnatPlugin(value = "filesystems_plugin",
        name = "External Filesystems Plugin",
        logConfigurationFile = "META-INF/resources/filesystems-logback.xml",
        entityPackages = "com.radiologics.filesystems")
@ComponentScan(value = "com.radiologics.filesystems.*",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*TestConfig.*"))
public class FilesystemsPlugin {

    @Bean(name = "filesystemsThreadPoolExecutorFactoryBean")
    public ThreadPoolExecutorFactoryBean filesystemsThreadPoolExecutorFactoryBean() {
        ThreadPoolExecutorFactoryBean tBean = new ThreadPoolExecutorFactoryBean();
        tBean.setCorePoolSize(5);
        tBean.setThreadNamePrefix("filesystems-plugin-");
        return tBean;
    }

    @Bean
    public TriggerTask localArchiveCleanupTask(final LocalArchiveCleanup localArchiveCleanup) {
        return new TriggerTask(
                localArchiveCleanup,
                new PeriodicTrigger(4, TimeUnit.HOURS)
        );
    }
}
