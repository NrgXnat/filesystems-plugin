// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.services;

import com.radiologics.filesystems.config.TestConfig;
import com.radiologics.filesystems.exceptions.InvalidFilesystemOperationException;
import com.radiologics.filesystems.exceptions.UnsupportedUrlException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xnat.utils.CatalogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

import static com.radiologics.filesystems.config.SharedStrings.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.*;
import static org.junit.Assert.*;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = TestConfig.class)
public class DefaultFilesystemServiceTest {
    @Autowired private SiteConfigPreferences siteConfigPreferences;

    @Autowired private FilesystemService defaultFilesystemService;

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Before
    public void setup() throws Exception {
        Files.createDirectories(Paths.get(writableArchivePath));
    }

    @After
    public void cleanup() throws IOException {
        FileUtils.deleteDirectory(new File(writableArchivePath));
    }

    @Test
    public void testSupportsUrl() {
        // Project makes no difference, test on two
        for (String project : Arrays.asList(supportedProject, otherProject)) {
            assertThat(defaultFilesystemService.supportsUrl(defaultFsGoodUrl, project, true), is(true));
            assertThat(defaultFilesystemService.supportsUrl(badUrl, project, true), is(false));
            assertThat(defaultFilesystemService.supportsUrl(nonUrl, project, false), is(false));
            assertThat(defaultFilesystemService.supportsUrl(nonUrl, project, true), is(false));
        }
    }

    @Test
    public void testPushFile() throws Exception {
        File testFile = Paths.get(siteConfigPreferences.getArchivePath(), testFileName).toFile();
        exceptionRule.expect(InvalidFilesystemOperationException.class);
        exceptionRule.expectMessage("Unsupported operation push or delete files for filesystem service " +
                defaultFilesystemService.getClass().getName());
        defaultFilesystemService.pushFile(testFile, defaultFsGoodUrl, supportedProject, false);
    }

    @Test
    public void testDeleteFile() throws Exception {
        exceptionRule.expect(InvalidFilesystemOperationException.class);
        exceptionRule.expectMessage("Unsupported operation push or delete files for filesystem service " +
                defaultFilesystemService.getClass().getName());
        defaultFilesystemService.deleteFile(defaultFsGoodUrl, supportedProject);
    }

    @Test
    public void testMakeUriFromLocal() throws Exception {
        exceptionRule.expect(InvalidFilesystemOperationException.class);
        exceptionRule.expectMessage("Filesystem cannot archive files");
        defaultFilesystemService.makeUriFromLocal(Paths.get(writableArchivePath, testFileName).toString(), supportedProject);
    }

    @Test
    public void testListAllFiles() throws Exception {
        exceptionRule.expect(InvalidFilesystemOperationException.class);
        exceptionRule.expectMessage("Unsupported operation listAllFiles for filesystem service " +
                defaultFilesystemService.getClass().getName());
        defaultFilesystemService.listAllFiles("anything", supportedProject);
    }

    @Test
    public void testGetMetadataValidUrl() throws Exception {
        Date reqTime = new Date();
        CatalogUtils.CatalogEntryAttributes attr = defaultFilesystemService.getMetadata(defaultFsGoodUrl, writableArchivePath, supportedProject);
        assertNotNull(attr);
        assertEquals(defaultFsGoodUrlArchiveName, attr.relativePath);
        assertEquals("user-agent", attr.name);
        assertEquals(defaultFsGoodUrlResponseSize, attr.size);
        assertTrue("Last modified is not close enough to request time",
                (attr.lastModified.getTime() - reqTime.getTime()) < 3000);
        assertNull(attr.md5);
    }

    @Test
    public void testGetMetadataInvalidUrl() throws Exception {
        exceptionRule.expect(UnsupportedUrlException.class);
        exceptionRule.expectMessage(defaultFilesystemService.getClass().getName() +
                " does not support URL " + badUrl);
        defaultFilesystemService.getMetadata(badUrl, writableArchivePath, supportedProject);
    }

