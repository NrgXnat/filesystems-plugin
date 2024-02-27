// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.exceptions.InactiveUnpermittedOrNotWritableFilesystemException;
import com.radiologics.filesystems.model.entity.FilesystemObjectInfo;
import com.radiologics.filesystems.model.entity.RemoteFilesPathInfo;
import com.radiologics.filesystems.exceptions.InvalidFilesystemOperationException;
import com.radiologics.filesystems.exceptions.RemoteApiException;
import com.radiologics.filesystems.exceptions.UnsupportedUrlException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xnat.utils.CatalogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
@Service
public class DefaultFilesystemService extends AbstractFilesystemService {
    @Autowired
    public DefaultFilesystemService(final SiteConfigPreferences siteConfigPreferences,
                                    @Qualifier("filesystemsThreadPoolExecutorFactoryBean")
                                        final ThreadPoolExecutorFactoryBean filesystemsThreadPoolExecutorFactoryBean) {
        super(siteConfigPreferences, filesystemsThreadPoolExecutorFactoryBean);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FilesystemObjectInfo assertSupportsUrl(String url, @Nullable String project, boolean shouldExist)
            throws UnsupportedUrlException, InvalidFilesystemOperationException {
        return assertSupportsUrl(url, project, shouldExist, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FilesystemObjectInfo assertSupportsUrl(String url, @Nullable String project, boolean shouldExist, boolean shouldBeWritable)
            throws UnsupportedUrlException, InvalidFilesystemOperationException {
        if (!FileUtils.IsUrl(url, true)) {
            throw new UnsupportedUrlException(url + " is not a remote URL.");
        }
        if (shouldBeWritable) {
            throw new InvalidFilesystemOperationException(this, "push or delete files");
        }
        if (shouldExist) {
            try {
                getUrlHeaders(url);
            } catch (IOException e) {
                throw new UnsupportedUrlException(this, url);
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    protected RemoteFilesPathInfo getRemoteFilesPathInfo(String url, String catalogPath, @Nullable String project)
            throws UnsupportedUrlException, InactiveUnpermittedOrNotWritableFilesystemException, InvalidFilesystemOperationException {

        assertSupportsUrl(url, project, false);
        RemoteFilesPathInfo info = new RemoteFilesPathInfo();
        URL urlObj;
        try {
            urlObj = new URL(url);
        } catch (MalformedURLException e) {
            throw new UnsupportedUrlException(this, url, e);
        }
        String urlPath = urlObj.getPath()
                .replaceFirst("^/", "").replaceAll("/$", "");
        info.name = urlPath.replaceFirst(".*/", "");

        String hash_type = "MD5";
        try {
            // Try to name with md5 hash of path
            MessageDigest md5 = MessageDigest.getInstance(hash_type);
            md5.update(url.getBytes());
            info.catalogRelativePath = Hex.encodeHexString(md5.digest()) + "_" + info.name;
        } catch (NoSuchAlgorithmException e) {
            log.error("Unsupported hashing algorithm {}", hash_type, e);
            //fall back on url path if digest fails
            info.catalogRelativePath = urlPath;
        }
        return makeRemoteFilesPathInfo(url, info);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    protected RemoteFilesPathInfo getRemoteFilesPathInfo(String url,
                                                         @Nullable String destinationPath,
                                                         @Nullable String catalogRelativePath,
                                                         @Nullable String project)
            throws UnsupportedUrlException, InvalidFilesystemOperationException {

        RemoteFilesPathInfo info = makeRemoteFilesPathInfo(url, null);
        populateLocalPathInfo(destinationPath, catalogRelativePath, info);
        return info;
    }


    @Nonnull
    private RemoteFilesPathInfo makeRemoteFilesPathInfo(String url, @Nullable RemoteFilesPathInfo info)
            throws UnsupportedUrlException, InvalidFilesystemOperationException {
        assertSupportsUrl(url, null, true);
        if (info == null) info = new RemoteFilesPathInfo();
        info.remoteInfo = url;
        return info;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    protected CatalogUtils.CatalogEntryAttributes doGetMetadata(RemoteFilesPathInfo info) throws RemoteApiException {
        try {
            return downloadUrlHeaders(info);
        } catch (IOException e) {
            throw new RemoteApiException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doPullFile(File f, RemoteFilesPathInfo info, DownloadListener listener) throws RemoteApiException {
        f.getParentFile().mkdirs();
        try {
            downloadUrl((String) info.remoteInfo, f);
        } catch (IOException e) {
            throw new RemoteApiException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public InputStream doGetInputStream(String url, @Nullable String project) throws RemoteApiException {
        try {
            return streamUrl(url);
        } catch (IOException e) {
            throw new RemoteApiException(e);
        }
    }

    /**
     * Get input stream for url
     * @param url the url
     * @return the input stream
     * @throws IOException if url is invalid or input stream
     */
    private InputStream streamUrl(String url) throws IOException {
        URLConnection connection;
        connection = openURLConnectionWithRedirects(new URL(url));
        return connection.getInputStream();
    }

    private URLConnection openURLConnectionWithRedirects(URL url) throws IOException {
        return openURLConnectionWithRedirects(url, null);
    }

    /**
     * Opens a URL connection, following redirects. Also uses a hack to imitate a CLI user agent.
     *
     * @param url           URL string
     * @param requestMethod HEAD, etc
     * @return URLConnection
     * @throws IOException for errors
     */
    private URLConnection openURLConnectionWithRedirects(URL url, String requestMethod) throws IOException {
        int redirects = 0;
        URLConnection connection = url.openConnection();
        while (redirects <= 5) {
            redirects++;
            if (connection instanceof HttpURLConnection) {
                if (StringUtils.isNotEmpty(requestMethod)) {
                    ((HttpURLConnection) connection).setRequestMethod(requestMethod);
                }
                ((HttpURLConnection) connection).setInstanceFollowRedirects(true); //Doesn't work https<->http
                long contentLength = connection.getContentLength();
                if (contentLength == -1) {
                    connection = url.openConnection();
                    // Dropbox (maybe others?) try to give the java user agent a file preview
                    // but, it understands that cURL just wants the file, as do we.
                    connection.addRequestProperty("User-Agent", "curl/7.61.0");
                    continue;
                }
                int responseCode = ((HttpURLConnection) connection).getResponseCode();
                switch (responseCode) {
                    case HttpURLConnection.HTTP_OK:
                        break;
                    case HttpURLConnection.HTTP_MOVED_TEMP:
                    case HttpURLConnection.HTTP_MOVED_PERM:
                        String location = connection.getHeaderField("Location");
                        if (location != null) {
                            url = new URL(connection.getURL(), location);
                            connection = url.openConnection();
                            continue;
                        }
                        throw new IOException("Redirect with no location");
                    default:
                        throw new IOException("HTTP response: " + responseCode);
                }
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }


    /**
     * Writes URL contents to file
     *
     * @param url    the url
     * @param f      the file
     * @throws IOException for issues opening connection or saving data to file
     */
    private void downloadUrl(String url, File f) throws IOException {
        URLConnection connection;
        try {
            connection = openURLConnectionWithRedirects(new URL(url));
        } catch (IOException e) {
            log.error("Issue opening connection to {}", url, e);
            throw e;
        }
        long length = connection.getContentLength();
        try (ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream())) {
            //TODO file lock
            try (final FileOutputStream fos = new FileOutputStream(f)) {
                long lengthSaved = fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                if (length != lengthSaved) {
                    throw new IOException("Expected " + length + " bytes but only downloaded " + lengthSaved);
                }
            }
        } catch (IOException e) {
            log.error("Issue downloading url {} content to {}", url, f.getAbsolutePath(), e);
            throw e;
        }
    }

    /**
     * downloadUrlHeaders will read metadata for the uri into destinationPath
     *
     * @param info                   path info
     * @return attrs CatalogUtils.CatalogEntryAttributes
     */
    @Nonnull
    private CatalogUtils.CatalogEntryAttributes downloadUrlHeaders(RemoteFilesPathInfo info) throws IOException {
        URLConnection connection = getUrlHeaders((String) info.remoteInfo);
        return new CatalogUtils.CatalogEntryAttributes(info.catalogRelativePath, info.name,
                    connection.getContentLengthLong(), new Date(connection.getLastModified()), null, null, null);
    }

    /**
     * Make HEAD request to uri
     * @param uri           the uri
     * @return the connection
     * @throws IOException if HEAD request fails
     */
    @Nonnull
    private URLConnection getUrlHeaders(String uri) throws IOException {
        URL url = new URL(uri);
        URLConnection connection = openURLConnectionWithRedirects(url, "HEAD");
        if (connection.getContentLength() == -1) {
            throw new IOException("Connection content length is -1");
        }
        return connection;
    }

    /*
    Things we don't actually implement for this simple open URL filesystem service
     */
    /**
     * {@inheritDoc}
     */
    @Override
    protected void doPushFile(File f, String url, @Nullable String project, @Nullable FilesystemObjectInfo info, boolean firstPush)
            throws InvalidFilesystemOperationException {
        // We don't archive local files to this simple open URL filesystem service
        throw new InvalidFilesystemOperationException(this, "pushFile");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doDeleteFile(String url, @Nullable String project, @Nullable FilesystemObjectInfo info)
            throws InvalidFilesystemOperationException {
        // Deleting not supported at arbitrary URLs
        throw new InvalidFilesystemOperationException(this, "deleteFile");
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public List<String> doListAllFiles(@Nullable String root, String project) throws InvalidFilesystemOperationException {
        throw new InvalidFilesystemOperationException(this, "listAllFiles");
    }
}
