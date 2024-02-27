// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.rest;

import com.radiologics.filesystems.model.entity.RemoteFilesItemState;
import com.radiologics.filesystems.services.RemoteCatalogService;
import com.radiologics.filesystems.services.RemoteFilesPluginService;
import io.swagger.annotations.*;
import lombok.extern.slf4j.Slf4j;
import org.nrg.action.ServerException;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.Project;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.action.ClientException;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.om.XnatAbstractresource;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ResourceData;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import static org.nrg.xdat.security.helpers.AccessLevel.Read;

@Api(description = "XNAT Remote Files API")
@XapiRestController
@RequestMapping(value = "/remote_files")
@Slf4j
public class RemoteFilesApi extends AbstractXapiRestController {
    private CatalogService catalogService;
    private RemoteFilesPluginService remoteFilesService;
    private final RemoteCatalogService remoteCatalogService;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    public RemoteFilesApi(final CatalogService catalogService,
                          final RemoteCatalogService remoteCatalogService,
                          final RemoteFilesPluginService remoteFilesService,
                          final UserManagementServiceI userManagementService,
                          final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.catalogService = catalogService;
        this.remoteFilesService = remoteFilesService;
        this.remoteCatalogService = remoteCatalogService;
    }

    @ApiOperation(value = "Add list of URLs to a resource catalog.", notes = "The resource should be identified by standard " +
            "archive-relative paths, such as /archive/experiments/XNAT_E0001/scans/SCANID/resources/DICOM or " +
            "/archive/projects/XNAT_01/subjects/XNAT_01_01/resources/RESID. If multiple catalogs are found within the " +
            "provided path, an error will be returned", response = String.class)
    @ApiResponses({@ApiResponse(code = 200, message = "The add operation completed successfully."),
            @ApiResponse(code = 400, message = "Something is wrong with your request"),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "add_to_catalog", consumes = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.PUT)
    @ResponseBody
    public ResponseEntity<String> addRemoteFilesToResourceCatalog(
            @ApiParam("The path to a resource catalog.") @RequestParam final String resource,
            @ApiParam("Create the catalog if it doesn't exist?") @RequestParam(defaultValue = "true") final boolean create,
            @ApiParam("Overwrite existing entries?") @RequestParam(defaultValue = "false") final boolean overwrite,
            @ApiParam("Json map of URLs to add. JSON key should be URL, JSON value should be the desired relative path " +
                    "within the catalog, or an empty string for default: {\"url\":\"displayPath\"}, e.g., " +
                    "{\"s3://my/url/file.txt\":\"file.txt\", \"s3://my/url/alt/file.txt\":\"alt/file.txt\"}")
            @RequestBody final Map<String, String> urls) {
        final UserI user = getSessionUser();

        log.debug("User {} requested to add URLs to the resource catalog {}", user.getUsername(), resource);

        try {
            remoteCatalogService.addRemoteFilesToResourceCatalog(user, resource, urls, create, overwrite);
            return new ResponseEntity<>("Success!", HttpStatus.OK);
        } catch (ServerException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ClientException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private XnatResourcecatalog getCatalogResourceById(Integer resourceId, UserI user) throws ClientException {
        XnatAbstractresource resource =
                XnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(resourceId, user, false);
        if (!(resource instanceof XnatResourcecatalog)) {
            throw new ClientException("Non-catalog resource " + resourceId);
        }
        return (XnatResourcecatalog) resource;
    }

    @ApiOperation(value = "Returns a RemoteFilesItemState",
            response = RemoteFilesItemState.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Item archive status successfully retrieved."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 400, message = "Invalid resource."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "item_status",
            produces = {MediaType.APPLICATION_JSON_VALUE}, method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<RemoteFilesItemState> getItemStatus(@ApiParam("The URI for an item.") @RequestParam(value = "item") final String itemUri) {
        try {
            ArchivableItem item = catalogService.getResourceDataFromUri(itemUri).getItem();
            return new ResponseEntity<>(remoteFilesService.getItemState(item), HttpStatus.OK);
        } catch (ClientException e) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @ApiOperation(value = "Initiate pull from filesystem",
            response = String.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Pull successfully initiated."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 406, message = "Invalid parameters."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "pull", produces = MediaType.TEXT_HTML_VALUE, method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> initiatePull(@ApiParam("The URI for an item.") @RequestParam(value = "item") final String itemUri,
                                               @ApiParam("Resource id.") @RequestParam(value = "resource", required = false) final Integer resourceId) {
        try {
            ResourceData resourceData = catalogService.getResourceDataFromUri(itemUri);
            ArchivableItem item = resourceData.getItem();
            List<XnatAbstractresourceI> resources = getResourcesList(resourceData, resourceId);

            Map<Integer, Future<Boolean>> jobs = remoteFilesService.initiatePullItem(item, resources,
                    null, null);

            if (resourceId != null && jobs.get(resourceId) == null) {
                throw new ClientException("Cannot perform pull: item " + item.getId() + " resource " +
                        resourceId + " is in another task or the xnat_node_info for the server cannot be determined.");
            }

            return new ResponseEntity<>("Pull initiated", HttpStatus.OK);
        } catch (ServerException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ClientException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @ApiOperation(value = "Monitor pull progress.",
            response = String.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Pull progress retrieved."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 406, message = "Invalid parameters"),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "pull_progress", produces = MediaType.TEXT_HTML_VALUE, method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> pullProgress(@ApiParam("Resource id.") @RequestParam(value = "resource") final Integer resourceId) {
        try {
            XnatResourcecatalog resource = getCatalogResourceById(resourceId, getSessionUser());
            return new ResponseEntity<>(String.format("%.0f%%", remoteFilesService.monitorPullProgress(resource)),
                    HttpStatus.OK);
        } catch (ServerException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ClientException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @ApiOperation(value = "Initiate push to filesystem",
            response = String.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Push successfully initiated."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 406, message = "Invalid parameters"),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "push", produces = MediaType.TEXT_HTML_VALUE, method = RequestMethod.POST)
    @ResponseBody
    public ResponseEntity<String> initiatePush(@ApiParam("The URI for an item.") @RequestParam(value = "item") final String itemUri,
                                               @ApiParam("Resource id.") @RequestParam(value = "resource", required = false) final Integer resourceId) {
        try {
            ResourceData resourceData = catalogService.getResourceDataFromUri(itemUri);
            ArchivableItem item = resourceData.getItem();
            List<XnatAbstractresourceI> resources = getResourcesList(resourceData, resourceId);
            remoteFilesService.initiatePushItem(item, resources, getSessionUser());
            return new ResponseEntity<>("Push initiated", HttpStatus.OK);
        } catch (ClientException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Get list of resources depending on whether resourceId was provided
     * @param resourceData the resource data from URI
     * @param resourceId the resource id or null for all
     * @return list of resources
     * @throws ClientException if resourceId not valid
     */
    private List<XnatAbstractresourceI> getResourcesList(final ResourceData resourceData,
                                                         @Nullable final Integer resourceId)
            throws ClientException {
        List<XnatAbstractresourceI> resources;
        if (resourceId != null) {
            resources = Collections.singletonList(getCatalogResourceById(resourceId, getSessionUser()));
        } else {
            resources = resourceData.getXnatUri().getResources(true);
        }
        return resources;
    }

    @ApiOperation(value = "Returns true if some filesystem service can access the file.", response = Boolean.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Filesystem access checked."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "check_access", produces = {MediaType.APPLICATION_JSON_VALUE},
            method = RequestMethod.GET, restrictTo = Read)
    @ResponseBody
    public ResponseEntity<Boolean> checkAccess(@RequestParam final String url,
                                               @RequestParam @Project final String project) {
        try {
            return new ResponseEntity<>(remoteFilesService.canPullFile(url, project), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}