// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.exceptions.InactiveUnpermittedOrNotWritableFilesystemException;
import com.radiologics.filesystems.exceptions.InvalidFilesystemOperationException;
import com.radiologics.filesystems.exceptions.RemoteApiException;
import com.radiologics.filesystems.exceptions.UnsupportedUrlException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.security.SecurityManager;
import org.nrg.xft.event.EventDetails;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.helpers.uri.archive.ResourceURII;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.CatalogUtils;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;

@Service
@Slf4j
public class RemoteCatalogServiceImpl implements RemoteCatalogService {
    private final CatalogService localCatalogService;
    private final RemoteFilesPluginService remoteFilesService;
    private final List<FilesystemService> filesystemServices;

    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public RemoteCatalogServiceImpl(final CatalogService localCatalogService,
                                    final RemoteFilesPluginService remoteFilesService,
                                    final List<FilesystemService> filesystemServices) {
        this.localCatalogService = localCatalogService;
        this.remoteFilesService = remoteFilesService;
        this.filesystemServices = filesystemServices;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addRemoteFilesToResourceCatalog(final UserI user, final String catalogUriString,
                                                final Map<String, String> urls, final boolean create, boolean overwrite)
            throws ServerException, ClientException {

        final ResourceData resourceData = localCatalogService.getResourceDataFromUri(catalogUriString, true);
        final URIManager.ArchiveItemURI resourceUri = resourceData.getXnatUri();
        final ArchivableItem item = resourceData.getItem();
        XnatResourcecatalog catRes = resourceData.getCatalogResource();
        PersistentWorkflowI workflow = null;

        boolean success = false;
        try {
            Integer eventId = null;

            // Get or create catalog resource
            if (catRes == null) {
                if (create) {
                    try {
                        //TODO is there a better way to get the parent from the URI?
                        // (can't use item bc e.g., scan would have session item)
                        String parentUriStr = catalogUriString.replaceAll("/resources/[^/]*$", "");
                        if (!(UriParserUtils.parseURI(parentUriStr) instanceof URIManager.ArchiveItemURI)) {
                            throw new ClientException("Cannot determine parent resource, " +
                                    "please create the catalog before you add to it.");
                        }
                        workflow = createWorkflow(item, catalogUriString, catRes, user);
                        eventId = workflow.buildEvent().getEventId().intValue();
                        catRes = localCatalogService.createAndInsertResourceCatalog(user, parentUriStr, eventId,
                                ((ResourceURII) resourceUri).getResourceLabel(), null, null, null);
                    } catch (Exception e) {
                        throw new ServerException(e);
                    }
                } else {
                    throw new ClientException("Resource URI: " + catalogUriString +
                            " doesn't exist, rerun with create=true.");
                }
            } else {
                // Ensure that user can edit the catalog
                localCatalogService.checkPermissionsOnItem(user, item, SecurityManager.EDIT, catalogUriString);
            }

            // Get catalog info
            CatalogUtils.CatalogData catalogData = CatalogUtils.CatalogData.getOrCreate(item, catRes);
            final Map<String, CatalogUtils.CatalogMapEntry> catalogMap = CatalogUtils.buildCatalogMap(catalogData, true);

            // Preprocess urls
            List<String> errorMessages = new ArrayList<>();
            Map<String, CatalogUtils.CatalogEntryAttributes> urlMap = new HashMap<>();
            for (String url : urls.keySet()) {
                if (!FileUtils.IsUrl(url, true)) {
                    //Not a URL
                    errorMessages.add(url + " is not a URL");
                    continue;
                }

                boolean exists = catalogMap.containsKey(url);
                if (exists && !overwrite) {
                    errorMessages.add(url + " already in catalog and overwrite=false");
                    continue;
                }

                CatalogUtils.CatalogEntryAttributes attrs = getCatalogEntryAttributesForUrl(url, catalogData.catPath,
                        catalogData.project);
                if (attrs == null) {
                    //Unable to retrieve headers
                    errorMessages.add(url + " not accessible");
                    continue;
                }

                // Determine catalog-relative path
                String relPath = urls.get(url);
                if (!StringUtils.isBlank(relPath)) {
                    attrs.relativePath = relPath;
                    attrs.name = new File(relPath).getName();
                }
                if (catalogMap.containsKey(attrs.relativePath) && !exists) {
                    // Relative path in use
                    errorMessages.add(url + " relative path " + attrs.relativePath + " already in use");
                    continue;
                }

                //Add to map
                urlMap.put(url, attrs);
            }

            if (!urlMap.isEmpty()) {
                if (eventId == null) {
                    workflow = createWorkflow(item, catalogUriString, catRes, user);
                    eventId = workflow.buildEvent().getEventId().intValue();
                }
                remoteFilesService.addUrlsToCatalog(user, item, catalogData, eventId, urlMap, overwrite, catalogMap);
            }

            if (!errorMessages.isEmpty()) {
                String message = "The following errors were encountered: " +
                        String.join(", ", errorMessages) + ".";
                if (!urlMap.isEmpty()) {
                    message += " Other URLs were successfully added to the catalog.";
                } else {
                    message += " Nothing added to the catalog.";
                }
                throw new ClientException(message);
            }

            success = true;

        } finally {
            if (workflow != null) {
                try {
                    if (success) {
                        WorkflowUtils.complete(workflow, workflow.buildEvent());
                    } else {
                        WorkflowUtils.fail(workflow, workflow.buildEvent());
                    }
                } catch (Exception e) {
                    log.error("Unable to save workflow {}", workflow, e);
                }
            }
        }
    }

    private PersistentWorkflowI createWorkflow(ArchivableItem item, String catalogUriString,
                                               XnatResourcecatalog catRes, UserI user) throws ServerException {
        // Make workflow
        // If we are adding to a DICOM/secondary resource, use Modified .* Session action, which triggers OHIF metadata
        // refresh, otherwise, use an action that won't
        String action = isDicomCatalog(catalogUriString, catRes) ?
                EventUtils.getAddModifyAction(item.getXSIType(), false) : "Catalog modified";
        EventDetails event = EventUtils.newEventInstance(EventUtils.CATEGORY.DATA, EventUtils.TYPE.REST,
                action, "Add URLs to catalog", "");
        PersistentWorkflowI workflow;
        try {
            return PersistentWorkflowUtils.buildOpenWorkflow(user, item.getItem(), event);
        } catch (Exception e) {
            throw new ServerException(e);
        }
    }

    /**
     * Does the catalog contain DICOM data?
     * @param catalogUriString uri string
     * @param catRes catalog resource or null if doesn't yet exist
     * @return T/F
     */
    private boolean isDicomCatalog(String catalogUriString, @Nullable XnatResourcecatalog catRes) {
        return catRes == null ?
                catalogUriString.contains("/DICOM") || catalogUriString.contains("/secondary") :
                catRes.getLabel().equals("DICOM") || catRes.getLabel().equals("secondary");
    }

    /**
     * Get URL headers (either via filesystem service or directly from URL) to populate
     * CatalogUtils.CatalogEntryAttributes object
     *
     * @param url the url
     * @param catPath used determine path relative to catalog parent for CatEntryBean ID field
     * @param project the project
     * @return CatalogUtils.CatalogEntryAttributes
     */
    @Nullable
    private CatalogUtils.CatalogEntryAttributes getCatalogEntryAttributesForUrl(String url,
                                                                                String catPath,
                                                                                String project) {
        for (FilesystemService fs : filesystemServices) {
            try {
                return fs.getMetadata(url, catPath, project);
            } catch (UnsupportedUrlException | InactiveUnpermittedOrNotWritableFilesystemException | InvalidFilesystemOperationException e) {
                // Not supported by this filesystem, try the next one
            } catch (RemoteApiException e) {
                // Something went wrong trying to HEAD the url with this filesystem, log it and keep trying
                log.error("Unable to HEAD {} with fs {}", url, fs.getClass().getName());
            }
        }
        return null;
    }
}
