// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.aws.s3.services;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3URI;
import com.amazonaws.services.s3.internal.Mimetypes;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.transfer.*;
import com.amazonaws.util.Base64;
import com.amazonaws.util.BinaryUtils;
import com.amazonaws.util.Md5Utils;
import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.exceptions.*;
import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.model.entity.FilesystemObjectInfo;
import com.radiologics.filesystems.model.entity.RemoteFilesPathInfo;
import com.radiologics.filesystems.services.AbstractFilesystemService;
import com.radiologics.filesystems.services.FilesystemService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.magicwerk.brownies.collections.BigList;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.utils.CatalogUtils;
import org.nrg.xnat.utils.ThreadAndProcessFileLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AwsS3FilesystemService extends AbstractFilesystemService {
    String separator = "/";

    private final boolean isPrimaryNode;
    private AwsS3ConfigEntityService awsS3ConfigEntityService;
    private static List<String> SUPPORTED_PROTOCOLS =
            Collections.unmodifiableList(Arrays.asList(AmazonS3.ENDPOINT_PREFIX, "https", "http"));

    private final Map<Long, String> cachedBucketsIdMap = new HashMap<>();
    private final Map<String, AwsS3Config> bucketS3ClientMap = new HashMap<>();
    private final Map<String, String> projectArchiverMap = new HashMap<>();
    private final AtomicReference<Date> lastCacheUpdate = new AtomicReference<>();
    private long cacheRefreshIntervalMs = TimeUnit.HOURS.toMillis(12); // Every 12 hours

    private static String MD5_KEY = "md5";

    private static class AwsS3ObjectInfo extends FilesystemObjectInfo {
        @Nonnull AwsS3Config config;
        @Nonnull String key;

        AwsS3ObjectInfo(@Nonnull AwsS3Config config, @Nonnull String key) {
            this.config = config;
            this.key = key;
        }
    }

    @Autowired
    public AwsS3FilesystemService(final XnatAppInfo xnatAppInfo,
                                  final AwsS3ConfigEntityService awsS3ConfigEntityService,
                                  final SiteConfigPreferences siteConfigPreferences,
                                  @Qualifier("filesystemsThreadPoolExecutorFactoryBean")
                                      final ThreadPoolExecutorFactoryBean filesystemsThreadPoolExecutorFactoryBean) {
        super(siteConfigPreferences, filesystemsThreadPoolExecutorFactoryBean);
        this.isPrimaryNode = xnatAppInfo.isPrimaryNode();
        this.awsS3ConfigEntityService = awsS3ConfigEntityService;
        refreshCache(true);
    }

    /**
     * Constructor to use this class outside XNAT for interacting with S3
     * @param config the activated AWS S3 configuration
     */
    public AwsS3FilesystemService(AwsS3Config config) {
        super();
        isPrimaryNode = true; // block cache refresh from db
        lastCacheUpdate.set(new Date()); 
        refresh(config);
    }

    /**
     * Refresh internal state pertaining to config
     * @param config the config POJO
     */
    public synchronized void refresh(AwsS3Config config) {
        refresh(Collections.singletonList(config));
    }

    /**
     * Remove filesystem config from cache by id
     * @param id filesystem config id
     */
    public synchronized void deactivate(long id) {
        // remove from maps
        String bucketName = cachedBucketsIdMap.remove(id);
        if (bucketName == null) {
            return;
        }
        bucketS3ClientMap.remove(bucketName);
        while (projectArchiverMap.values().remove(bucketName));
    }

    /**
     * Add filesystem config to cache
     * @param config the config POJO
     */
    public synchronized void activate(AwsS3Config config) {
        String bucket = config.getBucketName();
        cachedBucketsIdMap.put(config.getId(), bucket);
        bucketS3ClientMap.put(bucket, config);
        for (ProjectFilesystemSettings ps : config.getArchivesForProjects()) {
            projectArchiverMap.put(ps.getProjectId(), bucket);
        }
    }

    /**
     * Refresh cache for configs in list
     * @param configs list of config POJOs
     */
    private synchronized void refresh(@Nullable List<AwsS3Config> configs) {
        if (configs == null) {
            return;
        }
        for (AwsS3Config config : configs) {
            deactivate(config.getId());
            activate(config);
        }
    }

    /**
     * Remove filesystem configs from cache by ids
     * @param ids set of ids to remove
     */
    private synchronized void deactivateDeleted(Set<Long> ids) {
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            deactivate(id);
        }
    }

    /**
     * Refresh internal cache
     * @param init is this happening upon initialization?
     */
    private synchronized void refreshCache(boolean init) {
        boolean refreshAll = init ||
                System.currentTimeMillis() - lastCacheUpdate.get().getTime() > cacheRefreshIntervalMs;
        if (!init) {
            if (isPrimaryNode && !refreshAll) {
                // Updates to cache corresponding to changes in FS config happen "live" on primary node.
                return;
            }
            // Make a copy so we aren't modifying the actual map
            Set<Long> currentIds = new HashSet<>(cachedBucketsIdMap.keySet());
            deactivateDeleted(awsS3ConfigEntityService.queryDeleted(currentIds));
        }
        refresh(awsS3ConfigEntityService.queryRefreshAndReturnPojos(lastCacheUpdate, refreshAll));
    }

    /**
     * Update permitted projects for activated config (shortcut to avoid reactivating when creds haven't changed)
     * @param config the POJO with updated project perms
     * @throws InvalidEntityException if ids don't match
     */
    public synchronized void updateProjectsForConfig(AwsS3Config config) throws InvalidEntityException {
        String bucket = config.getBucketName();
        AwsS3Config existingConfig = bucketS3ClientMap.get(bucket);
        if (existingConfig == null) {
            // Shouldn't happen
            return;
        }
        existingConfig.updatePermittedProjects(config);
        bucketS3ClientMap.put(bucket, existingConfig);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void updateProjectArchiver(String project, @Nullable Long archiverId)
            throws InvalidEntityException {
        refreshCache(false);
        if (archiverId == null) {
            projectArchiverMap.remove(project);
        } else {
            String bucketName = cachedBucketsIdMap.get(archiverId);
            if (bucketName == null) {
                // Not relevant to our filesystem
                throw new InvalidEntityException("Archiver id " + archiverId + " not found in cache.");
            }
            projectArchiverMap.put(project, bucketName);
            // Rather than renewing the whole config, just add
            bucketS3ClientMap.get(bucketName).addPermittedProject(project);
        }
    }

    /**
     * Get AwsS3Config POJO for bucketName and project
     * @param bucketName the bucket name
     * @param project the project
     * @return the config
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if no config or project can't access it
     */
    @Nonnull
    private synchronized AwsS3Config getConfigFromMap(String bucketName, @Nullable String project)
            throws InactiveUnpermittedOrNotWritableFilesystemException {
        refreshCache(false);
        AwsS3Config config = bucketS3ClientMap.get(bucketName);
        // If not permit all AND no project specified or if project not allowed to use this bucket, return null
        if (config == null || !config.isActive()) {
            throw new InactiveUnpermittedOrNotWritableFilesystemException(this);
        }
        if (!config.isPermitAllProjects() && (project == null || !config.getPermittedProjects().contains(project))) {
            throw new InactiveUnpermittedOrNotWritableFilesystemException(config, project);
        }
        return config;
    }

    /**
     * Get AwsS3Config POJO for archiving project
     * @param project the project
     * @return the config
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if project not configured to archive or no config
     */
    @Nonnull
    private synchronized AwsS3Config getArchiverConfigFromMap(@Nullable String project)
            throws InactiveUnpermittedOrNotWritableFilesystemException {
        refreshCache(false);
        String bucket;
        AwsS3Config config;
        if (project == null || (bucket = projectArchiverMap.get(project)) == null ||
                (config = bucketS3ClientMap.get(bucket)) == null || !config.isActive() || !config.isArchiver()) {
            throw new InactiveUnpermittedOrNotWritableFilesystemException(this, project);
        }
        return config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean servesConfig(FilesystemConfig config) {
        return config.getFilesystemType() == FilesystemConfig.FilesystemType.AWSS3 &&
                config.equals(awsS3ConfigEntityService.getPojoById(config.getId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AwsS3ObjectInfo assertSupportsUrl(String url, @Nullable String project, boolean shouldExist)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        return assertSupportsUrl(url, project, shouldExist, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AwsS3ObjectInfo assertSupportsUrl(String url, @Nullable String project, boolean shouldExist, boolean shouldBeWritable)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        if (FileUtils.IsUrl(url, true) && SUPPORTED_PROTOCOLS.contains(getUrlProtocol(url))) {
            return parseUrl(url, project, shouldExist, shouldBeWritable);
        } else {
            throw new UnsupportedUrlException(this, url);
        }
    }

    /**
     * Parse URL into AwsS3ObjectInfo
     * @param url the url
     * @param project the project
     * @return the AwsS3ObjectInfo for the URL for pulling/pushing/etc
     * @throws UnsupportedUrlException if URL not support by AWS S3
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     */
    @Nonnull
    private AwsS3ObjectInfo parseUrl(String url, @Nullable String project)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        return parseUrl(url, project, true);
    }

    /**
     * Parse URL into AwsS3ObjectInfo
     * @param url the url
     * @param project the project
     * @param shouldExist if true, will throw exception if url doesn't exist
     * @return the AwsS3ObjectInfo for the URL for pulling/pushing/etc
     * @throws UnsupportedUrlException if URL not support by AWS S3 or if url that ought to exist doesn't
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     */
    @Nonnull
    private AwsS3ObjectInfo parseUrl(String url, @Nullable String project, boolean shouldExist)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        return parseUrl(url, project, shouldExist, false);
    }

    /**
     * Parse URL into AwsS3ObjectInfo
     * @param url the url
     * @param project the project
     * @param shouldExist if true, will throw exception if url doesn't exist
     * @param shouldBeWritable if true, will throw exception if url doesn't refer to a bucket that's set as archiver
     *                         for this project
     * @return the AwsS3ObjectInfo for the URL for pulling/pushing/etc
     * @throws UnsupportedUrlException if URL not support by AWS S3 or if url that ought to exist doesn't
     * @throws InactiveUnpermittedOrNotWritableFilesystemException if filesystem for URL is inactive or project not permitted to use it
     */
    @Nonnull
    private AwsS3ObjectInfo parseUrl(String url, @Nullable String project, boolean shouldExist, boolean shouldBeWritable)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        try {
            AmazonS3URI uri = new AmazonS3URI(url); // Throws IllegalArgumentException if not S3 URI
            String bucket = uri.getBucket();
            String key = uri.getKey();
            AwsS3Config config = getConfigFromMap(bucket, project);
            if (shouldBeWritable) {
                if (!config.isArchiver()) {
                    throw new InactiveUnpermittedOrNotWritableFilesystemException(config);
                }
                AwsS3Config archiverConfig = getArchiverConfigFromMap(project);
                if (archiverConfig.getId() != config.getId()) {
                    throw new InactiveUnpermittedOrNotWritableFilesystemException(archiverConfig, config, project);
                }
            }
            if (shouldExist && !config.getS3client().doesObjectExist(bucket, key)) {
                throw new AmazonServiceException(url + " doesn't exist or isn't accessible");
            }
            return new AwsS3ObjectInfo(config, key);
        } catch (IllegalArgumentException | AmazonServiceException | RemoteApiException e) {
            throw new UnsupportedUrlException(this, url, e);
        }
    }

    /**
     * Create XNAT archive-relative path from URL, used if adding by URL and deciding where to put on XNAT.
     * This is the "mirror" operation to {@link FilesystemService#makeUriFromLocal(String, String)}, which takes
     * a local file and determines the remote URL.
     *
     * @param info the AwsS3ObjectInfo for the URL
     * @return the archive-relative path
     */
    private String getRelativePathFromRemotePath(AwsS3ObjectInfo info) {
        String bucket = info.config.getBucketName();
        String relPath = info.key;
        // Keep things unique
        relPath = FileUtils.AppendRootPath(bucket, relPath);
        return relPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    protected RemoteFilesPathInfo getRemoteFilesPathInfo(String url,
                                                         String catalogPath,
                                                         @Nullable String project)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        RemoteFilesPathInfo info = new RemoteFilesPathInfo();
        AwsS3ObjectInfo remoteInfo = parseUrl(url, project);
        String relPath = getRelativePathFromRemotePath(remoteInfo);
        info.remoteInfo = remoteInfo;
        populateLocalPathInfo(FileUtils.AppendRootPath(catalogPath, relPath), relPath, info);
        return info;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    protected RemoteFilesPathInfo getRemoteFilesPathInfo(String url,
                                                         String destinationPath,
                                                         String catalogRelativePath,
                                                         @Nullable String project)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        RemoteFilesPathInfo info = new RemoteFilesPathInfo();
        info.remoteInfo = parseUrl(url, project);
        populateLocalPathInfo(destinationPath, catalogRelativePath, info);
        return info;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public InputStream doGetInputStream(String url, @Nullable String project)
            throws RemoteApiException, UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException {
        AwsS3ObjectInfo remoteInfo = parseUrl(url, project);
        try {
            GetObjectRequest request = new GetObjectRequest(remoteInfo.config.getBucketName(), remoteInfo.key);
            return remoteInfo.config.getS3client().getObject(request).getObjectContent();
        } catch (AmazonServiceException e) {
            throw new RemoteApiException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doPullFile(File f, RemoteFilesPathInfo info, DownloadListener listener)
            throws RemoteApiException {
        try {
            AwsS3ObjectInfo remoteInfo = (AwsS3ObjectInfo) info.remoteInfo;
            Download download = remoteInfo.config.getS3transfer().download(remoteInfo.config.getBucketName(),
                    remoteInfo.key, f);
            if (listener instanceof AwsS3DownloadListener) {
                ((AwsS3DownloadListener) listener).setDownload(download);
            }
            download.waitForCompletion();
        } catch (AmazonServiceException | InterruptedException e) {
            throw new RemoteApiException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doDeleteFile(String url, @Nullable String project, @Nullable FilesystemObjectInfo info)
            throws RemoteApiException, InactiveUnpermittedOrNotWritableFilesystemException {
        if (!(info instanceof AwsS3ObjectInfo)) {
            throw new InactiveUnpermittedOrNotWritableFilesystemException(this, project);
        }
        AwsS3ObjectInfo remoteInfo = (AwsS3ObjectInfo) info;
        try {
            remoteInfo.config.getS3client().deleteObject(remoteInfo.config.getBucketName(), remoteInfo.key);
        } catch (AmazonServiceException e) {
            throw new RemoteApiException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    protected CatalogUtils.CatalogEntryAttributes doGetMetadata(RemoteFilesPathInfo info) throws RemoteApiException {
        try {
            AwsS3ObjectInfo remoteInfo = (AwsS3ObjectInfo) info.remoteInfo;
            GetObjectMetadataRequest request = new GetObjectMetadataRequest(remoteInfo.config.getBucketName(),
                    remoteInfo.key);
            ObjectMetadata md = remoteInfo.config.getS3client().getObjectMetadata(request);
            return new CatalogUtils.CatalogEntryAttributes(info.catalogRelativePath, info.name, md.getContentLength(),
                    md.getLastModified(), md.getContentMD5(), null, null);
        } catch (AmazonServiceException e) {
            throw new RemoteApiException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doPushFile(File file, String url, @Nullable String project, @Nullable FilesystemObjectInfo info,
                              boolean firstPush)
            throws RemoteApiException, InactiveUnpermittedOrNotWritableFilesystemException, IOException {
        if (!(info instanceof AwsS3ObjectInfo)) {
            throw new InactiveUnpermittedOrNotWritableFilesystemException(this, project);
        }
        AwsS3ObjectInfo remoteInfo = (AwsS3ObjectInfo) info;

        // Get metadata to check if file has changed since last upload
        ObjectMetadata mdOrig = null;
        if (!firstPush) {
            try {
                mdOrig = remoteInfo.config.getS3client().getObjectMetadata(remoteInfo.config.getBucketName(),
                        remoteInfo.key);
            } catch (Exception e) {
                // Ignore, worst case, we re-push an existing file
                log.debug("Issue with HEAD request for {}", remoteInfo.key, e);
            }
        }

        try {
            ThreadAndProcessFileLock fl = ThreadAndProcessFileLock.getThreadAndProcessFileLock(file, true);
            fl.tryLock(10L, TimeUnit.SECONDS);
            try {
                String md5Sum = CatalogUtils.getHash(file, false);
                // MD5 format Amazon uses, can be computed directly with: Md5Utils.md5AsBase64(file);
                String md5b64 = StringUtils.isNotBlank(md5Sum) ?
                        Base64.encodeAsString(BinaryUtils.fromHex(md5Sum)) : Md5Utils.md5AsBase64(file);
                // Check if file with same content already exists on S3. Content MD5 may or may not be there (depends
                // on how data was uploaded). ETag may be an MD5 sum, but not always
                // (see https://docs.aws.amazon.com/AmazonS3/latest/API/RESTCommonResponseHeaders.html)
                if (mdOrig != null && (md5Sum.equals(mdOrig.getETag()) ||
                        md5b64.equals(mdOrig.getContentMD5()) || md5Sum.equals(mdOrig.getUserMetaDataOf(MD5_KEY)))) {
                    // Already on S3 with same content
                    return;
                }
                // If we pass an input stream with an ObjectMetadata object, we avoid recomputing md5 and can add our
                // own md5 for cases where etag isn't the md5sum
                ObjectMetadata md = new ObjectMetadata();
                md.setContentType(Mimetypes.getInstance().getMimetype(file));
                md.setContentMD5(md5b64);
                md.setContentLength(file.length());
                // Sadly, we have to add the below as the value in setContentMD5 doesn't seem to be stored to S3:
                // mdOrig.getContentMD5() == null
                md.setUserMetadata(Collections.singletonMap(MD5_KEY, md5Sum));
                try (FileInputStream fis = new FileInputStream(file)) {
                    try {
                        Upload upload = remoteInfo.config.getS3transfer().upload(remoteInfo.config.getBucketName(),
                                remoteInfo.key, fis, md);
                        upload.waitForCompletion();
                        if (upload.getState() != Transfer.TransferState.Completed) {
                            throw new RemoteApiException("Upload of " + file.getAbsolutePath() + " to " + url + " failed");
                        }
                    } catch (SdkClientException | InterruptedException e) {
                        throw new RemoteApiException(e);
                    }
                }
            } finally {
                fl.unlock();
            }
        } finally {
            ThreadAndProcessFileLock.removeThreadAndProcessFileLock(file);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public String makeUriFromLocal(String absoluteLocalPath, @Nullable String project)
            throws InactiveUnpermittedOrNotWritableFilesystemException {

        AwsS3Config config = getArchiverConfigFromMap(project);

        String archiveRelativePath;
        if (siteConfigPreferences == null) {
            archiveRelativePath = absoluteLocalPath;
        } else {
            archiveRelativePath = Paths.get(siteConfigPreferences.getArchivePath())
                    .relativize(Paths.get(absoluteLocalPath)).toString();
        }

        // Used to check if file existed already and append random int, now just warn that S3 versioning should be used
        //Path path = Paths.get(archiveRelativePath);
        //String archiveRelPathParent = path.getParent().toString();
        //String name = path.getFileName().toString();
        //while (s3client.doesObjectExist(bucketName, archiveRelativePath)) {
        //    archiveRelativePath = archiveRelPathParent + separator +
        //            ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE) + "_" + name;
        //}
        String awsPath = config.getBucketName() + separator +
                StringUtils.defaultIfBlank(config.getSubdirectory(), "");
        return AmazonS3.ENDPOINT_PREFIX + "://" + StringUtils.appendIfMissing(awsPath, separator) + archiveRelativePath;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    protected DownloadListener getDownloadListenerInstance() {
        return new AwsS3DownloadListener();
    }

    private static class AwsS3DownloadListener extends DownloadListener {
        private Download download;

        @Override
        protected double computeProgress() {
            double percTransferred;
            return download == null || Double.isNaN((percTransferred = download.getProgress().getPercentTransferred()))
                    ? super.computeProgress() : percTransferred / 100.0;
        }

        void setDownload(Download download) {
            this.download = download;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public List<String> doListAllFiles(@Nullable String root, @Nullable String project)
            throws RemoteApiException, InactiveUnpermittedOrNotWritableFilesystemException, UnsupportedUrlException {

        AwsS3Config config;
        if (root == null) {
            root = "";
            config = getArchiverConfigFromMap(project);
        } else {
            AwsS3ObjectInfo info = parseUrl(root, project, false);
            config = info.config;
        }

        AmazonS3 s3client = config.getS3client();
        String bucket = config.getBucketName();
        String prepend = "";
        if (root.startsWith(AmazonS3.ENDPOINT_PREFIX)) {
            prepend = root.replaceAll(bucket + separator + ".*",
                    bucket + separator);
            root = root.replace(prepend, "");
        }

        // Strip trailing slash from root
        root = root.replaceAll(separator + "$", "");

        ListObjectsV2Request request = new ListObjectsV2Request().withBucketName(bucket)
                .withPrefix(root + separator);
        ListObjectsV2Result result;
        List<String> keys = new BigList<>();
        try {
            do {
                result = s3client.listObjectsV2(request);
                for (S3ObjectSummary obj : result.getObjectSummaries()) {
                    keys.add(prepend + obj.getKey());
                }
                request.setContinuationToken(result.getNextContinuationToken());
            } while (result.isTruncated());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        // Add directories to list
        Pattern stripLast = Pattern.compile(separator + "[^"+ separator +"]*$");
        Set<String> dirs = new HashSet<>();
        boolean newPath;
        for (String dir : keys) {
            newPath = true;
            String parent = dir;
            while (newPath && !(parent = stripLast.matcher(parent).replaceAll("")).equals(dir)) {
                // Strip last separator and anything after
                if (parent.equals(root)) {
                    break;
                }
                newPath = dirs.add(parent + separator);
            }
        }
        keys.addAll(dirs);
        return keys;
    }
}
