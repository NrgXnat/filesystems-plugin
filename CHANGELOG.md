# Wishlist

## v3.2
- Allow project owners to specify filesystems/credentials
- Give priority to UI-initiated actions

## v??
- Add support for Box as an archiver filesystem
- Allow user to cancel a running push via UI (put cancel request on queue the node running the push)

# Changelog
## v3.1
- Check archiver filesystem writable and warn & set archiver=false if not
- Only allow pushing to filesystems designated as archivers for the given projects

## v3
- Enable filesystem config per project
- Inclusion in archive cleanup set per project
- Upload to remote after processing preference set per project
- Do not push file if content is the same on remote
- Bugfix: don't open a new socket with each cache refresh

## v2.4
- Support multiple sets of S3 credentials, one for archive, N for reading
- Add API for manually intiating cleanup

## v2.3
- Bugfix to address pulling non-archivable item resources (e.g. scan resources)
- Handle file copying from archive to destination within pullItem
- Require versioning be turned on for s3 buckets and allow overwriting of remote file on push if location conflicts
- Allow specification of a subdirectory for archive
- Add test suite

## v2.2
- Add workflow entry when catalogs are modified with URLs
- Improved exception handling

## v2.1
- Add method to upload new files (e.g. from container service) directly to remote filesystem and add to catalogs by URL only

## v2.0
- All remote file handling moved into this plugin
- During cleanup, change catalog entry URI to the remote location (simulatenous change: pulling needs to pull all remote files, not just archive-cleaned ones)
- AWS S3 bulk downloading via TransferManager removed due to (1) inability to download to arbitrary location, (2) inefficiency
- Dropbox temporarily disabled because URLs aren't supported making it difficult to store remote location in catalog
- Add support for downloading to arbitrary location, not just archive dir
- Add automatic archival to project resources

## v1.2
- Only include certain projects in cleanup
- Ensure that push/pull operations are threadsafe and inter-process safe

## v1.1
- Support for outside-XNAT use
- Bugfix: invalidate client when credential change makes it inactive

## v1.0.1
- Bugfix: only use URI for deleting when it's a full path
- 
