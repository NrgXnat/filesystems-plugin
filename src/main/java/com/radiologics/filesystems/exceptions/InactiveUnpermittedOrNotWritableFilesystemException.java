// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

import com.radiologics.filesystems.model.auto.FilesystemConfig;
import com.radiologics.filesystems.services.FilesystemService;

public class InactiveUnpermittedOrNotWritableFilesystemException extends FilesystemServiceException {
    public InactiveUnpermittedOrNotWritableFilesystemException(final FilesystemService fs, final String project) {
        super(fs.getClass().getName() + " cannot archive files from project " + project);
    }

    public InactiveUnpermittedOrNotWritableFilesystemException(final FilesystemConfig config) {
        super("Unable to write files to filesystem " + config.getFilesystemType() + " config " + config.getName());
    }

    public InactiveUnpermittedOrNotWritableFilesystemException(final FilesystemService fs) {
        super(fs.getClass().getName() + " does not have an active config that can serve this request");
    }

    public InactiveUnpermittedOrNotWritableFilesystemException(final FilesystemConfig config, final String project) {
        super("Project " + project + " is not permitted to use filesystem " + config.getFilesystemType() +
                " config " + config.getName());
    }

    public InactiveUnpermittedOrNotWritableFilesystemException(FilesystemConfig archiverConfig, FilesystemConfig config, final String project) {
        super("Project " + project + " can only archive to " + archiverConfig.getFilesystemType() + " config " +
                archiverConfig.getName() + "; this request would attempt to write to " + config.getName());
    }
}