    @Test
    public void testGetMetadataNonUrl() throws Exception {
        exceptionRule.expect(UnsupportedUrlException.class);
        exceptionRule.expectMessage(nonUrl + " is not a remote URL.");
        defaultFilesystemService.getMetadata(nonUrl, writableArchivePath, supportedProject);
    }

    @Test
    public void testPullValidUrl() throws Exception {
        String name = "testPullValidUrl.txt";
        String path = Paths.get(writableArchivePath, name).toString();
        File file = defaultFilesystemService.pullFile(defaultFsGoodUrl, path, supportedProject);
        assertNotNull(file);
        assertThat(file, anExistingFile());
        assertThat(file, aFileWithSize(defaultFsGoodUrlResponseSize));
        assertThat(file, aFileWithAbsolutePath(equalTo(path)));
        assertThat(file, aFileNamed(equalTo(name)));
        assertTrue("File content doesn't match expected",
                FileUtils.contentEquals(file, defaultFsGetFile));
    }

    @Test
    public void testPullInvalidUrl() throws Exception {
        exceptionRule.expect(UnsupportedUrlException.class);
        exceptionRule.expectMessage(defaultFilesystemService.getClass().getName() +
                " does not support URL " + badUrl);
        defaultFilesystemService.pullFile(badUrl, Paths.get(writableArchivePath, "tmp.txt").toString(), supportedProject);
    }

    @Test
    public void testPullNonUrl() throws Exception {
        exceptionRule.expect(UnsupportedUrlException.class);
        exceptionRule.expectMessage(nonUrl + " is not a remote URL.");
        defaultFilesystemService.pullFile(nonUrl, Paths.get(writableArchivePath, "tmp.txt").toString(), supportedProject);
    }

    @Test
    public void testGetInputStream() throws Exception {
        InputStream is = defaultFilesystemService.getInputStream(defaultFsGoodUrl, supportedProject);
        File file = new File(writableArchivePath, "tmp.txt");
        FileUtils.copyInputStreamToFile(is, file);
        assertNotNull(file);
        assertThat(file, anExistingFile());
        assertThat(file, aFileWithSize(defaultFsGoodUrlResponseSize));
        assertTrue("Input stream content doesn't match expected",
                FileUtils.contentEquals(file, defaultFsGetFile));
    }

    @Test
    public void testGetInputStreamInvalidUrl() throws Exception {
        exceptionRule.expect(UnsupportedUrlException.class);
        exceptionRule.expectMessage(defaultFilesystemService.getClass().getName() +
                " does not support URL " + badUrl);
        defaultFilesystemService.getInputStream(badUrl, supportedProject);
    }

    @Test
    public void testGetInputStreamNonUrl() throws Exception {
        exceptionRule.expect(UnsupportedUrlException.class);
        exceptionRule.expectMessage(nonUrl + " is not a remote URL.");
        defaultFilesystemService.getInputStream(nonUrl, supportedProject);
    }

    @Test
    public void testInitiatePullResourceFilesAndPollPullResource() throws Exception {
        File catalogFile = new File(siteConfigPreferences.getArchivePath(), "DATA_catalog.xml");
        CatalogUtils.CatalogData catalogData = new CatalogUtils.CatalogData(catalogFile, supportedProject);
        Object obj = defaultFilesystemService.initiatePullResourceFiles(catalogData.catPath, writableArchivePath, supportedProject,
                new ArrayList<>(catalogData.catBean.getEntries_entry()));
        assertNotNull(obj);
        assertTrue(obj instanceof AbstractFilesystemService.DownloadTracker);
        final AbstractFilesystemService.DownloadTracker tracker = (AbstractFilesystemService.DownloadTracker) obj;
        await().until(() -> defaultFilesystemService.pollPullResource(tracker) == 100.0);
        File file = new File(writableArchivePath, catalogData.catBean.getEntries_entry().get(0).getCachepath());
        assertThat(file, anExistingFile());
        assertThat(file, aFileWithSize(defaultFsGoodUrlResponseSize));
        assertTrue("File content doesn't match expected",
                FileUtils.contentEquals(file, defaultFsGetFile));
    }
}
