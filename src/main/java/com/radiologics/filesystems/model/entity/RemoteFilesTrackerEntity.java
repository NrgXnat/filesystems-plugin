// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.model.entity;

import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;
import org.nrg.xdat.model.XnatResourcecatalogI;
import org.nrg.xnat.node.entities.XnatNodeInfo;
import org.nrg.xnat.turbine.utils.ArchivableItem;

import javax.persistence.*;
import java.time.ZonedDateTime;

@Entity
public class RemoteFilesTrackerEntity extends AbstractHibernateEntity {
    private String itemId;
    private String itemXsiType;
    private Integer abstractResourceId;
    private String resourceLabel;
    private XnatNodeInfo nodeInfo;
    private RemoteFilesResourceTask task;
    private RemoteFilesResourceStatus status;
    private boolean removeForUnchangedStatus;
    private Double pullProgress;
    private ZonedDateTime lastLocalCheck;

    public RemoteFilesTrackerEntity() {
        super();
    }

    public RemoteFilesTrackerEntity(ArchivableItem item,
                                    XnatResourcecatalogI resource,
                                    XnatNodeInfo nodeInfo,
                                    RemoteFilesResourceTask task,
                                    RemoteFilesResourceStatus status) {
        super();
        this.itemId = item.getId();
        this.itemXsiType = item.getXSIType();
        this.abstractResourceId = resource.getXnatAbstractresourceId();
        this.resourceLabel = getInformativeLabelForResource(resource);
        this.nodeInfo = nodeInfo;
        this.task = task;
        this.status = status;
        this.removeForUnchangedStatus = true;
    }

    public String getItemId() {
        return itemId;
    }
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getItemXsiType() {
        return itemXsiType;
    }
    public void setItemXsiType(String itemXsiType) {
        this.itemXsiType = itemXsiType;
    }

    @Column(unique = true)
    public Integer getAbstractResourceId() {
        return abstractResourceId;
    }

    public void setAbstractResourceId(Integer abstractResourceId) {
        this.abstractResourceId = abstractResourceId;
    }

    public String getResourceLabel() {
        return resourceLabel;
    }

    public void setResourceLabel(String resourceLabel) {
        this.resourceLabel = resourceLabel;
    }

    @Enumerated(EnumType.STRING)
    public RemoteFilesResourceTask getTask() {
        return task;
    }
    public void setTask(RemoteFilesResourceTask task) {
        this.task = task;
    }

    @Enumerated(EnumType.STRING)
    public RemoteFilesResourceStatus getStatus() {
        return status;
    }
    public void setStatus(RemoteFilesResourceStatus status) {
        this.status = status;
    }

    @ManyToOne
    public XnatNodeInfo getNodeInfo() {
        return nodeInfo;
    }
    public void setNodeInfo(XnatNodeInfo nodeInfo) {
        this.nodeInfo = nodeInfo;
    }

    @Column(columnDefinition = "boolean default true")
    public boolean isRemoveForUnchangedStatus() {
        return removeForUnchangedStatus;
    }

    public void setRemoveForUnchangedStatus(boolean removeForUnchangedStatus) {
        this.removeForUnchangedStatus = removeForUnchangedStatus;
    }

    public Double getPullProgress() {
        return pullProgress;
    }

    public void setPullProgress(Double pullProgress) {
        this.pullProgress = pullProgress;
    }

    public ZonedDateTime getLastLocalCheck() {
        return lastLocalCheck;
    }

    public void setLastLocalCheck(ZonedDateTime lastLocalCheck) {
        this.lastLocalCheck = lastLocalCheck;
    }

    /**
     * Return informative label for resource
     * @param resource the resource
     * @return the label
     */
    private String getInformativeLabelForResource(XnatResourcecatalogI resource) {
        String catalogFilePath = resource.getUri(); // always in UNIX format ("/" separator)
        String scanIdTag = catalogFilePath.contains("/SCANS/") ?
                catalogFilePath.replaceFirst(".*/SCANS/", "scan_").replaceAll("/.*", "")
                : null;
        String label = resource.getLabel();
        if (StringUtils.isNotBlank(scanIdTag)) {
            label = scanIdTag + "_" + label;
        }
        return label;
    }

    @Override
    public String toString() {
        return "RemoteFilesTrackerEntity{" +
                "itemId='" + itemId + '\'' +
                ", itemXsiType='" + itemXsiType + '\'' +
                ", abstractResourceId=" + abstractResourceId +
                ", resourceLabel='" + resourceLabel + '\'' +
                ", nodeInfo=" + nodeInfo +
                ", task=" + task +
                ", status=" + status +
                ", removeForUnchangedStatus=" + removeForUnchangedStatus +
                ", pullProgress=" + pullProgress +
                ", lastLocalCheck=" + lastLocalCheck +
                '}';
    }
}
