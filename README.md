# Filesystems Plugin

This plugin adds support for external filesystems (currently, AWS S3) as XNAT archive locations. Remote files can be added to XNAT catalogs via the API `PUT xapi/remote_files/add_to_catalog` as full URLs. 

The plugin will also archive files to AWS S3 (provided you've activiated an AWS S3 bucket as an archiver for your project) after a configurable period of inaccess. "Archived" files can be pulled into the local XNAT archive individually on demand or in batch. 

_In a future version of this plugin, we are planning to add support for Box as an alternative to AWS S3 for an archival filesystem._

Instructions for activating the filesystems can be found in the XNAT webapp interface once the plugin has been added to your XNAT.

## Dependencies

1. XNAT version 1.7.6
1. _(for file archival only)_: [Multi-node XNAT](https://wiki.xnat.org/pages/viewpage.action?pageId=34504866) or at least your single Tomcat server with a `${xnat.home}/config/node-conf.properties` file containing `node.id=tomcat` (the node id doesn't have to be `tomcat`, you can choose whatever you'd like).

## Build

To build the filesystems plugin:

1. If you haven't already, clone this repository and cd into the newly cloned folder.
1. Build the plugin: `./gradlew fatJar` (on Windows, you can use the batch file: `gradlew.bat fatJar`). This should build the plugin in the file `build/libs/filesystems_plugin-${VERSION}-fat.jar`
1. Copy the plugin jar to your plugins folder: `cp build/libs/filesystems_plugin-${VERSION}-fat.jar ${xnat.home}/plugins/`

## Deploy

Once you've copied the plugin jar into your XNAT's **plugins** folder (and optionally, set any desired environment variables e.g., `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`), you'll need to restart the Tomcat server and any "shadow servers" (shadow servers are the multi-node XNAT servers that don't serve the webapp). The fileystems plugin will be available from the XNAT web application as soon as the restart and initialization processes complete. 

## Configure

- Navigate to `Administer>Plugin Settings` to set your preferences for the filesystems plugin
- Navigate to `Administer>XNAT Task Settings` if you configured a multi-node XNAT (or "faked it" by adding `node-conf.properties` to your Tomcat server)

## Validate

- Configure per above
- Manually archive files for a configured project via the API `POST xapi/remote_files/push` (see swagger-ui for params). That API call will initiate the push or throw an exception. To determine when the push is complete, use use API `GET xapi/remote_files/item_status`
AND/OR
- Add remote files to xnat via API `PUT xapi/remote_files/add_to_catalog` 
AND/OR
- Run a container with output files provided you have configured the project to upload container outputs to remote
