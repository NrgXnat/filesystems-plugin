// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.rest;

import com.radiologics.filesystems.exceptions.InvalidEntityException;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.services.ProjectFilesystemSettingsService;
import io.swagger.annotations.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.HibernateException;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.nrg.xdat.security.helpers.AccessLevel.*;

@Api
@XapiRestController
@RequestMapping(value = "/project_filesystem_settings")
@Slf4j
public class ProjectFilesystemSettingsApi extends AbstractXapiRestController {
    private final ProjectFilesystemSettingsService projectFilesystemSettingsService;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    public ProjectFilesystemSettingsApi(final ProjectFilesystemSettingsService projectFilesystemSettingsService,
                                        final UserManagementServiceI userManagementService,
                                        final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.projectFilesystemSettingsService = projectFilesystemSettingsService;
    }

    @ApiOperation(value = "Get all project settings.")
    @ApiResponses({@ApiResponse(code = 200, message = "The operation completed successfully."),
            @ApiResponse(code = 400, message = "Something is wrong with your request"),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<List<ProjectFilesystemSettings>> getAll() {
        try {
            return new ResponseEntity<>(projectFilesystemSettingsService.getPojoForAllProjects(getSessionUser()),
                    HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @ApiOperation(value = "Update project settings.")
    @ApiResponses({@ApiResponse(code = 200, message = "The operation completed successfully."),
            @ApiResponse(code = 400, message = "Something is wrong with your request"),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "update", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_HTML_VALUE,
            method = RequestMethod.PUT, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<String> update(final @RequestBody ProjectFilesystemSettings pojo) {
        try {
            projectFilesystemSettingsService.createOrUpdateFromPojo(pojo);
        } catch (NotFoundException | InvalidEntityException | HibernateException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>("Success!", HttpStatus.OK);
    }
}