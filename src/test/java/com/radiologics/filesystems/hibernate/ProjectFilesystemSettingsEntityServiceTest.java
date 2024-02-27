// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.hibernate;

import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.model.entity.ProjectFilesystemSettingsEntity;
import com.radiologics.filesystems.services.ProjectFilesystemSettingsEntityService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.nrg.framework.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {TestConfig.class})
public class ProjectFilesystemSettingsEntityServiceTest {
    @Autowired private ProjectFilesystemSettingsEntityService projectFilesystemSettingsEntityService;

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    private ProjectFilesystemSettingsEntity createEntity(String projectId) {
        ProjectFilesystemSettingsEntity entity = new ProjectFilesystemSettingsEntity();
        entity.setProjectId(projectId);
        entity.setCleanupInterval(7);
        entity.setDirectUploadOutputs(false);
        entity.setArchiverConfig(null);
        return projectFilesystemSettingsEntityService.create(entity);
    }

    @Test
    @DirtiesContext
    public void testFindByProject() throws Exception {
        String project = "project";
        ProjectFilesystemSettingsEntity entity = createEntity(project);
        assertThat(projectFilesystemSettingsEntityService.findByProjectAndReturnPojo(project), is(entity.toPojo()));
    }

    @Test
    @DirtiesContext
    public void testFindByProjectNotFound() throws Exception {
        String project = "project";
        exceptionRule.expect(NotFoundException.class);
        exceptionRule.expectMessage("Project " + project + " not configured for filesystems plugin");
        projectFilesystemSettingsEntityService.findByProjectAndReturnPojo(project);
    }
}