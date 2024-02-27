// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.exceptions;

import com.radiologics.filesystems.model.entity.RemoteFilesResourceTask;
import com.radiologics.filesystems.model.entity.RemoteFilesTrackerEntity;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xnat.turbine.utils.ArchivableItem;

public class InvalidResourceTaskException extends RuntimeException {
    public InvalidResourceTaskException(final RemoteFilesResourceTask task) {
        super("Task " + task.toString() + " is not recognized.");
    }

    public InvalidResourceTaskException(ArchivableItem item,
                                        XnatResourcecatalog resource,
                                        final RemoteFilesResourceTask taskExpected) {
        super("Item " + item.getId() + " resource " + resource.getLabel() + " (" +
                resource.getXnatAbstractresourceId() + ") is attempting to complete task " + taskExpected.toString() +
                ", but doesn't appear to be tracked as a remote resource.");
    }
    public InvalidResourceTaskException(final RemoteFilesTrackerEntity entity,
                                        final RemoteFilesResourceTask taskExpected,
                                        final RemoteFilesResourceTask taskActual) {
        super("Resource " + entity.getResourceLabel() + " is attempting to complete task " + taskExpected.toString() +
                ", but appears to be running task " + taskActual.toString());
    }
}
