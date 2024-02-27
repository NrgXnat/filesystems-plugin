// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

public class MissingXnatNodeInfoException extends Exception {
    public MissingXnatNodeInfoException(final String message) {
        super(message);
    }

    public MissingXnatNodeInfoException(final String message, final Throwable e) {
        super(message, e);
    }

    public MissingXnatNodeInfoException(final Throwable e) {
        super(e);
    }
}
