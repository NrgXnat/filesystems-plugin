// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

/**
 * Expected issues arising from inactivity, lack of permissions, unsupported URLs etc
 */
public class FilesystemServiceException extends Exception {
    public FilesystemServiceException(final String message) {
        super(message);
    }

    public FilesystemServiceException(final String message, final Throwable e) {
        super(message, e);
    }

    public FilesystemServiceException(final Throwable e) {
        super(e);
    }
}
