// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.model.entity;

import javax.annotation.Nullable;

public class RemoteFilesPathInfo {
    public Object remoteInfo;
    public String localDestinationPath;
    public String catalogRelativePath;
    public String name;
    @Nullable public String project;
}
