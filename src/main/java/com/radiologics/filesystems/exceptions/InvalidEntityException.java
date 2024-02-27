// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

public class InvalidEntityException extends Exception {
    public InvalidEntityException(final String message) {
        super(message);
    }

    public InvalidEntityException(final String message, final Throwable e) {
        super(message, e);
    }

    public InvalidEntityException(final Throwable e) {
        super(e);
    }
}
