// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonInclude
public class RemoteFilesItemState {
    public enum Status {
        Inactive,   //no remote files
        Local,      //has remote files but they'll currently local
        Archived,   //remote files are archived
        Locked      //some child resource is being pushed, pulled, or added to
    }

    private Status status;
    private List<Resource> resources = new ArrayList<>();

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(List<Resource> resources) {
        this.resources = resources;
    }

    @JsonIgnore
    public void sortResources() {
        Collections.sort(resources);
    }

    @JsonIgnore
    public void addResourceWithStatus(Resource res, String status) {
        res.setStatus(status);
        this.resources.add(res);
    }

    /**
     * We need a key that'll uniquely identify the resource (abstract resource id) but will also be user-readable (label)
     * @param id        the xnat abstract resource id
     * @param label     the human-readable label
     * @return the key
     */
    @JsonIgnore
    public Resource makeResourceKey(Integer id, String label) {
        return new Resource(id.toString(), label);
    }

    @JsonInclude
    public static class Resource implements Comparable<Resource> {
        private String label;
        private String id;
        private String status;

        public Resource() {}

        public Resource(String id, String label)  {
            this.id = id;
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        @Override
        public int compareTo(Resource res) {
            return label.compareTo(res.label);
        }
    }
}
