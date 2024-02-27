// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

import com.radiologics.filesystems.services.FilesystemService;

public class InvalidFilesystemOperationException extends FilesystemServiceException {
    public InvalidFilesystemOperationException(final String message) {
        super(message);
    }

    public InvalidFilesystemOperationException(final String message, final Throwable e) {
        super(message, e);
    }

    public InvalidFilesystemOperationException(final Throwable e) {
        super(e);
    }

    public InvalidFilesystemOperationException(FilesystemService fs, String operation) {
        super("Unsupported operation " + operation + " for filesystem service " + fs.getClass().getName());
    }
}
