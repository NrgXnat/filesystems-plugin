// Copyright 2019 Radiologics, Inc
// Developer: Kate Alpert <kate@radiologics.com>

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
    var progress_class = 'in-progress';
    var child_div_class = 'child-resource-status';
    var idPrefix = 'preload-child-';
    var build_child_resource_status_div = function(id, label, resource) {
        return spawn('div', {classes: child_div_class, id: idPrefix + id, data: {resource: resource}}, [
            'Resource <strong>'+label+'</strong> ',
            spawn('span', {classes: 'messages refreshing'}, 'refreshing status...'),
            spawn('span', {classes: 'messages no-pull-res'}, 'is cached locally.'),
            spawn('span', {classes: 'messages push-in-progress'}, 'is currently being archived to an external ' +
                'filesystem. You might notice operations like viewing scans, inspecting resource reports, etc. ' +
                'taking more time then usual, as they might be pulling files back into the XNAT filesystem.'),
            spawn('span', {classes: 'messages need-pull'}, 'is archived to an external filesystem. You may wish to ' +
                '<a class="preload">preload it</a> to speed up operations like viewing scans, inspecting ' +
                'resource reports, etc.'),
            spawn('span', {classes: 'messages pull-in-progress clearfix'}, [
                spawn('div', {classes: 'pull-progress-div'}, spawn('div', {classes: 'pull-progress-bar'}, '0%')),
                'Please wait to attempt operations like viewing scans, inspecting resource reports, etc. You may leave ' +
                'this page and/or navigate to other pages while this process runs, you won\'t interrupt it (returning ' +
                'to this page will show you any progress updates).'
            ]),
            spawn('span', {classes: 'messages error-pull'}, ['preloading was unsuccessful', spawn('span.error-pull-msg')])
        ]);
    };

    XNAT.plugin.filesystems_plugin.item_uri = "";
    XNAT.plugin.filesystems_plugin.preload_container = null;
    XNAT.plugin.filesystems_plugin.preload_container_modal = null;
    XNAT.plugin.filesystems_plugin.preload_container_children = {};
    XNAT.plugin.filesystems_plugin.counter = {};
    XNAT.plugin.filesystems_plugin.poller = null;
    XNAT.plugin.filesystems_plugin.pull_info = {};

    var change_display = function(data, status, error_text) {
        var id = data.id;
        var $container, refresh_parent;
        if (!XNAT.plugin.filesystems_plugin.preload_container_children.hasOwnProperty(id)) {
            $container = $(build_child_resource_status_div(id, data.label, JSON.stringify(data)));
            XNAT.plugin.filesystems_plugin.preload_container_modal.content$.append($container);
            XNAT.plugin.filesystems_plugin.preload_container_children[id] = $container;
        } else {
            $container = XNAT.plugin.filesystems_plugin.preload_container_children[id];
        }
        $container.find('.messages').hide();
        $container.removeClass('warning info error success');
        if (!status) {
            status = data.status;
        } else if (status === "Local") {
            refresh_parent = true;
        }
        switch (status) {
            case "Refreshing":
                XNAT.plugin.filesystems_plugin.preload_container_children[id].addClass("warning");
                $container.find("span.refreshing").show();
                break;
            case "Local":
                if (XNAT.plugin.filesystems_plugin.pull_info.hasOwnProperty(id)) {
                    delete XNAT.plugin.filesystems_plugin.pull_info[id];
                }
                XNAT.plugin.filesystems_plugin.preload_container_children[id].addClass("success");
                $container.find("span.no-pull-res").show();
                if (refresh_parent) {
                    $.each(XNAT.plugin.filesystems_plugin.preload_container_children, function(key, $cont) {
                        if (!$cont.hasClass("success")) {
                            refresh_parent = false;
                            return false;
                        }
                    });
                    if (refresh_parent) refresh_item_status();
                }
                break;
            case "Archived":
                XNAT.plugin.filesystems_plugin.preload_container_children[id].addClass("info");
                $container.find("span.need-pull").show();
                break;
            case "Pull":
                if (!XNAT.plugin.filesystems_plugin.pull_info.hasOwnProperty(id)) {
                    // If not already running, start polling pull progress. Otherwise, this is a refresh,
                    // no need to re-start polling
                    update_pull_progress(data);
                }
                XNAT.plugin.filesystems_plugin.preload_container_children[id].addClass(progress_class + " warning");
                $container.find("span.pull-in-progress").show();
                break;
            case "Push":
                XNAT.plugin.filesystems_plugin.preload_container_children[id].addClass("warning");
                $container.find("span.push-in-progress").show();
                break;
            default:
                XNAT.plugin.filesystems_plugin.preload_container_children[id].addClass("error");
                if (error_text) {
                    $container.find("span.error-pull-msg").text(": " + error_text +
                        ". You might try refreshing the page");
                } else {
                    $container.find("span.error-pull-msg").text(": unrecognized status " + status);
                }
                $container.find("span.error-pull").show();
        }
    };

    var load_display = function(data, error_text) {
        XNAT.plugin.filesystems_plugin.preload_container.removeClass("warning info error success");
        XNAT.plugin.filesystems_plugin.preload_container.find("span.messages").hide();
        XNAT.plugin.filesystems_plugin.preload_container.find("span.close").show();
        try {
            switch (data.status) {
                case "Local":
                    if (XNAT.plugin.filesystems_plugin.preload_container_modal != null) {
                        XNAT.plugin.filesystems_plugin.preload_container_modal.hide();
                    }
                    XNAT.plugin.filesystems_plugin.preload_container.addClass("success");
                    $("span#no-pull").show();
                    break;
                case "Locked":
                    if (XNAT.plugin.filesystems_plugin.preload_container_modal != null) {
                        var any_archived = data['resources'].filter(function (res) {
                            return res.status === 'Archived'
                        }).length > 0;
                        XNAT.plugin.filesystems_plugin.preload_container_modal.content$.find('span#locked-msg').show();
                        if (any_archived) {
                            XNAT.plugin.filesystems_plugin.preload_container_modal.content$.find('span#archived-msg').show();
                        } else {
                            XNAT.plugin.filesystems_plugin.preload_container_modal.content$.find('span#archived-msg').hide();
                        }
                    }
                    XNAT.plugin.filesystems_plugin.preload_container.find("span.close").hide();
                    XNAT.plugin.filesystems_plugin.preload_container.addClass("warning");
                    $.each(data['resources'], function(i, res) {change_display(res);});
                    $("span#locked").show();
                    break;
                case "Archived":
                    if (XNAT.plugin.filesystems_plugin.preload_container_modal != null) {
                        XNAT.plugin.filesystems_plugin.preload_container_modal.content$.find('span#locked-msg').hide();
                        XNAT.plugin.filesystems_plugin.preload_container_modal.content$.find('span#archived-msg').show();
                    }
                    XNAT.plugin.filesystems_plugin.preload_container.addClass("info");
                    $.each(data['resources'], function(i, res) {change_display(res);});
                    $("span#archived").show();
                    break;
                default:
                    throw "Error";
            }
        } catch (err) {
            if (!error_text) {
                error_text = 'issue communicating with server (if you run XNAT behind a reverse-proxy, ' +
                    'this may be the cause)';
            }
            if (XNAT.plugin.filesystems_plugin.preload_container_modal != null) {
                XNAT.plugin.filesystems_plugin.preload_container_modal.hide();
            }
            XNAT.plugin.filesystems_plugin.preload_container.find("span.loading").hide();
            XNAT.plugin.filesystems_plugin.preload_container.addClass("error");
            $("span#error-pull-msg").text(": " + error_text + ". You might try refreshing the page");
            $("span#error-pull").show();
        }
    };


    var update_pull_progress = function(data) {
        var id = data.id;
        if (!XNAT.plugin.filesystems_plugin.counter.hasOwnProperty(id)) {
            XNAT.plugin.filesystems_plugin.counter[id] = 0;
        }
        var progress_bar = $("#" + idPrefix + id + " .pull-progress-bar");
        XNAT.xhr.get({
            url: XNAT.url.restUrl('/xapi/remote_files/pull_progress'),
            data: {resource: id},
            dataType: "text",
            success: function(perc) {
                if (perc === "100%") {
                    change_display(data,"Local");
                } else if (progress_bar.length) {
                    if (XNAT.plugin.filesystems_plugin.counter[id] !== 0) {
                        XNAT.plugin.filesystems_plugin.counter[id] = 0;
                    }
                    if (perc === "-1%") {
                        progress_bar.css("width", "100%")
                            .css("background-color", "#333")
                            .text("Waiting...");
                    } else if (perc === "0%") {
                        progress_bar.css("width", "100%")
                            .css("background-color","#ffaa66")
                            .text("Locating remote files...");
                    } else {
                        progress_bar.css("width", perc)
                            .css("background-color","#ffaa66")
                            .css("color", "#f0f0f0")
                            .text(perc);
                    }
                    update_pull_progress(data);
                }
            },
            error: function(xhr) {
                if (XNAT.plugin.filesystems_plugin.counter[id] < 30) {
                    XNAT.plugin.filesystems_plugin.counter[id]++;
                    progress_bar.css("width", "100%")
                        .css("background-color","#333")
                        .text(xhr.responseText);
                    update_pull_progress(data);
                } else {
                    XNAT.plugin.filesystems_plugin.counter[id] = 0;
                    change_display(data,"error", xhr.responseText);
                }
            }
        });
    };

    var refresh_item_status = function(skipLoadingCheck) {
        if (!skipLoadingCheck && !set_loading()) {
            return;
        }
        XNAT.xhr.get({
            url: XNAT.url.restUrl('/xapi/remote_files/item_status'),
            data: {item: XNAT.plugin.filesystems_plugin.item_uri},
            dataType: 'json',
            success: function(data) {
                if (data.status === "Inactive") {
                    XNAT.plugin.filesystems_plugin.preload_container.hide();
                    return;
                }
                XNAT.plugin.filesystems_plugin.preload_container.show();
                load_display(data);
            },
            error: function(xhr) {
                load_display("error", xhr.responseText)
            },
            complete: function() {
                XNAT.plugin.filesystems_plugin.preload_container.find("span.loading").hide();
            }
        });
    };

    var set_loading = function() {
        var $loadingMsg = XNAT.plugin.filesystems_plugin.preload_container.find("span.loading");
        if (!$loadingMsg.is(":hidden")) {
            return false;
        }
        XNAT.plugin.filesystems_plugin.preload_container.find("span.messages").hide();
        $loadingMsg.show();
        return true;
    };

    var stop_polling = function() {
        if (XNAT.plugin.filesystems_plugin.poller != null) {
            window.clearInterval(XNAT.plugin.filesystems_plugin.poller);
        }
        XNAT.plugin.filesystems_plugin.poller = null;
    };

    var start_polling = function(skipLoadingCheck) {
        if (skipLoadingCheck) {
            // Run it immediately
            refresh_item_status(skipLoadingCheck);
        }
        //TODO figure out how to keep this from prolonging user session
        // XNAT.plugin.filesystems_plugin.poller = window.setInterval(function() {
        //     refresh_item_status(skipLoadingCheck);
        // }, 120000);
    };

    $(document).on('click', 'a.preload', function(){
        var singleRes = false,
            data = {},
            label = 'this item',
            reqData =  {item: XNAT.plugin.filesystems_plugin.item_uri};

        if ($(this).prop('id') !== 'preload-all') {
            singleRes = true;
            var id = $(this).parents('div.'+ child_div_class).prop('id').replace(idPrefix, '');
            if (!XNAT.plugin.filesystems_plugin.preload_container_children.hasOwnProperty(id)) {
                return false;
            }
            var $container = XNAT.plugin.filesystems_plugin.preload_container_children[id];
            if ($container.hasClass(progress_class)) {
                XNAT.ui.dialog.alert("Preloading already in progress...please wait");
                return false;
            }
            data = $(this).parents('div.'+ child_div_class).data('resource');
            label = 'the ' + data.label + ' resource';
            reqData['resource'] = id;
        }

        var waitDialog;
        XNAT.xhr.post({
            url: XNAT.url.restUrl("/xapi/remote_files/pull"),
            data: reqData,
            dataType: "text",
            beforeSend: function () {
                var cont = confirm("You are about to pull all files for " + label + " into " +
                    "the local XNAT archive directory. You would do this to speed up loading data for viewing, " +
                    "downloading, or other web application operations. It will consume space on the server " +
                    "filesystem and may take some time. Do you wish to proceed?");
                if (cont) {
                    stop_polling();
                    if (singleRes) {
                        change_display(data, "Refreshing");
                    } else {
                        waitDialog = XNAT.ui.dialog.static.wait('Requesting preload...');
                        set_loading();
                    }
                }
                return cont;
            },
            success: function() {
                if (singleRes) {
                    change_display(data, "Pull");
                }
                start_polling(!singleRes);
            },
            error: function(xhr) {
                if (singleRes) {
                    change_display(data,"error", xhr.responseText);
                } else {
                    load_display(data, xhr.responseText);
                }
            },
            complete: function() {
                if (waitDialog) {
                    waitDialog.close();
                }
            }
        });
    });

    var make_modal = function() {
        XNAT.plugin.filesystems_plugin.preload_container_modal = XNAT.ui.dialog.init({
            title: 'Remote resource status',
            id: 'child-resource-statuses',
            content: spawn('div', {}, [
                spawn('span', {id: 'archived-msg', classes: 'messages'}, 'Some resources are archived to an external ' +
                    'filesystem. You may <a class="preload" id="preload-all">preload all of them</a>, or preload them ' +
                    'individually below, to speed up operations like viewing scans, inspecting resource reports, etc. '),
                spawn('span', {id: 'locked-msg', classes: 'messages'}, 'Some resources are currently being pushed ' +
                    'to or pulled from an external filesystem. Please wait to perform operations such as viewing scans, ' +
                    'inspecting resource reports, etc. on these resources. <a class="refresh-status">Refresh status</a>.'),
                spawn('span', {classes: 'messages loading'}, 'Please wait... loading status.')
            ]),
            destroyOnClose: false,
            protected: true,
            buttons: [
                {
                    label: 'Close',
                    isDefault: true,
                    close: true
                },
                {
                    label: 'Refresh',
                    isDefault: false,
                    close: false,
                    action: function() {
                        var $parent = $(this).parents('#child-resource-statuses');
                        var $loading = $parent.find('.loading');
                        $parent.find('.messages').hide();
                        $loading.show();
                        refresh_item_status();
                        $loading.hide();
                    }
                }
            ]
        });
        XNAT.plugin.filesystems_plugin.preload_container_modal.content$.find('span.messages').hide();
    };

    $(document).ready(function() {
        if (obj && obj.uri) {
            XNAT.plugin.filesystems_plugin.item_uri = obj.uri.replace("/REST/", "/archive/");
            XNAT.plugin.filesystems_plugin.preload_container = $('#preload-container');

            make_modal();

            start_polling(true);

            XNAT.plugin.filesystems_plugin.preload_container.find("span.close").click(function(){
                XNAT.plugin.filesystems_plugin.preload_container.hide();
            });
        }
    });

    $(document).on('click', 'a.refresh-status', function() {
        var $loading;
        if (XNAT.plugin.filesystems_plugin.preload_container_modal != null &&
            XNAT.plugin.filesystems_plugin.preload_container_modal.isOpen) {
            var $parent = XNAT.plugin.filesystems_plugin.preload_container_modal.content$;
            $loading = $parent.find('.loading');
            $parent.find('.messages').hide();
            $loading.show();
        }
        refresh_item_status();
        if ($loading) $loading.hide();
    });

    $(document).on('click', 'a.locked-or-archived-details', function() {
        if (XNAT.plugin.filesystems_plugin.preload_container_modal != null) {
            XNAT.plugin.filesystems_plugin.preload_container_modal.show();
        }
    });
}));
