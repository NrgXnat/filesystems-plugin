// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.aws.s3.rest;

import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.aws.s3.services.AwsS3ConfigEntityService;
import com.radiologics.filesystems.aws.s3.services.AwsS3FilesystemService;
import com.radiologics.filesystems.exceptions.InvalidArchiverEntityException;
import com.radiologics.filesystems.exceptions.InvalidEntityException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
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

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;

@Slf4j
@Api
@XapiRestController
@RequestMapping(value = "/filesystems/aws_s3/config")
public class AwsS3ConfigApi extends AbstractXapiRestController {
    private final AwsS3FilesystemService awsS3FilesystemService;
    private final AwsS3ConfigEntityService awsS3ConfigEntityService;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    public AwsS3ConfigApi(AwsS3FilesystemService awsS3FilesystemService,
                             AwsS3ConfigEntityService awsS3ConfigEntityService,
                             UserManagementServiceI userManagementService, RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.awsS3FilesystemService = awsS3FilesystemService;
        this.awsS3ConfigEntityService = awsS3ConfigEntityService;
    }

    @ApiOperation(value = "Retrieve all AWS S3 configs.", response = List.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "Retrieved successfully."),
            @ApiResponse(code = 400, message = "Invalid request parameters."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<List<AwsS3Config>> getAll() {
        try {
            return new ResponseEntity<>(awsS3ConfigEntityService.getPojoForAllConfigs(), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @ApiOperation(value = "Create or update an AWS S3 config.", response = String.class, responseContainer = "String")
    @ApiResponses({@ApiResponse(code = 200, message = "Created or updated successfully."),
            @ApiResponse(code = 400, message = "Invalid request parameters."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "update", produces = MediaType.TEXT_HTML_VALUE,
            method = RequestMethod.PUT, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<String> update(final @RequestBody AwsS3Config pojo) {
        try {
            awsS3ConfigEntityService.createOrUpdateFromPojo(pojo, false,
                    awsS3FilesystemService);
        } catch (NotFoundException | InvalidEntityException | HibernateException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>("Success!", HttpStatus.OK);
    }

    @ApiOperation(value = "Refresh an AWS S3 config.", response = String.class, responseContainer = "String")
    @ApiResponses({@ApiResponse(code = 200, message = "Refreshed successfully."),
            @ApiResponse(code = 400, message = "Invalid request parameters."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "{id}/refresh", produces = MediaType.TEXT_HTML_VALUE,
            method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<String> refresh(final @PathVariable long id) {
        try {
            awsS3ConfigEntityService.refresh(id, awsS3FilesystemService);
        } catch (InvalidEntityException e) {
            return new ResponseEntity<>("Warning: " + e.getMessage(), HttpStatus.PARTIAL_CONTENT);
        } catch (NotFoundException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>("Success!", HttpStatus.OK);
    }

    @ApiOperation(value = "Delete an AWS S3 config.", response = String.class, responseContainer = "String")
    @ApiResponses({@ApiResponse(code = 200, message = "Deleted successfully."),
            @ApiResponse(code = 400, message = "Invalid request parameters."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error.")})
    @XapiRequestMapping(value = "{id}", produces = MediaType.TEXT_HTML_VALUE, method = RequestMethod.DELETE,
            restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<String> delete(final @PathVariable long id) {
        try {
            awsS3ConfigEntityService.deleteById(id, awsS3FilesystemService);
        } catch (HibernateException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>("Success!", HttpStatus.OK);
    }

    @ApiOperation(value = "Update permitted project for AWS S3 config.", response = String.class,
            responseContainer = "String")
    @ApiResponses({@ApiResponse(code = 200, message = "Updated successfully."),
            @ApiResponse(code = 400, message = "Invalid request parameters."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(value = "{id}/permitted_projects", produces = MediaType.TEXT_HTML_VALUE,
            method = RequestMethod.POST, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<String> updatePermittedProjects(final @PathVariable long id,
                                                          final @RequestParam boolean permitted,
                                                          final @RequestParam(required = false, name="projects[]") List<String> projects) {
        try {
            awsS3ConfigEntityService.updatePermittedProjects(id, permitted, projects, awsS3FilesystemService);
        } catch (NotFoundException | InvalidEntityException | HibernateException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>("Success!", HttpStatus.OK);
    }
}
