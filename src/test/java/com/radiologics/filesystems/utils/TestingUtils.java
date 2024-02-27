package com.radiologics.filesystems.utils;

import com.radiologics.filesystems.aws.s3.model.auto.AwsS3Config;
import com.radiologics.filesystems.model.auto.ProjectFilesystemSettings;
import com.radiologics.filesystems.services.ProjectFilesystemSettingsService;
import org.nrg.action.ClientException;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.concurrent.Callable;

import static com.radiologics.filesystems.config.SharedStrings.projectArrayList;
import static com.radiologics.filesystems.config.SharedStrings.supportedProject;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class TestingUtils {
    public static void setupProjectSettings(ProjectFilesystemSettingsService projectFilesystemSettingsService,
                                            AwsS3Config awsArchiverConfig)
            throws Exception {

        for (String project : projectArrayList) {
            ProjectFilesystemSettings pfs = new ProjectFilesystemSettings(project);
            if (project.equals(supportedProject)) {
                pfs.setDirectUploadOutputs(true);
            }
            pfs.setArchiverConfig(awsArchiverConfig);
            projectFilesystemSettingsService.createOrUpdateFromPojo(pfs);
        }
    }

    public static void expectException(Class<? extends Exception> eClazz, String message, Callable<Void> c) {
        try {
            c.call();
            fail("Should have seen " + eClazz.getName());
        } catch (Exception e) {
            if (!eClazz.isInstance(e)) {
                fail("Unexpected exception " + e + " was expecting " + eClazz.getName());
            }
            assertThat(e.getMessage(), containsString(message));
        }
    }
}

