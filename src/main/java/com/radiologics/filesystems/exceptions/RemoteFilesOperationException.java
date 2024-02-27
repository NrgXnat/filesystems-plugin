// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

/**
 * For issues with the filesystems plugin itself
 */
public class RemoteFilesOperationException extends Exception {
    public RemoteFilesOperationException(final String message) {
        super(message);
    }

    public RemoteFilesOperationException(final String message, final Throwable e) {
        super(message, e);
    }

    public RemoteFilesOperationException(final Throwable e) {
        super(e);
    }
}
