// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.archive.CatalogService;

import java.util.Map;

public interface RemoteCatalogService {
    /**
     *
     * Adds the listed URLs into an existing resource catalog. If you wish to add local files to an existing resource catalog,
     * copy them into the resource and then run {@link org.nrg.xnat.services.archive.CatalogService#refreshResourceCatalog(UserI, String, CatalogService.Operation...)}.
     *
     * @param user              The user running the operation.
     * @param catalogResource   The URI of the resource catalog.
     * @param urls              URLs to add to the catalog (key=URL, value=desired relative path)
     * @param create            True: catalog should be created if it doesn't exist
     * @param overwrite         True: overwrite entires that already exist with this URL
     * @throws ClientException When an error occurs that is caused somehow by the requested operation.
     * @throws ServerException When an error occurs in the system during the refresh operation.
     */
    void addRemoteFilesToResourceCatalog(final UserI user, final String catalogResource, final Map<String, String> urls,
                                         boolean create, boolean overwrite)
            throws ServerException, ClientException;
}
