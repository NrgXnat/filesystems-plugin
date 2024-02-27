// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

public class NoSuchFilesystemException extends RuntimeException {
    public NoSuchFilesystemException(final String message) {
        super(message);
    }

    public NoSuchFilesystemException(final String message, final Throwable e) {
        super(message, e);
    }

    public NoSuchFilesystemException(final Throwable e) {
        super(e);
    }
}
