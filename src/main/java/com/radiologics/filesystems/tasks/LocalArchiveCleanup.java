// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.tasks;

import com.radiologics.filesystems.exceptions.NoSuchFilesystemException;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.services.FilesystemService;
import com.radiologics.filesystems.services.ProjectFilesystemSettingsService;
import com.radiologics.filesystems.services.RemoteFilesPluginService;
import org.nrg.action.ClientException;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.task.XnatTask;
import org.nrg.framework.task.services.XnatTaskService;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.base.auto.AutoXnatProjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.exceptions.UnsupportedRemoteFilesOperationException;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.task.AbstractXnatTask;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.*;


@Slf4j
@Component
@XnatTask(taskId = "LocalArchiveCleanup",
        description = "Local Archive Cleanup Task: Periodically uploads files to configured external filesystems and " +
                "removes them from local archive",
        defaultExecutionResolver = "SingleNodeExecutionResolver",
        executionResolverConfigurable = true)
public class LocalArchiveCleanup extends AbstractXnatTask {
    private final XnatUserProvider primaryAdminUserProvider;
    private final RemoteFilesPluginService remoteFilesService;
    private final ProjectFilesystemSettingsService projectFilesystemSettingsService;
    private final CatalogService catalogService;

    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public LocalArchiveCleanup(final XnatUserProvider primaryAdminUserProvider,
                               final RemoteFilesPluginService remoteFilesService,
                               final ProjectFilesystemSettingsService projectFilesystemSettingsService,
                               final CatalogService catalogService,
                               final XnatTaskService taskService,
                               final XnatAppInfo appInfo,
                               final JdbcTemplate jdbcTemplate) {
        super(taskService, true, appInfo, jdbcTemplate);
        this.primaryAdminUserProvider = primaryAdminUserProvider;
        this.catalogService = catalogService;
        this.remoteFilesService = remoteFilesService;
        this.projectFilesystemSettingsService = projectFilesystemSettingsService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runTask() {
        final UserI user = primaryAdminUserProvider.get();
        if (user == null) {
            log.warn("The user for running the local archive cleanup process was not found. Aborting for now.");
            return;
        }
        log.trace("Running local archive cleanup job as {}", user.getLogin());

        for (XnatProjectdata project : AutoXnatProjectdata.getAllXnatProjectdatas(user, false)) {
            String projectId = project.getId();
            ProjectFilesystemSettings settings = null;
            FilesystemService fs;
            try {
                settings = projectFilesystemSettingsService.getSettingsForProject(projectId);
                fs = projectFilesystemSettingsService.getArchiverFilesystemForProject(projectId, false);
            } catch (NotFoundException | UnsupportedRemoteFilesOperationException e) {
                log.trace("Project {} not configured for cleanup", project.getId());
                continue;
            } catch (NoSuchFilesystemException e) {
                log.error("Cannot determine filesystem service to handle cleanup of project {}: settings {}",
                        projectId, settings, e);
                continue;
            }
            pushToExternalFilesystem(user, project, fs, settings.getCleanupInterval());
        }
    }

    /**
     * For all projects included in cleanup for each configured filesystem
     * @param user the user
     */
    private void pushToExternalFilesystem(final UserI user,
                                          XnatProjectdata project,
                                          FilesystemService fs,
                                          int cleanupInterval) {

        List<ArchivableItem> items = new ArrayList<>();
        items.add(project);
        items.addAll(project.getParticipants_participant());
        items.addAll(project.getExperiments());
        for (final ArchivableItem item : items) {
            ResourceData resData;
            try {
                resData = catalogService.getResourceDataFromUri(UriParserUtils.getArchiveUri(item));
            } catch (ClientException e) {
                log.error("Unable to parse item {} uri", item.getId(), e);
                continue;
            }
            final List<XnatAbstractresourceI> resources = resData.getXnatUri().getResources(true);
            remoteFilesService.pushItem(item, resources, user, fs, cleanupInterval);
        }
    }
}
