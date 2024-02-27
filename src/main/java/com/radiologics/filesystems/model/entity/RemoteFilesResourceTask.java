// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.model.entity;

import com.radiologics.filesystems.exceptions.InvalidResourceTaskException;

import javax.annotation.Nullable;

public enum RemoteFilesResourceTask {
    Pull,
    Push,
    Add,
    None;

    @Nullable
    public RemoteFilesResourceStatus changesStatusTo() {
        switch (this) {
            case Pull:
                return RemoteFilesResourceStatus.Local;
            case Push:
            case Add:
                return RemoteFilesResourceStatus.Archived;
            case None:
                return null;
        }
        throw new InvalidResourceTaskException(this);
    }
}
