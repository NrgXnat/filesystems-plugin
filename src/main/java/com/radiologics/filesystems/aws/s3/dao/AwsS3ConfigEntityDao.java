// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

package com.radiologics.filesystems.aws.s3.dao;

import com.radiologics.filesystems.aws.s3.model.entity.AwsS3ConfigEntity;
import com.radiologics.filesystems.dao.FilesystemConfigEntityDao;
import org.springframework.stereotype.Repository;

@Repository
public class AwsS3ConfigEntityDao extends FilesystemConfigEntityDao<AwsS3ConfigEntity> {
}