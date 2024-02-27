// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

public class InvalidArchiverEntityException extends InvalidEntityException {
    public InvalidArchiverEntityException(final String message) {
        super(message);
    }

    public InvalidArchiverEntityException(final String message, final Throwable e) {
        super(message, e);
    }

    public InvalidArchiverEntityException(final Throwable e) {
        super(e);
    }
}
