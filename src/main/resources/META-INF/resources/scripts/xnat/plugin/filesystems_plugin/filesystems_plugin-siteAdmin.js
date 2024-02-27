// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

console.log('filesystems_plugin-siteAdmin.js');

var XNAT = getObject(XNAT || {});
XNAT.plugin = getObject(XNAT.plugin || {});
XNAT.plugin.filesystems_plugin = getObject(XNAT.plugin.filesystems_plugin || {});

(function(factory){
    if (typeof define === 'function' && define.amd) {
        define(factory);
    }
    else if (typeof exports === 'object') {
        module.exports = factory();
    }
    else {
        return factory();
    }
}(function() {
    var awss3ConfigManager, awss3ConfigList, filesystemProjectSettings;

    XNAT.plugin.filesystems_plugin.awss3ConfigManager = awss3ConfigManager =
        getObject(XNAT.plugin.filesystems_plugin.awss3ConfigManager || {});

    XNAT.plugin.filesystems_plugin.filesystemProjectSettings = filesystemProjectSettings =
        getObject(XNAT.plugin.filesystems_plugin.filesystemProjectSettings || {});

    XNAT.plugin.filesystems_plugin.awss3ConfigList = awss3ConfigList = [];

    function validateForm($inputs) {
        var errors = [];
        $inputs.filter('[data-validate]').each(function(){
            var valid = XNAT.validate(this).check();
            var $input = $(this);
            if ($input.hasClass('allow-empty') && !this.value) {
                // allow validation of empty fields
                // if set to 'allow-empty'
                valid = true;
            }
            if (!valid) {
                errors.push({
                    element$: $input,
                    element: this,
                    field: this.title||this.name||this.id,
                    name: this.name||this.id||this.title,
                    message: $input.data('message') || 'Invalid input'
                })
            }
        });
        return errors;
    }

    function displayErrors(errorMsg) {
        var errors = [];
        errorMsg.forEach(function(msg){ errors.push(spawn('li', '<b>' + msg.field + '</b> ' + msg.message)) });

        return spawn('div',[
            spawn('p', 'Errors found:'),
            spawn('ul', errors)
        ]);
    }

    function permittedUrl(configId) {
        return XNAT.url.restUrl('/xapi/filesystems/aws_s3/config/' + configId + '/permitted_projects');
    }

    function projectSettingsUrl(appended) {
        appended = appended ? '/' + appended  : '';
        return XNAT.url.restUrl('/xapi/project_filesystem_settings' + appended);
    }


    function spacer(width){
        return spawn('i.spacer', {
            style: {
                display: 'inline-block',
                width: width + 'px'
            }
        })
    }

    function awsS3ConfigUrl(appended){
        appended = appended ? '/' + appended  : '';
        return XNAT.url.restUrl('/xapi/filesystems/aws_s3/config' + appended);
    }

    function errorHandler(e, message){
        message = message ? message + '<br/><br/>' : '';
        var details = e.responseText ? spawn('p',[message, e.responseText]) : '';
        console.log(e);
        xmodal.alert({
            title: 'Error',
            content: '<p><strong>Error ' + e.status + ': '+ e.statusText+'</strong></p>' + details.html,
            okAction: function () {
                xmodal.closeAll();
            }
        });
    }

    // get the list of configs
    awss3ConfigManager.getAll = function(){
        return XNAT.xhr.get({
            url: awsS3ConfigUrl(),
            dataType: 'json',
            success: function(data){
                awss3ConfigList = data;
            },
            error: function(e) {
                errorHandler(e);
            }
        });
    };

    // dialog to create/edit hosts
    awss3ConfigManager.dialog = function(item){
        XNAT.dialog.open({
            title: 'AWS S3 credentials configuration',
            content: spawn('form', {classes: 'validate'}),
            width: 550,
            beforeShow: function(obj){
                var $formContainer = obj.$modal.find('.xnat-dialog-content');
                $formContainer.addClass('panel').find('form').append(
                    spawn('!', [
                        spawn('p', 'You may store credentials in environment variables AWS_ACCESS_KEY_ID and ' +
                            'AWS_SECRET_ACCESS_KEY on your server (restart needed if changed). Valid credentials ' +
                            '(access key and secret key) entered below will take precedence and do not require a ' +
                            'restart; invalid credentials will try to fall back on environment variables.'),
                        XNAT.ui.panel.input.text({
                            name: 'name',
                            label: 'Name for this configuration',
                            validation: 'not-empty',
                            description: 'Short name for this configuration, used to identify it (Required). Spaces and any special characters apart from \'-\' and \'_\' will be removed.'
                        }).element,
                        XNAT.ui.panel.input.text({
                            name: 'bucketName',
                            label: 'Bucket name',
                            validation: 'not-empty',
                            description: 'Name of your S3 bucket (Required). This bucket must have versioning ENABLED if you set archiver = true, below.'
                        }).element,
                        XNAT.ui.panel.input.text({
                            name: 'subdirectory',
                            label: 'Subdirectory',
                            description: 'Subdirectory path within your AWS S3 bucket into which you want to store your ' +
                                'XNAT archive. If blank, the XNAT archive directory contents will be at the root of your bucket.'
                        }).element,
                        XNAT.ui.panel.input.text({
                            name: 'accessKey',
                            label: 'Access key',
                            description: 'If your AWS access key is stored as an environment variable ' +
                                '(AWS_ACCESS_KEY_ID), you may leave this blank.'
                        }).element,
                        XNAT.ui.panel.input.text({
                            name: 'secretKey',
                            label: 'Secret key',
                            description: 'If your AWS secret key is stored as an environment variable ' +
                                '(AWS_SECRET_ACCESS_KEY), you may leave this blank.'
                        }).element,
                        XNAT.ui.panel.input.switchbox({
                            name: 'permitAllProjects',
                            label: 'Allow all projects to access this data',
                            value: 'true',
                            description: 'Should all projects have access to the data in this bucket with these ' +
                                'credentials? If not, you can selectively enable projects from the Project Access table.'
                        }).element,
                        XNAT.ui.panel.input.switchbox({
                            name: 'archiver',
                            label: 'Use as archiver?',
                            value: 'true',
                            description: 'Should this bucket be used for file cleanup? If true, you must have ' +
                                'versioning ENABLED and have write access to this bucket.'
                        }).element,
                        XNAT.ui.panel.input.hidden({
                            name: 'id',
                            id: 'config-id'
                        }).element,
                        XNAT.ui.panel.input.hidden({
                            name: 'filesystem',
                            id: 'filesystem-type',
                            value: 'awss3'
                        }).element
                    ])
                );
                if (item) {
                    $formContainer.find('form').setValues(item);
                }
            },
            buttons: [
                {
                    label: 'Save',
                    isDefault: true,
                    close: false,
                    action: function(obj){
                        xmodal.loading.open({ title: 'Saving config'});
                        var $form = obj.$modal.find('form');
                        var errors = validateForm($form.find('input'));
                        if (errors.length > 0) {
                            xmodal.loading.close();
                            XNAT.dialog.open({
                                title: 'Validation error',
                                width: 300,
                                content: displayErrors(errors)
                            });
                            return;
                        }
                        // gather form input values
                        var dataToPost = form2js($form.get(0), ':', false);
                        XNAT.xhr.putJSON({
                            url: awsS3ConfigUrl('update'),
                            data: JSON.stringify(dataToPost),
                            dataType: 'text',
                            success: function () {
                                awss3ConfigManager.refreshTable();
                                xmodal.loading.close();
                                XNAT.dialog.closeAll();
                                XNAT.ui.banner.top(2000, 'Saved.', 'success')
                            },
                            error: function (e) {
                                xmodal.loading.close();
                                errorHandler(e);
                            }
                        });
                    }
                }
            ]
        });
    };

    // create table for AWS S3 Config entries
    awss3ConfigManager.table = function(callback){

        // initialize the table - we'll add to it below
        var awsConfigTable = XNAT.table({
            className: 'aws-config xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        awsConfigTable.tr()
            .th('<b>Name</b>')
            .th('<b>Bucket</b>')
            .th('<b>Archiver</b>')
            .th('<b>Active</b>')
            .th('<b>Permit all</b>')
            .th('<b>Actions</b>');

        function editLink(item, text){
            return spawn('a.link|href=#!', {
                onclick: function(e){
                    e.preventDefault();
                    awss3ConfigManager.dialog(item);
                }
            }, [['b', text]]);
        }

        function editButton(item) {
            return spawn('button.btn.sm.edit', {
                onclick: function(e){
                    e.preventDefault();
                    awss3ConfigManager.dialog(item);
                }
            }, 'Edit');
        }

        function deleteButton(item){
            return spawn('button.btn.sm.delete', {
                onclick: function(){
                    xmodal.confirm({
                        height: 220,
                        scroll: false,
                        content: "" +
                            "<p>Are you sure you'd like to delete the config for bucket <b>" + item.bucketName + "</b>?</p>" +
                            "<p><b>This action cannot be undone.</b></p>",
                        okAction: function(){
                            XNAT.xhr.delete({
                                url: awsS3ConfigUrl(item.id),
                                dataType: 'text',
                                success: function(){
                                    awss3ConfigManager.refreshTable();
                                    XNAT.ui.banner.top(1000, '<b>"'+ item.bucketName + '"</b> config deleted.', 'success');
                                },
                                error: function(e){
                                    errorHandler(e);
                                }
                            });
                        }
                    })
                }
            }, [ spawn('i.fa.fa-trash') ]);
        }

        function refreshButton(item){
            return spawn('button.btn.sm', {
                onclick: function(){
                    xmodal.loading.open({ title: 'Refreshing...'});
                    XNAT.xhr.get({
                        url: awsS3ConfigUrl(item.id + '/refresh'),
                        dataType: 'text',
                        success: function(data, textStatus, xhr) {
                            if (xhr.status === 206) {
                                XNAT.dialog.alert(data);
                            }
                            awss3ConfigManager.refreshTable();
                        },
                        error: function(e){
                            errorHandler(e);
                        },
                        complete: function() {
                            xmodal.loading.close();
                        }
                    });
                }
            }, [ spawn('i.fa.fa-refresh') ]);
        }

        awss3ConfigManager.getAll().done(function(data){
            data = [].concat(data);
            data.forEach(function(item){
                awsConfigTable.tr({ title: item.name, data: { id: item.id, name: item.name}})
                    .td([ editLink(item, item.name) ])
                    .td( item.bucketName )
                    .td( [spawn('span', {classes: item.archiver ? 'text-success' : 'text-error'},
                        [String(item.archiver)])] )
                    .td( [spawn('span', {classes: item.active ? 'text-success' : 'text-error'},
                        [String(item.active)])] )
                    .td( [spawn('span', {classes: item.permitAllProjects ? 'text-success' : 'text-error'},
                        [String(item.permitAllProjects)])] )
                    .td([ spawn('div.center', [
                        editButton(item), spacer(10),
                        refreshButton(item), spacer(10),
                        deleteButton(item)
                    ])]);
            });

            if (isFunction(callback)) {
                callback(awsConfigTable.table);
            }

            filesystemProjectSettings.init();
        });

        awss3ConfigManager.$table = $(awsConfigTable.table);

        return awsConfigTable.table;
    };

    awss3ConfigManager.init = function(container) {

        var $manager = $$(container||'div#aws-s3-config-manager');
        var $footer = $('#aws-s3-config-manager').parents('.panel').find('.panel-footer');

        awss3ConfigManager.container = $manager;

        $manager.append(awss3ConfigManager.table());
        // awss3ConfigManager.table($manager);

        var newReceiver = spawn('button.new-aws-s3-config.btn.btn-sm.submit', {
            html: 'Add AWS S3 Bucket',
            onclick: function(){
                awss3ConfigManager.dialog();
            }
        });

        // add the 'add new' button to the panel footer
        $footer.append(spawn('div.pull-right', [
            newReceiver
        ]));
        $footer.append(spawn('div.clear.clearFix'));

        return {
            element: $manager[0],
            spawned: $manager[0],
            get: function(){
                return $manager[0]
            }
        };
    };

    awss3ConfigManager.refresh = awss3ConfigManager.refreshTable = function(container){
        var $manager = $$(container||'div#aws-s3-config-manager');

        awss3ConfigManager.$table.remove();
        $manager.append('Retrieving configs...');
        awss3ConfigManager.table(function(table){
            $manager.empty().prepend(table);
        });
    };

    awss3ConfigManager.init();




    // Project panel
    filesystemProjectSettings.projectList = [];
    filesystemProjectSettings.getAll = function(){
        return XNAT.xhr.get({
            url: projectSettingsUrl(),
            dataType: 'json',
            success: function(data){
                filesystemProjectSettings.projectList = data;
            },
            error: function(e) {
                errorHandler(e);
            }
        });
    };

    filesystemProjectSettings.dialog = function(item) {
        XNAT.dialog.open({
            title: 'Project ' + item.projectId + ' Local-Archive-Cleanup Preferences',
            content: spawn('form', {classes: 'validate'}),
            width: 550,
            beforeShow: function(obj){
                var $formContainer = obj.$modal.find('.xnat-dialog-content');
                $formContainer.addClass('panel').find('form').append(
                    spawn('!', [
                        XNAT.ui.panel.select.single({
                            name: 'archiverConfig',
                            label: 'Archiver',
                            description: 'If you select a remote filesystem configuration here, project ' +
                                item.projectId + '\'s files ' + 'will be archived to it.',
                            options: filesystemProjectSettings.archiverOptions,
                            onchange: function() {
                                if ($(this).val() === 'null') {
                                    $('.cleanup-pref').css({'visibility':'hidden'});
                                } else {
                                    $('.cleanup-pref').css({'visibility':'show'});
                                }
                            }
                        }).element,
                        XNAT.ui.panel.input.text({
                            name: 'cleanupInterval',
                            label: 'Cleanup interval',
                            classes: 'cleanup-pref',
                            validate: 'integer',
                            data: {message: 'Must be an integer'},
                            description: 'After this many days without access, files will be removed from the local ' +
                                'archive and stored only on the remote filesystem selected above.'
                        }).element,
                        XNAT.ui.panel.input.switchbox({
                            name: 'directUploadOutputs',
                            label: 'Directly upload processing outputs',
                            value: 'true',
                            classes: 'cleanup-pref',
                            description: 'Directly upload processing outputs to remote filesystem selected above, ' +
                                'skipping local archival entirely.'
                        }).element,
                        XNAT.ui.panel.input.hidden({
                            name: 'id',
                            id: 'prj-settings-id'
                        }).element,
                        XNAT.ui.panel.input.hidden({
                            name: 'projectId',
                            id: 'prj-id'
                        }).element
                    ])
                );
                $formContainer.find('form').setValues(item);
                if (item.archiverConfig) {
                    // Manually set this bc the JSON value matching doesn't work
                    $formContainer.find('select#archiver-config')
                        .find('option.archiver-' + item.archiverConfig.id).prop('selected', true);
                }
            },
            buttons: [
                {
                    label: 'Save',
                    isDefault: true,
                    close: false,
                    action: function(obj){
                        xmodal.loading.open({ title: 'Saving...'});
                        var $form = obj.$modal.find('form');
                        var errors = validateForm($form.find('input'));
                        if (errors.length > 0) {
                            xmodal.loading.close();
                            XNAT.dialog.open({
                                title: 'Validation error',
                                width: 300,
                                content: displayErrors(errors)
                            });
                            return;
                        }
                        // gather form input values
                        var dataToPost = form2js($form.get(0), ':', false, undefined, undefined, undefined, ['projectId']);
                        if (dataToPost['archiverConfig']) {
                            // Stored as a string in option value, convert back to object so it's not double-stringified
                            dataToPost['archiverConfig'] = JSON.parse(dataToPost['archiverConfig']);
                        }
                        XNAT.xhr.putJSON({
                            url: projectSettingsUrl('update'),
                            data: JSON.stringify(dataToPost),
                            dataType: 'text',
                            success: function () {
                                awss3ConfigManager.refresh();
                                xmodal.loading.close();
                                XNAT.dialog.closeAll();
                                XNAT.ui.banner.top(2000, 'Saved.', 'success')
                            },
                            error: function (e) {
                                xmodal.loading.close();
                                errorHandler(e);
                            }
                        });
                    }
                }
            ]
        });
    };

    filesystemProjectSettings.table = function($parent) {
        if (awss3ConfigList.length === 0) {
            $parent.empty().prepend(spawn('p', ['<strong>You must add a filesystem configuration, above, to set up ' +
                'project preferences.</strong>']));
            return;
        }

        function changePermissions(checkbox, configId, configName, successFn, projectIds) {
            var perm = checkbox.checked;
            var dataToPost = {permitted: perm};
            var applyToAll, projectDisplay;
            if (projectIds) {
                applyToAll = false;
                projectIds = [].concat(projectIds);
                dataToPost['projects'] = projectIds;
                projectDisplay = projectIds.join(', ');
            } else {
                applyToAll = true;
                projectDisplay = 'ALL projects';
            }
            var permFlagPassive = (perm) ? 'given <b>READ</b> access to' : '<b>BLOCKED</b> from accessing';

            XNAT.xhr.post({
                url: permittedUrl(configId),
                data: dataToPost,
                dataType: 'text',
                success: function() {
                    checkbox.value = perm;
                    successFn(configId, perm, applyToAll);
                    XNAT.ui.banner.top(1000, projectDisplay + ' ' + permFlagPassive + ' ' + configName,
                        'success');
                },
                error: function(e){
                    checkbox.checked = !perm;
                    errorHandler(e, 'UNABLE to change permissions for ' + projectDisplay + ' on ' + configName);
                }
            });
        }

        function setIndeterminate($selectAll, configId, checked) {
            $selectAll.prop('indeterminate', checked &&
                $('.select-one-' + configId + ':hidden:not(:disabled):not(:checked)').length > 0);
        }

        function checkAndSetIndeterminate($selectAll, configId) {
            configId = configId ? configId : $selectAll.data('configId');
            var checked = $('.select-one-' + configId + ':not(:hidden):not(:disabled):not(:checked)').length === 0;
            $selectAll.prop('checked', checked);
            setIndeterminate($selectAll, configId, checked);
        }

        function setSelectOnes(configId, checked, applyToAll) {
            $('.select-one-' + configId + ':not(:hidden):not(:disabled)').prop('checked', checked);
            if (applyToAll) {
                // Need to update the config mgr table bc we've adjusted "permitAllProjects".
                awss3ConfigManager.refreshTable();
            } else {
                setIndeterminate($('#select-all-' + configId), configId, checked);
            }
        }

        function setSelectAll(configId, checked, applyToAll) {
            var $selectAll = $('#select-all-' + configId);
            if (checked) {
                // should select all be checked?
                checkAndSetIndeterminate($selectAll, configId);
            } else {
                if ($selectAll.prop('checked')) {
                    $selectAll.prop('checked', false);
                    // Need to update the config mgr table bc we've adjusted "permitAllProjects".
                    awss3ConfigManager.refreshTable();
                } else {
                    $selectAll.prop('indeterminate', false);
                }
            }
        }

        filesystemProjectSettings.archiverOptions = [{label: '[No cleanup]', value: null}];
        var projectBuckets = {};
        var columnIds = ["project", "archiver"]; //don't change without checking all instances of columnIds[0]
        var labelMap = {
            project: {label: "Project", checkboxes: false},
            archiver: {label: "Archiver", checkboxes: false}
        };
        var projects;
        $.each(awss3ConfigList, function(i, e) {
            columnIds.push(e.name);
            labelMap[e.name] = {label: e.name, checkboxes: true, id: e.id};
            if (e.active && e.archiver) {
                filesystemProjectSettings.archiverOptions.push({
                    value: JSON.stringify(e),
                    label: e.name,
                    element: {
                        classes: 'archiver-' + e.id
                    }
                });
            }
            projects = [].concat(e.permittedProjects);
            projects.forEach(function(project) {
                if (!projectBuckets.hasOwnProperty(project)) {
                    projectBuckets[project] = [];
                }
                projectBuckets[project].push(e.id);
            });
        });

        // initialize the table - we'll add to it below
        var projectTable = XNAT.table({
            className: 'filesystems-project-table xnat-table data-table clean fixed-header selectable scrollable-table',
            style: {
                width: 'auto'
            }
        });
        var $dataRows = [];
        var dataRows = [];
        function cacheRows(){
            if ($dataRows.length === 0 || $dataRows.length !== dataRows.length) {
                $dataRows = dataRows.length ?
                    $(dataRows) :
                    filesystemProjectSettings.container.find('.table-body').find('tr');
            }
            return $dataRows;
        }

        function filterRows(val, name){
            if (!val) { return false }
            val = val.toLowerCase();
            var filterClass = 'filter-' + name;
            // cache the rows if not cached yet
            cacheRows();
            $dataRows.addClass(filterClass).filter(function(){
                return $(this).find('td.' + name).containsNC(val).length
            }).removeClass(filterClass);
            filesystemProjectSettings.$table.find('.selectable-select-all').each(function(){
                setIndeterminate($(this), $(this).data('configId'), $(this).prop('checked'));
            });
        }

        // add table header row
        projectTable.thead().tr();
        $.each(columnIds, function(i, c) {
            projectTable.th('<b>' + labelMap[c].label + '</b>');
        });
        // add check-all header row
        projectTable.tr({classes: 'filter'});
        $.each(columnIds, function(i, c) {
            if (labelMap[c].checkboxes) {
                var configId = labelMap[c].id;
                var configName = labelMap[c].label;
                projectTable.td({classes: "toggle-all"}, spawn('div.center', [spawn('input', {
                    type: 'checkbox',
                    checked: false,
                    value: 'true',
                    id: 'select-all-' + configId,
                    classes: 'selectable-select-all',
                    data: {configId: configId},
                    onchange: function() {
                        var projectIds = null;
                        var anyHidden = $('.select-one-' + configId + ':hidden').length > 0;
                        if (anyHidden) {
                            projectIds = $('td.' + columnIds[0] + ':not(:hidden)')
                                .filter(function(){
                                    return $(this).siblings().find('.select-one-' + configId + ':not(:disabled)').length > 0
                                })
                                .map(function(){
                                    return $(this).text();
                                }).get();
                        }
                        changePermissions(this, configId, configName, setSelectOnes, projectIds);
                    }
                })]));
            } else {
                document.head.appendChild(spawn('style|type=text/css', 'tr.filter-' + c + '{display:none;}'));
                var $filterInput = $.spawn('input.filter-data', {
                    type: 'text',
                    title: c + ':filter',
                    placeholder: 'Filter by ' + c,
                    style: 'width: 90%;'
                });
                $filterInput.on('focus', function(){
                    $(this).select();
                    cacheRows();
                });
                $filterInput.on('keyup', function(e){
                    var val = this.value;
                    var key = e.which;
                    // don't do anything on 'tab' keyup
                    if (key == 9) return false;
                    if (key == 27){ // key 27 = 'esc'
                        this.value = val = '';
                    }
                    if (!val || key == 8) {
                        $dataRows.removeClass('filter-' + c);
                    }
                    if (!val) {
                        // no value, no filter
                        return false;
                    }
                    filterRows(val, c);
                });
                projectTable.td({classes: 'filter'}, $filterInput[0]);
            }
        });

        function editButton(item, text, archiverActiveAndArchiving){
            var btnClass = archiverActiveAndArchiving ? 'primary' : 'error';
            if (!text) {
                text = 'None';
                btnClass = '';
            }
            return spawn('button', {
                classes: 'btn sm edit ' + btnClass,
                onclick: function(e){
                    e.preventDefault();
                    filesystemProjectSettings.dialog(item);
                }
            }, [['b', text]]);
        }

        function permittedCheckbox(configId, configName, projectId, permitted, disabled) {
            var ckbox = spawn('input', {
                type: 'checkbox',
                checked: permitted,
                disabled: disabled,
                value: 'true',
                id: 'permitted-' + configId + '-' + projectId,
                classes: 'selectable-select-one select-one-' + configId,
                onchange: function() {
                    changePermissions(this, configId, configName, setSelectAll, projectId);
                }
            });

            return spawn('div.center', [ckbox]);
        }

        projectTable.tbody({classes:'table-body'});
        filesystemProjectSettings.getAll().done(function(data){
            data = [].concat(data);
            var configs, permitted, disabled;
            data.forEach(function(item){
                projectTable.tr();
                projectTable.td({classes: columnIds[0]}, item.projectId);
                var archiverId, archiverLabel, archiverActiveAndArchiving;
                if (item.archiverConfig) {
                    archiverId = item.archiverConfig.id;
                    archiverLabel = item.archiverConfig.name;
                    archiverActiveAndArchiving = item.archiverConfig.active && item.archiverConfig.archiver;
                }
                projectTable.td({classes: columnIds[1]}, [editButton(item, archiverLabel, archiverActiveAndArchiving)]);
                configs = projectBuckets.hasOwnProperty(item.projectId) ? projectBuckets[item.projectId] : [];
                $.each(awss3ConfigList, function(i, e){
                    disabled = archiverId ? archiverId === e.id : false;
                    permitted = e.permitAllProjects ? true : disabled || configs.includes(e.id);
                    projectTable.td([permittedCheckbox(e.id, e.name, item.projectId, permitted, disabled)]);
                });
            });

            var $manager = $('<div class="data-table-wrapper"></div>');
            $parent.empty().prepend($manager);
            $manager.empty().prepend(projectTable.table);
            filesystemProjectSettings.container = $manager;
            filesystemProjectSettings.$table = $(projectTable.table);

            if (filesystemProjectSettings.$table.is(':hidden')) {
                filesystemProjectSettings.$table.on('nowVisible', function() {
                    $(this).find('.selectable-select-all').each(function(){
                        checkAndSetIndeterminate($(this));
                    });
                });
            } else {
                filesystemProjectSettings.$table.find('.selectable-select-all').each(function(){
                    checkAndSetIndeterminate($(this));
                });
            }

            XNAT.ui.ajaxTable.resizeTableCols(filesystemProjectSettings.$table);
        });
    };

    filesystemProjectSettings.init = function() {
        var $parent = $('div#filesystems-project-config');
        var $footer = $parent.parents('.panel').find('.panel-footer');

        if (filesystemProjectSettings.$table) {
            filesystemProjectSettings.$table.remove();
        }

        filesystemProjectSettings.table($parent);
        $footer.append(spawn('div.clear.clearFix'));
    };
}));