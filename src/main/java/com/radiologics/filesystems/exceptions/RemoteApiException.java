// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

public class RemoteApiException extends FilesystemServiceException {
    public RemoteApiException(final String message) {
        super(message);
    }

    public RemoteApiException(final String message, final Throwable e) {
        super(message, e);
    }

    public RemoteApiException(final Throwable e) {
        super(e);
    }
}
