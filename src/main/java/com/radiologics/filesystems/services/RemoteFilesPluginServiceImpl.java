// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.google.common.annotations.VisibleForTesting;
import com.radiologics.filesystems.exceptions.*;
import com.radiologics.filesystems.model.entity.RemoteFilesItemState;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceStatus;
import com.radiologics.filesystems.model.entity.RemoteFilesResourceTask;
import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.hibernate.HibernateException;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.framework.node.XnatNode;
import org.nrg.xdat.model.CatCatalogI;
import org.nrg.xdat.model.CatEntryI;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.base.BaseXnatExperimentdata;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xft.utils.zip.ZipUtils;
import org.nrg.xnat.exceptions.InvalidArchiveStructure;
import org.nrg.xnat.exceptions.UnsupportedRemoteFilesOperationException;
import org.nrg.xnat.helpers.resource.XnatResourceInfo;
import org.nrg.xnat.helpers.resource.XnatResourceInfoMap;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.node.services.XnatNodeInfoService;
import org.nrg.xnat.presentation.ChangeSummaryBuilderA;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.CatalogUtils;
import org.nrg.xnat.utils.ThreadAndProcessFileLock;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class RemoteFilesPluginServiceImpl implements RemoteFilesPluginService {
    private final RemoteFilesTrackerEntityService remoteFilesTrackerEntityService;
    private final ProjectFilesystemSettingsService projectFilesystemSettingsService;
    private final List<FilesystemService> filesystemServices;
    private final SiteConfigPreferences siteConfigPreferences;
    private final XnatNodeInfoService nodeInfoService;
    private final XnatNode xnatNode;
    private final ExecutorService executorService;
    private final int maxConsecutiveFailures = 3;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    public RemoteFilesPluginServiceImpl(final RemoteFilesTrackerEntityService remoteFilesTrackerEntityService,
                                        final ProjectFilesystemSettingsService projectFilesystemSettingsService,
                                        final List<FilesystemService> filesystemServices,
                                        final SiteConfigPreferences siteConfigPreferences,
                                        @Qualifier("threadPoolExecutorFactoryBean")
                                            final ThreadPoolExecutorFactoryBean threadPoolExecutorFactoryBean,
                                        final XnatNodeInfoService nodeInfoService,
                                        final XnatNode xnatNode) {

        this.remoteFilesTrackerEntityService = remoteFilesTrackerEntityService;
        this.projectFilesystemSettingsService = projectFilesystemSettingsService;
        this.filesystemServices = filesystemServices;
        this.siteConfigPreferences = siteConfigPreferences;
        this.executorService = threadPoolExecutorFactoryBean.getObject();
        this.nodeInfoService = nodeInfoService;
        this.xnatNode = xnatNode;
    }

    /**
     * Get Xnat Node Info for this server
     * @return XnatNodeInfo
     * @throws MissingXnatNodeInfoException if node info cannot be determined
     */
    private XnatNodeInfo getXnatNodeInfo() throws MissingXnatNodeInfoException {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            XnatNodeInfo xnatNodeInfo = nodeInfoService.getXnatNodeInfoByNodeIdAndHostname(xnatNode.getNodeId(), hostname);
            if (xnatNodeInfo == null) {
                throw new HibernateException("No xnatNodeInfo found");
            }
            return xnatNodeInfo;
        } catch (UnknownHostException | HibernateException e) {
            String msg = "Unable to obtain host or node information.";
            log.error(msg, e);
            throw new MissingXnatNodeInfoException(e);
        }
    }

    /**
     * Check if remoteFilesTrackerEntity indicates a valid task
     * @param entity the archive tracker entry
     * @return  TRUE if entry indicates a valid task (pull or push or add started after server start),
     *          FALSE if entry DNE or indicates no task or task is invalid
     */
    private boolean hasValidTask(@Nullable RemoteFilesTrackerEntity entity) {
        RemoteFilesResourceTask task;
        if (entity != null && (task = entity.getTask()) != null && task != RemoteFilesResourceTask.None) {
            // Item is performing a task. However, if task was started prior to node initialization, assume task
            // isn't actually running (e.g., server was shut down in the middle of a pull or push).
            XnatNodeInfo nodeInfo = entity.getNodeInfo();
            boolean nodeOlderThanLock = true;
            if (nodeInfo != null) {
                // Compare up to second
                long serverInit = nodeInfo.getLastInitialized().toInstant().getEpochSecond();
                long lockCreated = entity.getTimestamp().toInstant().getEpochSecond();
                nodeOlderThanLock = lockCreated >= serverInit;
            }
            Calendar c = Calendar.getInstance();
            c.add(Calendar.MINUTE, -90); //Check-ins occur hourly per org.nrg.xnat.initialization.NodeConfig, add cushion
            // If creating node is active and was started before task started => task is valid, else task is invalid
            // Assume null nodeInfo invalid or else it'll be "in task" forever
            return nodeInfo != null && nodeInfo.getIsActive() && nodeInfo.getLastCheckIn().after(c.getTime()) &&
                    nodeOlderThanLock;
        }
        return false;
    }

    /**
     * Lock for task, return false if cannot get the lock
     *
     * @param item the item
     * @param resource the resource
     * @param task the task
     * @param user the user (only needed if task == Push)
     * @param pullOutsideArchive true if pulling to non-archive location (only needed if task == Pull)
     * @param waitPushInterval number of days since last isLocal check before resource can be pushed
     *                         or null to skip this check
     * @param firstPush set to true if this is the first push for the resource
     * @return boolean for successfully obtaining the lock
     */
    private boolean startTask(ArchivableItem item,
                              XnatResourcecatalog resource,
                              RemoteFilesResourceTask task,
                              @Nullable UserI user,
                              @Nullable Boolean pullOutsideArchive,
                              @Nullable Integer waitPushInterval,
                              @Nullable MutableBoolean firstPush) {

        // Add a thread & process lock by item for this check - we're just controlling the checking with lockFile,
        // not the actual push/pull operation, which is "locked" by the database
        Path itemDir;
        try {
            itemDir = item.getExpectedCurrentDirectory().toPath();
            Files.createDirectories(itemDir); // Might not exist for subjects
        } catch (BaseXnatExperimentdata.UnknownPrimaryProjectException | InvalidArchiveStructure | IOException e) {
            itemDir = Paths.get(siteConfigPreferences.getCachePath());
        }

        // lockFile is never actually created, just used as a HashMap key and to name a lock file that's used
        // within ThreadAndProcessFileLock
        File lockFile = itemDir.resolve(resource.getXnatAbstractresourceId().toString()).toFile();

        boolean canStart;
        try {
            final ThreadAndProcessFileLock fl = ThreadAndProcessFileLock.getThreadAndProcessFileLock(lockFile,false);
            try {
                fl.tryLock(0L, TimeUnit.SECONDS); // block other processes from simultaneously running this check
                RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource(resource);
                try {
                    // Now check if we can pull or push the item
                    if (hasValidTask(entity)) {
                        // Someone else is pulling or pushing, only can lock if the other task is a pull
                        // and this is a pull outside archive
                        canStart = entity.getTask() == RemoteFilesResourceTask.Pull &&
                                pullOutsideArchive != null && pullOutsideArchive;
                    } else {
                        // Either db entry lock doesn't exist or it was created prior to server start (so it's invalid)
                        switch (task) {
                            case Add:
                                // We can add while a workflow runs (in fact, we have to be able add files to catalogs
                                // while workflow is finalizing), so if we get to this point, an add task can run
                                canStart = true;
                                break;
                            case Pull:
                                // We can pull while a workflow runs (in fact, we have to be able to pull files
                                // into build dir while workflow is queued), so if we get to this point, pull can run.
                                // HOWEVER, pulling a non-remote resource doesn't make sense unless we're pulling it
                                // outside the archive, so just make sure we're either pulling outside archive or we
                                // have remote files
                                canStart = (pullOutsideArchive != null && pullOutsideArchive) || entity != null;
                                break;
                            case Push:
                                // Don't allow a push if the last local check was within the interval or if any
                                // workflows are currently active. Otherwise, return true
                                ZonedDateTime lastLocalCheck;
                                // wait at least 1 day since last local check
                                if (user != null && (entity == null || waitPushInterval == null ||
                                        (lastLocalCheck = entity.getLastLocalCheck()) == null ||
                                        lastLocalCheck.plusDays(waitPushInterval).isBefore(ZonedDateTime.now()))) {
                                    canStart = PersistentWorkflowUtils.getOpenWorkflows(user, item.getId()).isEmpty();
                                } else {
                                    canStart = false;
                                }
                                break;
                            default:
                                // shouldn't happen
                                canStart = false;
                        }
                    }

                    if (!canStart) {
                        return false;
                    }

                    if (pullOutsideArchive != null && pullOutsideArchive) {
                        // Don't update the entity when we're pulling to some non-archive location
                        return true;
                    }

                    try {
                        XnatNodeInfo xnatNodeInfo = getXnatNodeInfo();
                        if (entity == null) {
                            remoteFilesTrackerEntityService.createRemoteFilesTrackerEntity(item, resource, xnatNodeInfo,
                                    task, null);
                            if (firstPush != null) {
                                firstPush.setTrue();
                            }
                        } else {
                            remoteFilesTrackerEntityService.setTaskAndStatus(entity, xnatNodeInfo, task, null);
                            if (firstPush != null) {
                                firstPush.setFalse();
                            }
                        }
                        return true;
                    } catch (HibernateException | MissingXnatNodeInfoException e) {
                        // We cannot start a task without a valid xnatNodeInfo
                        log.error("Unable to change item {} resource {} ({}) task to {}", item.getId(), resource.getLabel(),
                                resource.getXnatAbstractresourceId(), task, e);
                        return false;
                    }
                } finally {
                    fl.unlock();
                }
            } catch (IOException e) {
                // This is an IOException we expect if we cannot get the lock (or for issues unlocking)
                log.debug("Couldn't lock {} {}", lockFile, Thread.currentThread().getName());
                return false;
            } finally {
                // keep this separate from the unlock() in case the unlock() throws an exception
                ThreadAndProcessFileLock.removeThreadAndProcessFileLock(lockFile);
            }
        } catch (IOException e) {
            // This is an IOException bc we couldn't create the file we want to use for locking. Log it, it's a problem
            log.error("Error while trying to obtain/create file to use with ThreadAndProcessFileLock " +
                            "to check if item {} resource {} ({}) is being pushed or pulled", item.getId(),
                    resource.getLabel(), resource.getXnatAbstractresourceId(), e);
            return false;
        }
    }

    /**
     * Lock for pull, return false if cannot get the lock
     *
     * @param item the item
     * @param resource the resource
     * @param pullOutsideArchive true if pulling to non-archive location
     * @return boolean for successfully obtaining the lock
     */
    @VisibleForTesting
    boolean startPull(ArchivableItem item, XnatResourcecatalog resource, boolean pullOutsideArchive) {
        return startTask(item, resource, RemoteFilesResourceTask.Pull, null,
                pullOutsideArchive, null, null);
    }

    /**
     * Lock for push, return false if cannot get the lock
     *
     * @param item the item
     * @param resource the resource
     * @param user the user
     * @param waitPushInterval number of days since last isLocal check before resource can be pushed
     *                         or null to skip this check
     * @param firstPush set to true if this is the first push for the resource
     * @return boolean for successfully obtaining the lock
     */
    @VisibleForTesting
    boolean startPush(ArchivableItem item, XnatResourcecatalog resource, UserI user,
                      @Nullable Integer waitPushInterval, MutableBoolean firstPush) {
        return startTask(item, resource, RemoteFilesResourceTask.Push, user,
                null, waitPushInterval, firstPush);
    }

    /**
     * Lock for add, return false if cannot get the lock
     *
     * @param item the item
     * @param resource the resource
     * @return boolean for successfully obtaining the lock
     */
    @VisibleForTesting
    boolean startAdd(ArchivableItem item, XnatResourcecatalog resource) {
        return startTask(item, resource, RemoteFilesResourceTask.Add, null,
                null, null, null);
    }

    /**
     * Unlock after task completes, update status
     *
     * @param item item to lock
     * @param resource the resource
     * @param task the completed task
     * @param success if task completed successfully
     * @throws InvalidResourceTaskException if resource is not tracked as remote
     */
    private void completeTask(ArchivableItem item,
                              XnatResourcecatalog resource,
                              RemoteFilesResourceTask task,
                              boolean success)
            throws InvalidResourceTaskException {

        final RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource(resource);
        if (entity == null) {
            throw new InvalidResourceTaskException(item, resource, task);
        }
        completeTask(entity, task, success);
    }

    /**
     * Unlock after task completes, update status
     *
     * @param entity the db entry corresponding to resource to unlock
     * @param task the completed task
     * @param success if task completed successfully
     * @throws InvalidResourceTaskException if resource appears to be performing another task
     */
    private void completeTask(@Nonnull RemoteFilesTrackerEntity entity,
                              RemoteFilesResourceTask task,
                              boolean success) throws InvalidResourceTaskException {

        RemoteFilesResourceTask taskActual = entity.getTask();
        if (taskActual != task) {
            log.error("Attempting to complete task {} but entity {} is not performing that task", task, entity);
            throw new InvalidResourceTaskException(entity, task, taskActual);
        }

        if (entity.isRemoveForUnchangedStatus() && !success) {
            // Perhaps adding files failed or nothing was pushed, don't identify this resource as remote
            remoteFilesTrackerEntityService.delete(entity);
            return;
        }

        RemoteFilesResourceStatus status = success ? taskActual.changesStatusTo() : null;
        XnatNodeInfo xnatNodeInfo = null;
        try {
            xnatNodeInfo = getXnatNodeInfo();
        } catch (MissingXnatNodeInfoException e) {
            // We can complete a task without node info, just log the issue
            log.error("Unable to determine xnatNodeInfo, okay to ignore on task completion", e);
        }
        remoteFilesTrackerEntityService.setTaskAndStatus(entity, xnatNodeInfo,
                RemoteFilesResourceTask.None, status);
    }

    /**
     * Unlock after pull completes, update status
     * @param entity the entity
     * @param success if pull completed successfully
     * @param pullOutsideArchive true if pulling to non-archive location
     * @throws InvalidResourceTaskException if resource appears to be performing another task
     */
    private void completePull(@Nonnull RemoteFilesTrackerEntity entity,
                              boolean success,
                              boolean pullOutsideArchive) throws InvalidResourceTaskException {
        if (pullOutsideArchive) {
            if (entity.isRemoveForUnchangedStatus()) {
                // We only created an entity to block operations during pull outside archive, remove it now
                remoteFilesTrackerEntityService.delete(entity);
            }
            return;
        }
        completeTask(entity, RemoteFilesResourceTask.Pull, success);
    }

    /**
     * Unlock after pull completes, update status
     *
     * @param item item to lock
     * @param resource the resource
     * @param success if pull completed successfully
     * @throws InvalidResourceTaskException if resource is not tracked as remote or appears to be performing another task
     */
    @VisibleForTesting
    void completePull(ArchivableItem item,
                      XnatResourcecatalog resource,
                      boolean success) throws InvalidResourceTaskException {
        completeTask(item, resource, RemoteFilesResourceTask.Pull, success);
    }

    /**
     * Unlock after push completes, update status
     *
     * @param item item to lock
     * @param resource the resource
     * @param archivedFiles if push archived files
     * @throws InvalidResourceTaskException if resource is not tracked as remote or appears to be performing another task
     */
    @VisibleForTesting
    void completePush(ArchivableItem item,
                             XnatResourcecatalog resource,
                             boolean archivedFiles) throws InvalidResourceTaskException {
        completeTask(item, resource, RemoteFilesResourceTask.Push, archivedFiles);
    }

    /**
     * Unlock after add completes, update status
     *
     * @param item item to lock
     * @param resource the resource
     * @param success if add completed successfully
     * @throws InvalidResourceTaskException if resource is not tracked as remote or appears to be performing another task
     */
    @VisibleForTesting
    void completeAdd(ArchivableItem item,
                            XnatResourcecatalog resource,
                            boolean success) throws InvalidResourceTaskException {
        completeTask(item, resource, RemoteFilesResourceTask.Add, success);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RemoteFilesItemState getItemState(ArchivableItem item) {
        RemoteFilesItemState state = new RemoteFilesItemState();
        List<RemoteFilesTrackerEntity> remoteFilesTrackerEntities =
                remoteFilesTrackerEntityService.findByItemIdAndXsiType(item);
        if (remoteFilesTrackerEntities == null || remoteFilesTrackerEntities.isEmpty()) {
            // No remote files
            state.setStatus(RemoteFilesItemState.Status.Inactive);
        } else {
            boolean hasTask = false;
            boolean hasArchived = false;
            for (RemoteFilesTrackerEntity entity : remoteFilesTrackerEntities) {
                RemoteFilesItemState.Resource res =
                        state.makeResourceKey(entity.getAbstractResourceId(), entity.getResourceLabel());
                if (hasValidTask(entity)) {
                    RemoteFilesResourceTask task = entity.getTask();
                    state.addResourceWithStatus(res, task.toString());
                    hasTask = true;
                    continue;
                }
                RemoteFilesResourceStatus status = entity.getStatus();
                if (status != null) {
                    state.addResourceWithStatus(res, status.toString());
                    if (status == RemoteFilesResourceStatus.Archived) {
                        hasArchived = true;
                    }
                    continue;
                }

                // Otherwise, not locked for a task, not archived -> it's local
                state.addResourceWithStatus(res, RemoteFilesItemState.Status.Local.toString());
            }
            RemoteFilesItemState.Status status = RemoteFilesItemState.Status.Local;
            if (hasTask) {
                status = RemoteFilesItemState.Status.Locked;
            } else if (hasArchived) {
                status = RemoteFilesItemState.Status.Archived;
            }
            state.setStatus(status);
            state.sortResources();
        }
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean catalogHasRemoteFiles(XnatResourcecatalog resource) {
        // Has entry in remoteFilesTracker table and either is not local or has a task running
        RemoteFilesTrackerEntity entity;
        return (entity = remoteFilesTrackerEntityService.findByResource(resource)) != null &&
                !entityIsLocal(entity);
    }

    /**
     * If entity is in remoteFilesTracker table, is it currently local and not in a task?
     *
     * If so, calling this method will increment the "lastLocalCheck" field on the entity, which will keep it from
     * being pushed for another filesystemCleanupInterval (set in filesystems plugin preferences, min 1 day).
     *
     * @param entity the entity
     *
     * @return true if local and not running a task, false otherwise
     */
    private boolean entityIsLocal(RemoteFilesTrackerEntity entity) {
        return entityIsLocal(entity, false);
    }

    /**
     * If entity is in remoteFilesTracker table, is it currently local and not in a task?
     *
     * @param entity the entity
     * @param setLastLocalCheck if true, will increment the "lastLocalCheck" field on the entity, which will keep it
     *                          from being pushed for another filesystemCleanupInterval (set in filesystems plugin
     *                          preferences, min 1 day).
     * @return true if local and not running a task, false otherwise
     */
    private boolean entityIsLocal(RemoteFilesTrackerEntity entity, boolean setLastLocalCheck) {
        boolean localAndNotTasked = entity.getStatus() == RemoteFilesResourceStatus.Local && !hasValidTask(entity);
        if (setLastLocalCheck || localAndNotTasked) {
            remoteFilesTrackerEntityService.setLastLocalCheck(entity);
        }
        return localAndNotTasked;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canPullFile(String url, @Nullable String project) {
        if (!FileUtils.IsUrl(url, true)) {
            return false;
        }
        for (FilesystemService fs : filesystemServices) {
            try {
                fs.assertSupportsUrl(url, project, true);
                return true;
            } catch (InactiveUnpermittedOrNotWritableFilesystemException | UnsupportedUrlException | InvalidFilesystemOperationException e) {
                // Unsupported by this FS, ignore and try next
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public File pullFile(String url, String destinationPath, @Nullable String project) throws FileNotFoundException {
        if (!FileUtils.IsUrl(url, true)) {
            throw new FileNotFoundException(url + " is not a remote URL");
        }
        for (FilesystemService fs : filesystemServices) {
            try {
                return fs.pullFile(url, destinationPath, project);
            } catch (FilesystemServiceException e) {
                // Ignore, try next filesystem
            }
        }
        throw new FileNotFoundException("No configured filesystem can pull URL " + url);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean deleteFile(String url, @Nullable String project) {
        if (!FileUtils.IsUrl(url, true)) {
            return false;
        }
        for (FilesystemService fs : filesystemServices) {
            try {
                fs.deleteFile(url, project);
                return true;
            } catch (FilesystemServiceException e) {
                // Ignore, try next filesystem
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushProcessingOutputsAndAddUrlsToCatalog(final UserI user, final ArchivableItem item,
                                                         final XnatResourcecatalog catRes,
                                                         final Map<String, ? extends InputStreamSource> inputs,
                                                         final boolean preserveDirectories,
                                                         @Nullable Integer parentEventId)
            throws ClientException, ServerException, UnsupportedRemoteFilesOperationException {
        pushProcessingOutputsAndAddUrlsToCatalog(user, item, catRes, new XnatResourceInfoMap(inputs), preserveDirectories, parentEventId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pushProcessingOutputsAndAddUrlsToCatalog(UserI user, ArchivableItem item,
                                                         XnatResourcecatalog catRes, XnatResourceInfoMap resources,
                                                         boolean preserveDirectories, @Nullable Integer parentEventId)
            throws ClientException, ServerException, UnsupportedRemoteFilesOperationException {
        if (resources == null || resources.isEmpty()) {
            throw new ClientException("Nothing found to upload");
        }

        // Determine filesystem and check remote upload preference
        String project = item.getProject();
        FilesystemService fs = projectFilesystemSettingsService.getArchiverFilesystemForProject(project,
                    true);

        // Get catalog info
        CatalogUtils.CatalogData catalogData = CatalogUtils.CatalogData.getOrCreate(item, catRes);
        Path destination = Paths.get(catalogData.catPath);

        Map<String, CatalogUtils.CatalogEntryAttributes> urlMap = new HashMap<>();
        try {
            // Push files
            for (XnatResourceInfo resourceInfo : resources.values()) {
                String filepath = resourceInfo.getName();
                File file = null;
                InputStreamSource source = resourceInfo.getSource();
                if (source instanceof ByteArrayResource) {
                    file = destination.resolve(filepath).toFile();
                    org.apache.commons.io.FileUtils.copyInputStreamToFile(new ByteArrayInputStream(((ByteArrayResource) source).getByteArray()), file);
                } else if (source instanceof Resource) {
                    file = ((Resource) source).getFile();
                } else if (source instanceof MultipartFile) {
                    final MultipartFile multipartFile    = (MultipartFile) source;
                    final String        originalFilename = multipartFile.getOriginalFilename();
                    if (ZipUtils.isCompressedFile(originalFilename)) {
                        Path dir = destination.resolve(originalFilename);
                        file = dir.toFile();
                        final File tempDirectory = Files.createTempDirectory(Long.toString(Calendar.getInstance().getTimeInMillis())).toFile();
                        tempDirectory.deleteOnExit();
                        final File tempZipFile = new File(tempDirectory, originalFilename);
                        tempZipFile.deleteOnExit();
                        multipartFile.transferTo(tempZipFile);
                        Files.createDirectories(dir);
                        ZipUtils.extractFile(tempZipFile, dir);
                    } else {
                        file = destination.resolve(originalFilename).toFile();
                        org.apache.commons.io.FileUtils.copyInputStreamToFile(source.getInputStream(), file);
                    }
                }
                if (file == null) {
                    throw new IOException("Issue retrieving file from " + filepath);
                }
                if (file.isDirectory()) {
                    Path basePath = file.toPath();
                    Path pathToStrip = preserveDirectories ? basePath.getParent() : basePath;
                    uploadAndAddDirectoryFilesToUrlMap(basePath, fs, catalogData, pathToStrip, urlMap);
                } else {
                    uploadAndAddUrlAndRelPathToMap(file, fs, catalogData, file.getName(), urlMap);
                }
            }

            if (urlMap.isEmpty()) {
                throw new ClientException("No files found to upload");
            }

            // Add URLs to catalog
            addUrlsToCatalog(user, item, catalogData, parentEventId, urlMap, true, null);

        } catch (Exception e) {
            log.debug("Unable to upload files to remote catalog for {}", item.getId(), e);

            // Remove any files we uploaded so they don't cause conflicts/confusion
            for (String url : urlMap.keySet()) {
                try {
                    fs.deleteFile(url, project);
                } catch (Exception ex) {
                    // Ignore and keep trying to delete
                }
            }
            if (e instanceof ServerException) {
                throw (ServerException) e;
            } else if (e instanceof ClientException) {
                throw (ClientException) e;
            } else {
                throw new ServerException(e);
            }
        }
    }

    /**
     * Recurse through directories and run {@link #uploadAndAddUrlAndRelPathToMap(File, FilesystemService,
     * org.nrg.xnat.utils.CatalogUtils.CatalogData, String, Map)}
     *
     * @param basePath      path of base directory
     * @param fs            remote filesystem
     * @param catalogData   catalog data
     * @param pathToStrip   temp dir path to remove from files when determining relative path for catalog
     * @param urlMap        map of url to CatalogEntryAttributes used for adding to catalog
     * @throws ServerException for issues uploading to remote fs
     */
    private void uploadAndAddDirectoryFilesToUrlMap(final Path basePath, final FilesystemService fs,
                                                    final CatalogUtils.CatalogData catalogData, final Path pathToStrip,
                                                    final Map<String, CatalogUtils.CatalogEntryAttributes> urlMap)
            throws ServerException {

        final List<String> issues = new ArrayList<>();
        try {
            Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        uploadAndAddUrlAndRelPathToMap(file.toFile(), fs, catalogData,
                                pathToStrip.relativize(file).toString(), urlMap);
                    } catch (FilesystemServiceException | IOException e) {
                        issues.add("Issue uploading " + file.toAbsolutePath().toString() + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    issues.add("Issue uploading " + file.toAbsolutePath().toString() + ": " + e.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    // Ignore and log errors traversing a dir
                    if (e != null) {
                        issues.add("Error traversing " + dir + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ServerException(e);
        }

        if (!issues.isEmpty()) {
            throw new ServerException("Issue uploading files: " + issues);
        }
    }

    /**
     * Upload file to remote filesystem and add URL + unique relPath to map
     * @param file          File to upload
     * @param fs            Remote filesystem
     * @param catalogData   Catalog data
     * @param relPath       Relative Path to file within catPath
     * @param urlMap        Map of url to CatalogEntryAttributes used for adding to catalog
     * @throws InvalidFilesystemOperationException if the filesystem service cannot archive files
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     * @throws UnsupportedUrlException if url not supported by this filesystem
     * @throws RemoteApiException exceptions from remote FS API
     * @throws FileNotFoundException if file doesn't exist
     * @throws IOException if unable to obtain read lock for file
     */
    private void uploadAndAddUrlAndRelPathToMap(File file, FilesystemService fs, CatalogUtils.CatalogData catalogData,
                                                String relPath, Map<String, CatalogUtils.CatalogEntryAttributes> urlMap)
            throws IOException, InvalidFilesystemOperationException, InactiveUnpermittedOrNotWritableFilesystemException,
            RemoteApiException, UnsupportedUrlException {

        File relPathFile = new File(relPath);
        String filename = relPathFile.getName();
        //URL that's valid on filesystem
        String url = fs.makeUriFromLocal(Paths.get(catalogData.catPath, relPath).toString(), catalogData.project);

        fs.pushFile(file, url, catalogData.project, false);
        urlMap.put(url, new CatalogUtils.CatalogEntryAttributes(relPath, filename, file.length(),
                new Date(file.lastModified()), CatalogUtils.getHash(file, false), null, null));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addUrlsToCatalog(final UserI user,
                                 final ArchivableItem item,
                                 @Nonnull final CatalogUtils.CatalogData catalogData,
                                 @Nullable final Integer parentEventId,
                                 final Map<String, CatalogUtils.CatalogEntryAttributes> urlMap,
                                 boolean overwrite, @Nullable Map<String, CatalogUtils.CatalogMapEntry> catalogMap)
            throws ServerException, ClientException {

        if (catalogData.catRes == null) {
            throw new ServerException("Catalog resource not specified, cannot add urls");
        }

        long catSize = catalogData.catRes.getFileSize() == null ? 0 : (Long) catalogData.catRes.getFileSize();

        if (!startAdd(item, catalogData.catRes)) {
            throw new ServerException("Unable to add urls to resource because it is performing another task");
        }

        if (catalogMap == null) {
            catalogMap = CatalogUtils.buildCatalogMap(catalogData, true);
        }

        PersistentWorkflowI wrk = null;
        boolean hasArchivedFiles = false;
        boolean overallSuccess = false;
        try {
            final EventDetails event = new EventDetails(EventUtils.CATEGORY.DATA, EventUtils.TYPE.PROCESS,
                    "Catalog modified", "Added to catalog for resource " +
                    catalogData.catRes.getUri(), "");
            try {
                wrk = PersistentWorkflowUtils.getOrCreateWorkflowData(parentEventId, user, item.getItem(), event);
            } catch (PersistentWorkflowUtils.JustificationAbsent | PersistentWorkflowUtils.ActionNameAbsent e) {
                throw new ServerException("Unable to find or create workflow for add operation on " +
                        catalogData.catRes, e);
            }

            final Date now = Calendar.getInstance().getTime();
            final EventMetaI wrkevent = wrk.buildEvent();
            final Number eventId = wrkevent.getEventId();
            final XnatResourceInfo info = XnatResourceInfo.builder()
                    .username(user == null ? null : user.getUsername())
                    .created(now)
                    .lastModified(now)
                    .eventId(eventId)
                    .build();

            // URI or local file path -> local file path
            Map<String, String> toDelete = new HashMap<>();
            int nadded = 0;
            int nmod = 0;
            for (String url : urlMap.keySet()) {
                if (!FileUtils.IsUrl(url, true)) {
                    throw new ClientException("Invalid URL " + url);
                }
                CatalogUtils.CatalogEntryAttributes attrs = urlMap.get(url);

                CatalogUtils.CatalogMapEntry mapEntryUrl = catalogMap.get(url);
                CatalogUtils.CatalogMapEntry mapEntryRelPath = catalogMap.get(attrs.relativePath);
                boolean exists = mapEntryUrl != null || mapEntryRelPath != null;
                if (!overwrite && exists) {
                    throw new ClientException("Refusing to overwrite existing catalog entry with url " + url +
                            " and/or relative path " + attrs.relativePath);
                }
                // Add to catalog
                if (exists) {
                    if (mapEntryUrl != null && mapEntryRelPath != null && !mapEntryRelPath.entry.getUri().equals(url)) {
                        throw new ClientException("Found separate catalog entries, one with url " + url +
                                " and another with relative path " + attrs.relativePath + ", unable to determine " +
                                "which to update.");
                    }

                    // Overwrite existing entry
                    CatEntryI entry = mapEntryUrl != null ? mapEntryUrl.entry : mapEntryRelPath.entry;
                    CatalogUtils.CatalogEntryPathInfo entryInfo = new CatalogUtils.CatalogEntryPathInfo(entry,
                            catalogData.catPath);

                    if (!entry.getUri().equals(url)) {
                        // If the uri is different, then either the file is local or we want to remove the old URI (if we can)
                        toDelete.put(entryInfo.entryPath, entryInfo.entryPathDest);
                    } else {
                        // Otherwise, we just want to remove any conflicting local copy of the file
                        toDelete.put(entryInfo.entryPathDest, entryInfo.entryPathDest);
                    }
                    catSize -= CatalogUtils.getCatalogEntrySize(entry);
                    // Update entry for this new file
                    CatalogUtils.updateExistingCatEntry(entry, url, attrs, wrkevent);
                    nmod++;
                } else {
                    CatalogUtils.populateAndAddCatEntry(catalogData.catBean, url, attrs, info);
                    nadded++;
                }
                catSize += attrs.size;
            }

            Integer fileCount = catalogData.catRes.getFileCount();
            fileCount = (fileCount == null) ? nadded : fileCount + nadded;

            Map<String, Map<String, Integer>> auditSummary = new HashMap<>();
            if (nadded > 0) {
                CatalogUtils.addAuditEntry(auditSummary, Integer.parseInt(eventId.toString()), now,
                        ChangeSummaryBuilderA.ADDED, nadded);
            }
            if (nmod > 0) {
                CatalogUtils.addAuditEntry(auditSummary, Integer.parseInt(eventId.toString()), now,
                        ChangeSummaryBuilderA.MODIFIED, nmod);
            }
            CatalogUtils.saveUpdatedCatalog(catalogData, auditSummary, catSize, fileCount, wrkevent, user);
            hasArchivedFiles = true;

            for (String uri : toDelete.keySet()) {
                if (FileUtils.IsUrl(uri, true)) {
                    deleteFile(uri, catalogData.project);
                }
                // delete local file
                Files.deleteIfExists(Paths.get(toDelete.get(uri)));
            }
            overallSuccess = true;
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ServerException("An error occurred during the add operation: " + e.getMessage(), e);
        } finally {
            if (parentEventId == null && wrk != null) {
                try {
                    if (overallSuccess) {
                        WorkflowUtils.complete(wrk, wrk.buildEvent());
                    } else {
                        WorkflowUtils.fail(wrk, wrk.buildEvent());
                    }
                } catch (Exception e){
                    log.error("Unable to save workflow {}", wrk, e);
                }
            }
            completeAdd(item, catalogData.catRes, hasArchivedFiles);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pullItem(final ArchivableItem item,
                         final List<XnatAbstractresourceI> resources,
                         @Nullable String archiveRelativeDir,
                         @Nullable String destinationDir) throws ServerException {

        Map<Integer, Future<Boolean>> pullJobs = initiatePullItem(item, resources, archiveRelativeDir, destinationDir);

        boolean success = true;
        StringJoiner msg = new StringJoiner(", ");
        for (Integer resId : pullJobs.keySet()) {
            Future<Boolean> job = pullJobs.get(resId);
            try {
                if (job == null) {
                    // Pull could not be initiated
                    throw new RemoteFilesOperationException("resId " + resId + " is in another task " +
                            "or the xnat_node_info for the server couldn't be determined");
                }
                //Block until this pull job completes; order doesn't matter since we'll wait on the longest running one
                Boolean jobSuccess = job.get();
                if (jobSuccess == null || !jobSuccess) {
                    // Pull failed
                    throw new RemoteFilesOperationException("pull job failed for resId " + resId);
                }
            } catch (InterruptedException | ExecutionException | RemoteFilesOperationException e) {
                // Note the failure but still block on other jobs so we don't leave things in a weird state
                // (e.g., user might re-request pull which would then fail because of the previous request still running)
                success = false;
                log.error(e.getMessage(), e);
                msg.add(e.getMessage());
            }
        }
        if (!success) {
            throw new ServerException("Unable to pull some or all resources for item " + item.getId() + ": " + msg);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, Future<Boolean>> initiatePullItem(ArchivableItem item,
                                                          List<XnatAbstractresourceI> resources,
                                                          @Nullable String archiveRelativeDir,
                                                          @Nullable String destinationDir)
            throws ServerException {

        if (StringUtils.isBlank(archiveRelativeDir)) {
            try {
                archiveRelativeDir = item.getExpectedCurrentDirectory().getAbsolutePath();
            } catch (BaseXnatExperimentdata.UnknownPrimaryProjectException | InvalidArchiveStructure e) {
                throw new ServerException("Cannot determine archive path for item " + item.getId());
            }
        }

        final String project = item.getProject();
        final String archiveRelativeDirResolved = archiveRelativeDir;
        final String destinationDirResolved = StringUtils.defaultIfBlank(destinationDir,
                archiveRelativeDirResolved);
        final boolean pullOutsideArchive = !destinationDirResolved.equals(archiveRelativeDirResolved);

        Map<Integer, Future<Boolean>> pullJobUpdaters = new HashMap<>();
        for (XnatAbstractresourceI res : resources) {
            if (!(res instanceof XnatResourcecatalog)) {
                log.trace("{} {} ({}) is not a catalog resource", item.getId(), res.getLabel(),
                        res.getXnatAbstractresourceId());
                continue;
            }
            XnatResourcecatalog catRes = (XnatResourcecatalog) res;

            XnatNodeInfo xnatNodeInfo = null;
            try {
                xnatNodeInfo = getXnatNodeInfo();
            } catch (MissingXnatNodeInfoException e) {
                // We're ok if this entity doesn't have xnatNodeInfo bc we're not setting a task, just a lastLocalCheck
                log.error("Unable to determine xnatNodeInfo, okay to ignore on task completion", e);
            }

            RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findOrCreate(item, catRes,
                    xnatNodeInfo, RemoteFilesResourceStatus.Local);

            // The following check will block a push, so even if we're pulling outside archive (meaning the entity's
            // status & task won't change), we can be sure files won't be removed on us
            final boolean isLocal = entityIsLocal(entity, true);

            if (isLocal && !pullOutsideArchive) {
                // Already local and pulling into the archive
                if (entity.isRemoveForUnchangedStatus()) {
                    // We only created an entity to block operations during pull outside archive, remove it now
                    remoteFilesTrackerEntityService.delete(entity);
                }
                continue;
            }

            if (!startPull(item, catRes, pullOutsideArchive)) {
                //TODO do we want to try to cancel a running push so we can pull?
                log.debug("Cannot initiate pull for {} due to other task or missing xnat_node_info for server", entity);
                pullJobUpdaters.put(catRes.getXnatAbstractresourceId(), null);
                continue;
            }

            try {
                pullJobUpdaters.put(catRes.getXnatAbstractresourceId(),
                        executorService.submit(() -> pullItemResource(catRes, project,
                                archiveRelativeDirResolved, destinationDirResolved, pullOutsideArchive)));
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                completePull(entity, false, pullOutsideArchive);
                pullJobUpdaters.put(catRes.getXnatAbstractresourceId(), CompletableFuture.completedFuture(false));
            }
        }
        return pullJobUpdaters;
    }

    /**
     * Performs resource pull (initiates pull on external filesystems' executor service, polls it on its thread)
     * Expected to run via executor service.
     * @param resource the resource
     * @param project the project
     * @param archiveRelativeDir archive location for the resource
     * @param destinationDir destination
     * @return true for success
     */
    private Boolean pullItemResource(@Nonnull XnatResourcecatalog resource,
                                     @Nonnull String project,
                                     @Nonnull String archiveRelativeDir,
                                     @Nonnull String destinationDir,
                                     final boolean pullOutsideArchive)
            throws RemoteFilesOperationException, ServerException {

        RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource(resource);
        if (entity == null) {
            // Should never happen
            throw new RuntimeException("Remote resource must have a remote files tracker entity");
        }

        try {
            final CatalogUtils.CatalogData catalogData = CatalogUtils.CatalogData.getOrCreate(archiveRelativeDir,
                    resource, project);
            String catDestPath = destinationDir;
            if (!archiveRelativeDir.equals(catalogData.catPath)) {
                // Get path of this resource relative to the archiveRelativeDir, append this to the destinationDir
                catDestPath = Paths.get(destinationDir)
                        .resolve(Paths.get(archiveRelativeDir).relativize(Paths.get(catalogData.catPath)))
                        .toString();
            }

            if (pullOutsideArchive) {
                // We only want to pull files that aren't in the archive, so copy archive into destination first
                try {
                    org.apache.commons.io.FileUtils.copyDirectory(new File(catalogData.catPath), new File(catDestPath));
                } catch (IOException e) {
                    throw new RemoteFilesOperationException("Error copying " + catalogData.catPath + " to " + catDestPath +
                            " in preparation for remote file pull", e);
                }
            } else {
                remoteFilesTrackerEntityService.setPullProgress(entity,0.0);
            }

            List<CatEntryI> entriesToPull = getEntriesNeedingPull(catalogData.catBean, catalogData.catPath, catDestPath);
            if (entriesToPull.isEmpty()) {
                // All files are local. (This can happen even if status indicates otherwise when files are all pulled
                // individually rather than via resource, hence we need this additional check.)
                // Note that because we ran entityIsLocal, the auto-archival will be blocked from archiving this
                // resource for another filesystemCleanupInterval, so we're safe to return.
                completePull(entity, true, pullOutsideArchive);
                return true;
            }

            // This *cannot* be parallelized within a given resource, or else we risk multiple filesystems trying to
            // pull the same file (if somehow multiple filesystems could HEAD it, which is perhaps unlikely).
            final Map<FilesystemService, Object> trackerMap = new HashMap<>();
            for (FilesystemService fs : filesystemServices) {
                // This operation is slow, it HEADs any potential remote files
                final Object tracker = fs.initiatePullResourceFiles(catalogData.catPath, catDestPath,
                        catalogData.project, entriesToPull);
                if (tracker != null) {
                    trackerMap.put(fs, tracker);
                }
            }

            if (trackerMap.isEmpty()) {
                throw new RemoteFilesOperationException("Resource " + resource.getUri() + " has remote files, but no " +
                        "configured filesystems can pull them");
            }

            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 21600000) {
                // Update pull progress until pull completes or 6 hours elapse
                double progress = 0;
                if (!pullOutsideArchive && entityIsLocal(entity)) {
                    progress = 100;
                } else {
                    for (FilesystemService fs : trackerMap.keySet()) {
                        Object tracker = trackerMap.get(fs);
                        progress += fs.pollPullResource(tracker) / trackerMap.size();
                    }
                }

                log.trace("Progress for {} is {}", entity, progress);

                // If something unexpected happens when computing progress, log it and throw exception
                if (progress > 100 || progress < 0) {
                    throw new RemoteFilesOperationException("Unexpected value " + progress +
                            " for pull progress for entity " + entity);
                }

                if (progress == 100) {
                    completePull(entity, true, pullOutsideArchive);
                    return true;
                }

                if (!pullOutsideArchive) {
                    // Only track pull progress if pulling into archive
                    remoteFilesTrackerEntityService.setPullProgress(entity, progress);
                }

                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
            throw new RemoteFilesOperationException("Pull didn't complete in 6 hours");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            completePull(entity, false, pullOutsideArchive);
            throw e;
        }
    }

    /**
     * List of catalog entries needing pull (a.k.a., entry whose corresponding file isn't local)
     * @param cat           the catalog bean
     * @param catPath       the catalog path
     * @param destCatPath   the destination
     * @return the list
     */
    private List<CatEntryI> getEntriesNeedingPull(CatCatalogI cat,
                                                  String catPath,
                                                  @Nullable String destCatPath) {

        List<CatEntryI> targets = new ArrayList<>();

        for (CatCatalogI subset : cat.getSets_entryset()) {
            targets.addAll(getEntriesNeedingPull(subset, catPath, destCatPath));
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            CatalogUtils.CatalogEntryPathInfo info = new CatalogUtils.CatalogEntryPathInfo(entry, catPath, destCatPath);

            // If file is not in destination, add to list
            if (!Files.exists(Paths.get(info.entryPathDest))) {
                targets.add(entry);
            }
        }

        return targets;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double monitorPullProgress(XnatResourcecatalog resource) throws ServerException {
        RemoteFilesTrackerEntity entity = remoteFilesTrackerEntityService.findByResource(resource);
        if (entity == null || entityIsLocal(entity)) {
            // Assume local if we don't have an entry for it or (clearly) if it IS local
            return 100;
        }
        Double progress = entity.getPullProgress();

        if (progress == null) {
            if (entity.getTask() == RemoteFilesResourceTask.Pull) {
                return -1;
            } else{
                throw new ServerException("Pull failed, direct admin to filesystems.log");
            }
        }
        return progress;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Async
    public void pushItem(final ArchivableItem item,
                         final List<XnatAbstractresourceI> resources,
                         final UserI user,
                         @Nonnull final FilesystemService filesystemService,
                         final int cleanupInterval) {

        AtomicReference<WorkflowInfo> info = new AtomicReference<>();
        boolean success = true;
        List<String> msgs = new ArrayList<>();
        try {
            for (XnatAbstractresourceI res : resources) {
                if (!(res instanceof XnatResourcecatalog)) {
                    continue;
                }
                final XnatResourcecatalog catRes = (XnatResourcecatalog) res;

                MutableBoolean firstPush = new MutableBoolean(false);
                if (!startPush(item, catRes, user, cleanupInterval, firstPush)) {
                    log.trace("Item {} resource {} ({}) cannot be pushed due to other task",
                            item.getId(), res.getLabel(), res.getXnatAbstractresourceId());
                    continue;
                }

                boolean result = pushItemResource(item, catRes, cleanupInterval, filesystemService, user, info,
                        firstPush.booleanValue());

                if (!result) {
                    msgs.add("Unable to push " + catRes.getUri());
                    success = false;
                }
            }
        } catch (Exception e) {
            msgs.add(e.getMessage());
            log.error("Issue cleaning item {}", item.getId(), e);
            success = false;
        }

        WorkflowInfo wrkInfo;
        if ((wrkInfo = info.get()) != null) {
            PersistentWorkflowI wrk = wrkInfo.wrk;
            try {
                if (success) {
                    WorkflowUtils.complete(wrk, wrk.buildEvent());
                } else {
                    wrk.setDetails(String.join("; ", msgs));
                    WorkflowUtils.fail(wrk, wrk.buildEvent());
                }
            } catch (Exception e) {
                log.error("Issue ending workflow {}", wrk, e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initiatePushItem(final ArchivableItem item,
                                 final List<XnatAbstractresourceI> resources,
                                 final UserI user)
            throws ClientException {

        String project = item.getProject();
        final FilesystemService fs;
        try {
            fs = projectFilesystemSettingsService.getArchiverFilesystemForProject(project,
                    false);
        } catch (UnsupportedRemoteFilesOperationException e) {
            throw new ClientException(e.getMessage(), e);
        }

        List<String> messages = new ArrayList<>();
        Map<XnatResourcecatalog, Boolean> resourcesInPush = new HashMap<>();
        for (XnatAbstractresourceI res : resources) {
            if (!(res instanceof XnatResourcecatalog)) {
                continue;
            }
            MutableBoolean firstPush = new MutableBoolean(false);
            if (!startPush(item, (XnatResourcecatalog) res, user, null, firstPush)) {
                messages.add("Item " + item.getId() + " resource " + res.getLabel() +
                        " (" + res.getXnatAbstractresourceId() + ") cannot be pushed due to other task");
            } else {
                resourcesInPush.put((XnatResourcecatalog) res, firstPush.booleanValue());
            }
        }

        if (!messages.isEmpty()) {
            for (XnatResourcecatalog catRes : resourcesInPush.keySet()) {
                // remove push status since we're going to abort
                completePush(item, catRes, false);
            }
            throw new ClientException(String.join("; ", messages));
        }

        if (resourcesInPush.isEmpty()) {
            throw new ClientException("No resources to push");
        }

        AtomicReference<WorkflowInfo> info = new AtomicReference<>();
        for (XnatResourcecatalog catRes : resourcesInPush.keySet()) {
            executorService.submit(() ->
                    pushItemResource(item, catRes, null, fs, user, info, resourcesInPush.get(catRes)));
        }
    }

    /**
     * Push item resource to external filesystem
     * @param item              the item
     * @param catRes            the resource
     * @param cleanupInterval   the number of days after last access that a file can remain in the archive
     *                          (not be pushed) or null to skip this check
     * @param filesystemService the filesystem to which we'll archive any "stale" files (files with
     *                          last access time + cleanupInterval < now)
     * @param user              the user performing the push
     * @param info              workflow info object (shared amongst all item's resources)
     * @param firstPush         is this the first push to remote for this resource?
     * @return success (true if no errors, false otherwise)
     */
    private boolean pushItemResource(final ArchivableItem item,
                                     final XnatResourcecatalog catRes,
                                     @Nullable final Integer cleanupInterval,
                                     final FilesystemService filesystemService,
                                     final UserI user,
                                     final AtomicReference<WorkflowInfo> info,
                                     boolean firstPush) {
        boolean success = true;
        boolean archived = false;
        try {
            // Get catalog info
            CatalogUtils.CatalogData catalogData = CatalogUtils.CatalogData.getOrCreate(item, catRes);
            final Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData);

            // Archive all archivable files (not hidden, catalogs, snapshots, etc) last accessed prior to cutoff
            final FileTime cutoff = cleanupInterval != null
                    ? FileTime.fromMillis(ZonedDateTime.now().minusDays(cleanupInterval).toInstant().toEpochMilli())
                    : null;
            final AtomicBoolean markModified = new AtomicBoolean(false);
            final AtomicInteger failureCount = new AtomicInteger(0);

            final Path catalogPath = Paths.get(catalogData.catPath);

            Map<Path, Future<Boolean>> results = new HashMap<>();
            Files.walkFileTree(catalogPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    results.put(file, executorService.submit(() ->
                            pushFile(file, attrs, catalogData, cleanupInterval, cutoff, catalogPath, catalogMap,
                                    filesystemService, info, item, user, firstPush, markModified, failureCount)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // Skip files that can't be traversed
                    log.error("Skipped {}", file, e);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    // Ignore and log errors traversing a dir
                    if (e != null) {
                        log.error("Error traversing {}", dir, e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // wait for all file pushing to complete
            boolean markArchived = false;
            List<Path> deleteAfterCatalogSave = new ArrayList<>();
            for (Path file : results.keySet()) {
                if (results.get(file).get()) {
                    deleteAfterCatalogSave.add(file);
                    markArchived = true;
                }
            }

            if (markModified.get()) {
                CatalogUtils.writeCatalogToFile(catalogData, false);
            }

            // Only set this if we don't handle an exception writing the catalog
            archived = markArchived;

            for (Path file : deleteAfterCatalogSave) {
                Files.deleteIfExists(file);
            }
        } catch (Exception e) {
            log.error("Issue cleaning item {} resource {} ({})", item.getId(), catRes.getLabel(),
                    catRes.getXnatAbstractresourceId(), e);
            success = false;
        } finally {
            try {
                completePush(item, catRes, archived);
            } catch (Exception e) {
                log.error("Unable to complete push", e);
            }
        }

        return success;
    }

    private boolean pushFile(Path file,
                             BasicFileAttributes attrs,
                             CatalogUtils.CatalogData catalogData,
                             @Nullable Integer cleanupInterval,
                             FileTime cutoff,
                             Path catalogPath,
                             Map<String, CatalogUtils.CatalogMapEntry> catalogMap,
                             FilesystemService filesystemService,
                             AtomicReference<WorkflowInfo> info,
                             ArchivableItem item,
                             UserI user,
                             boolean firstPush,
                             AtomicBoolean markModified,
                             AtomicInteger failureCount) {

        if (failureCount.get() >= maxConsecutiveFailures) {
            return false;
        }

        File f = file.toFile();

        // Files to ignore (not archive): catalog files, hidden files, SNAPSHOTS, viewer metadata
        String absPath;
        if (f.equals(catalogData.catFile) || f.isHidden() ||
                (absPath = f.getAbsolutePath()).contains("SNAPSHOTS") || absPath.contains("metadata")) {
            return false;
        }

        // Ignore if cleanupInterval set but last accessed within cutoff
        if (cleanupInterval != null && cutoff.compareTo(attrs.lastAccessTime()) < 0) {
            return false;
        }

        // If file was last accessed prior to cutoff, archive it
        String uri = null;
        try {
            final String relative = catalogPath.relativize(file).toString();

            CatEntryI entry = catalogMap.get(relative) == null ? null : catalogMap.get(relative).entry;
            if (entry == null || !FileUtils.IsUrl((uri = entry.getUri()), true)) {
                // If it's a local file, make it a URL in the catalog
                uri = makeUrlForCatalog(filesystemService, info, item, user, f, catalogData, entry,
                        relative, markModified);
            }
            try {
                filesystemService.pushFile(f, uri, catalogData.project, firstPush);
            } catch (FilesystemServiceException e) {
                // If the uri cannot be pushed with this filesystem service, see if there's a another
                // that can handle it. If not, update it so it can be archived to this filesystem.
                for (FilesystemService fs : filesystemServices) {
                    if (fs.supportsUrl(uri, catalogData.project, true, true)) {
                        // Handled elsewhere, rethrow exception to skip delete
                        throw e;
                    }
                }
                // No other archiver filesystem can handle this file, treat it like a local file
                uri = makeUrlForCatalog(filesystemService, info, item, user, f, catalogData, entry,
                        relative, markModified);
                filesystemService.pushFile(f, uri, catalogData.project, firstPush);
            }

            return true;
        } catch (Exception e) {
            log.error("Unable to archive {} to {}", f.getAbsolutePath(), uri, e);
            failureCount.incrementAndGet();
            return false;
        }
    }

    /**
     * Make URL for file and update catalog entry
     * @param filesystemService filesystem service for which URL will be made
     * @param info workflow info
     * @param item the archivable item
     * @param user the user
     * @param f the file
     * @param catalogData data about the catalog
     * @param entry the catalog entry
     * @param relative the relative path
     * @param markModified atomic boolean to mark the catalog modified
     * @return the URI
     * @throws InvalidFilesystemOperationException if filesystemService cannot make a uri
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem is not active or project not permitted to use it
     */
    private String makeUrlForCatalog(FilesystemService filesystemService, AtomicReference<WorkflowInfo> info,
                                     ArchivableItem item, UserI user, File f, CatalogUtils.CatalogData catalogData,
                                     CatEntryI entry, String relative, AtomicBoolean markModified)
            throws InvalidFilesystemOperationException, InactiveUnpermittedOrNotWritableFilesystemException {
        checkWorkflowInfo(info, item, user);
        WorkflowInfo wrkInfo = info.get();
        String uri = filesystemService.makeUriFromLocal(f.getAbsolutePath(), item.getProject());
        CatalogUtils.addOrUpdateEntry(catalogData, entry, uri, relative, f, wrkInfo.resInfo,
                wrkInfo.eventMeta);
        markModified.set(true);
        return uri;
    }

    private static class WorkflowInfo {
        PersistentWorkflowI wrk;
        EventMetaI eventMeta;
        XnatResourceInfo resInfo; // resource info object, used if we add a new file
    }

    /**
     * Create WorkflowInfo object if one hasn't already been created
     * @param atomicWrkInfo  atomic WorkflowInfo object
     * @param item          the item
     * @param user          the user
     */
    private void checkWorkflowInfo(AtomicReference<WorkflowInfo> atomicWrkInfo, ArchivableItem item, UserI user) {
        if (atomicWrkInfo.get() != null) {
            return;
        }

        synchronized (this) {
            try {
                WorkflowInfo wrkInfo = new WorkflowInfo();
                EventDetails event = EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.REST,
                        "Catalog(s) updated",
                        "Push to remote", "");
                wrkInfo.wrk = PersistentWorkflowUtils.buildOpenWorkflow(user, item.getItem(), event);
                wrkInfo.eventMeta = wrkInfo.wrk.buildEvent();
                // Make resource info object, used if we add a new file
                wrkInfo.resInfo = XnatResourceInfo.builder()
                        .username(user == null ? null : user.getUsername())
                        .eventId(wrkInfo.eventMeta.getEventId())
                        .created(wrkInfo.eventMeta.getEventDate())
                        .lastModified(wrkInfo.eventMeta.getEventDate())
                        .build();

                atomicWrkInfo.set(wrkInfo);
            } catch (PersistentWorkflowUtils.EventRequirementAbsent e) {
                log.error("Unable to create workflow", e);
            }
        }
    }


}
