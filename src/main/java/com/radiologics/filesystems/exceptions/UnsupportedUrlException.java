// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

import com.radiologics.filesystems.services.FilesystemService;

public class UnsupportedUrlException extends FilesystemServiceException {

    private static String makeMessage(final FilesystemService fs, final String url) {
        return fs.getClass().getName() + " does not support URL " + url;
    }

    public UnsupportedUrlException(final FilesystemService fs, final String url) {
        super(makeMessage(fs, url));
    }

    public UnsupportedUrlException(final FilesystemService fs, final String url, Throwable e) {
        super(makeMessage(fs, url), e);
    }

    public UnsupportedUrlException(final String message) {
        super(message);
    }
}
