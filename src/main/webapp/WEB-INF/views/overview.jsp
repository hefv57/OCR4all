<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="t" tagdir="/WEB-INF/tags" %>
<%@ taglib prefix="s" tagdir="/WEB-INF/tags/settings" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<t:html>
    <t:head projectDataSel="true" processHandler="true">
        <title>OCR4All - Project Overview</title>

        <!-- jQuery DataTables -->
        <link rel="stylesheet" type="text/css" href="resources/css/datatables.min.css">
        <script type="text/javascript" charset="utf8" src="resources/js/datatables.min.js"></script>

        <script type="text/javascript">
            $(document).ready(function() {
                // Initialize project data selection
                initializeProjectDataSelection('ajax/overview/listProjects');

                var datatableReloadIntveral = null;
                // Responsible for initializing and updating datatable contents
                function datatable(){
                    // Allow reinitializing DataTable with new data
                    if( $.fn.DataTable.isDataTable("#overviewTable") ) {
                        $('#overviewTable').DataTable().clear().destroy();
                    }

                    var overviewTable = $('#overviewTable').DataTable( {
                        ajax : {
                            "type"   : "GET",
                            "url"    : "ajax/overview/list",
                            "dataSrc": function (data) { return data; },
                            "error"  : function() {
                                openCollapsibleEntriesExclusively([0]);
                                $('#projectDir').addClass('invalid').focus();
                                // Prevent datatable from reloading an invalid directory
                                clearInterval(datatableReloadIntveral);
                            }
                        },
                        columns: [
                            { title: "Page Identifier", data: "pageId" },
                            { title: "Preprocessing", data: "preprocessed" },
                            { title: "Noise Removal", data: "despeckled"},
                            { title: "Segmentation", data: "segmented" },
                            <c:if test='${(not empty processingMode) && (processingMode == "Directory")}'>
                            { title: "Region Extraction", data: "segmentsExtracted" },
                            </c:if>
                            { title: "Line Segmentation", data: "linesExtracted" },
                            { title: "Recognition", data: "recognition" },
                            { title: "Ground Truth", data: "groundtruth" },
                        ],
                        createdRow: function( row, data, index ){
                            $('td:first-child', row).html('<a href="pageOverview?pageId=' + data.pageId + '">' + data.pageId + '</a>');
                            $.each( $('td:not(:first-child)', row), function( idx, td ) {
                                if( $(td).html() === 'true' ) {
                                    $(td).html('<i class="material-icons green-text">check</i>');
                                }
                                else {
                                    $(td).html('<i class="material-icons red-text">clear</i>');
                                }
                            });
                        },
                        initComplete: function() {
                            openCollapsibleEntriesExclusively([2]);

                            // Initialize select input
                            $('select').material_select();

                            // Update overview continuously
                            datatableReloadIntveral = setInterval( function() {
                                overviewTable.ajax.reload(null, false);
                            }, 10000);
                        },
                    });
                }

                // Responsible for verification and loading of the project
                function projectInitialization(newPageVisit, allowLegacy=false) {
                    var ajaxParams = { "projectDir" : $('#projectDir').val(), "imageType" : $('#imageType').val(), "processingMode" : $('#processingMode').val() };
                    // Check if directory exists
                    $.get( "ajax/overview/checkDir?",
                        // Only force new session if project loading is triggered by user
                        $.extend(ajaxParams, {"resetSession" : !newPageVisit})
                    )
                    .done(function( data ) {
                        if( data === true ) {
                            $.get( "ajax/overview/validate?" )
                            .done(function( data ) {
                                if( data === true ) {
                                    // Check if filenames match project specific naming convention
                                    $.get( "ajax/overview/validateProject?", ajaxParams )
                                    .done(function( data ) {
                                         if( data === true ) {
                                            if( newPageVisit && !allowLegacy && ($('#processingMode').val() === "Pagexml") ){
                                                // Check if the project still contains legacy files
                                                $.get("ajax/overview/isLegacy")
                                                .done(function(data) {
                                                    if( data === true ){
                                                        $('#modal_legacy').modal({
                                                            dismissible: true
                                                        });
                                                        $('#modal_legacy').modal('open');
                                                    } else {
                                                        projectInitialization(true, true);
                                                    }
                                                });
                                            } else {
                                                // Check if dir only houses pdf files and no images
                                                $.get("ajax/overview/checkpdf")
                                                    .done(function(data) {
                                                    if( data === true) {
                                                        openCollapsibleEntriesExclusively([0]);
                                                        $('#modal_convertpdf').modal({
                                                            dismissible: false
                                                        });
                                                        $('#modal_convertpdf').modal('open');
                                                    }
                                                    else {
                                                        // Two scenarios for loading overview page:
                                                        // 1. Load or reload new project: Page needs reload to update GTC_Web link in navigation
                                                        // 2. Load project due to revisiting overview page: Only datatable needs to be initialized
                                                        if( newPageVisit == false ) {
                                                            location.reload();
                                                        }
                                                        else {
                                                            // Load datatable after the last process update is surely finished
                                                            datatable();
                                                        }
                                                    }
                                                });
                                            }
                                         }
                                         else{
                                             openCollapsibleEntriesExclusively([0]);
                                             $('#modal_imageAdjust').modal({
                                                 dismissible: false
                                             });
                                             $('#modal_imageAdjust').modal('open');
                                         }
                                    });
                                }
                                else{
                                    // Unload project if directory structure is not valid
                                    $.get( "ajax/overview/invalidateSession" );

                                    openCollapsibleEntriesExclusively([0]);
                                    $('#modal_validateDir').modal('open');
                                }
                            });
                        }
                        else {
                            // Unload project if directory does not exist
                            $.get( "ajax/overview/invalidateSession" );

                            openCollapsibleEntriesExclusively([0]);
                            $('#projectDir').addClass('invalid').focus();
                            // Prevent datatable from reloading an invalid directory
                            clearInterval(datatableReloadIntveral);

                            $('#modal_checkDir_failed').modal('open');
                        }
                    })
                    .fail(function( data ) {
                        $('#modal_checkDir_failed').modal('open');
                    });
                }

                $('button[data-id="loadProject"]').click(function() {
                    if( $.trim($('#projectDir').val()).length === 0 ) {
                        openCollapsibleEntriesExclusively([0]);
                        $('#projectDir').addClass('invalid').focus();
                    }
                    else {
                        // Only load project if no conversion process is running
                        setTimeout(function() {
                            if( !isProcessRunning() ) {
                                projectInitialization(false);
                            }
                            else {
                                $('#modal_inprogress').modal('open');
                            }
                        }, 500);
                    }
                });

                // Execute file rename only after the user agreed
                $('#directConvert, #backupAndConvert').click(function() {
                    // Initialize process handler (wait time, due to delayed AJAX process start)
                    setTimeout(function() {
                        initializeProcessUpdate("overview", [ 0 ], [ 1 ], false);
                    }, 500);

                    // Start process
                    var ajaxParams = {"backupImages" : ( $(this).attr('id') == 'backupAndConvert' )};
                    $.post( "ajax/overview/adjustProjectFiles", ajaxParams )
                    .done(function( data ) {
                        // Load datatable after the last process update is surely finished
                        setTimeout(function() {
                            datatable();
                        }, 2000);
                    })
                    .fail(function( data ) {
                        $('#modal_adjustImages_failed').modal('open');
                    });
                });
                $('#cancelConvert').click(function() {
                    setTimeout(function() {
                        // Unload project if user refuses the mandatory adjustments
                        if( !isProcessRunning() ) {
                            $.get( "ajax/overview/invalidateSession" );
                        }
                    }, 500);
                });
                $('#cancelConvertPdf', '#cancelLegacy').click(function() {
                    setTimeout(function() {
                        // Unload project if user refuses the mandatory adjustments
                        if( !isProcessRunning() ) {
                            $.get( "ajax/overview/invalidateSession" );
                        }
                    }, 500);
                });
                $('#convertToPdf, #convertToPdfWithBlanks').click(function() {
                    // Initialize process handler (wait time, due to delayed AJAX process start)
                    setTimeout(function() {
                        initializeProcessUpdate("overview", [ 0 ], [ 1 ], false);
                    }, 500);

                    // Start converting PDF
                    var ajaxParams = {"deleteBlank" : ( $(this).attr('id') == 'convertToPdf' ), "dpi" : document.getElementById('dpi').value};
                    $.post( "ajax/overview/convertProjectFiles", ajaxParams )
                        .done(function( data ) {
                            // Load datatable after the last process update is surely finished
                            setTimeout(function() {
                                datatable();
                            }, 2000);
                        })
                        .fail(function( data ) {
                        });
                });
                $('#continueLegacy').click(function() {
                    setTimeout(function() {
                        // Unload project if user refuses the mandatory adjustments
                        if( !isProcessRunning() ) {
                            projectInitialization(true, true);
                        }
                    }, 500);
                });
                $('#openLegacy').click(function() {
                    setTimeout(function() {
                        // Unload project if user refuses the mandatory adjustments
                        if( !isProcessRunning() ) {
                            const $processingMode = $("#processingMode");
                            $processingMode.val("Directory");
                            $processingMode.material_select();
                            projectInitialization(false, false);
                        }
                    }, 500);
                });
                $('button[data-id="cancelProjectAdjustment"]').click(function() {
                    cancelProcess();

                    // Unload project if user cancels the mandatory adjustments
                    setTimeout(function() {
                        $.get( "ajax/overview/invalidateSession" );
                    }, 500);
                });

                // Trigger overview table fetching on pageload
                if( $.trim($('#projectDir').val()).length !== 0 ) {
                    initializeProcessUpdate("overview", [ 0 ], [ 1 ], false);
                    setTimeout(function() {
                        // Load project only if no conversion process is currently running
                        if( !isProcessRunning() ) {
                            projectInitialization(true);
                        }
                    }, 500);
                } else {
                    openCollapsibleEntriesExclusively([0]);
                }
                //checking if dpi input value si valid and disabling button if not
                $('#dpi').on('input', function(e) {
                    if(!this.checkValidity()){
                        $('#convertToPdf').addClass("disabled");
                        $('#convertToPdfWithBlanks').addClass("disabled");
                    }else{
                        $('#convertToPdf').removeClass("disabled");
                        $('#convertToPdfWithBlanks').removeClass("disabled");
                    }
                });
            });


        </script>
    </t:head>
    <t:body heading="Project Overview" processModals="true">
        <div class="container">
            <div class="section">
                <button data-id="loadProject" class="btn waves-effect waves-light" type="submit" name="action">
                    Load Project
                    <i class="material-icons right">send</i>
                </button>
                <button data-id="cancelProjectAdjustment" class="btn waves-effect waves-light" type="submit" name="action">
                    Cancel Project Adjustment
                    <i class="material-icons right">cancel</i>
                </button>

                <ul class="collapsible" data-collapsible="accordion">
                    <li>
                        <div class="collapsible-header"><i class="material-icons">settings</i>Settings</div>
                        <div class="collapsible-body">
                            <s:overview></s:overview>
                        </div>
                    </li>
                    <li style="display: block;">
                        <div class="collapsible-header"><i class="material-icons">info_outline</i>Status</div>
                        <div class="collapsible-body">
                            <div class="status"><p>Status: <span>No Project Overview process running</span></p></div>
                            <div class="progress">
                                <div class="determinate"></div>
                            </div>
                        </div>
                    </li>
                    <li>
                        <div class="collapsible-header"><i class="material-icons">dehaze</i>Overview</div>
                        <div class="collapsible-body">
                            <c:if test='${(not empty processingMode) && (processingMode == "Directory")}'>
                            <p class="red-text">Loaded with Legacy (This mode will be removed in future OCR4all versions)</p>    
                            </c:if>
                            <table id="overviewTable" class="display centered" width="100%"></table>
                        </div>
                    </li>
                </ul>

                <button data-id="loadProject" class="btn waves-effect waves-light" type="submit" name="action">
                    Load Project
                    <i class="material-icons right">send</i>
                </button>
                <button data-id="cancelProjectAdjustment" class="btn waves-effect waves-light" type="submit" name="action">
                    Cancel Project Adjustment
                    <i class="material-icons right">cancel</i>
                </button>
            </div>
        </div>

        <div id="modal_imageAdjust" class="modal">
            <div class="modal-content">
                <h4 class="red-text">Attention</h4>
                    <p>
                        Some or all files do not match the required format of this software.<br />
                        <br />
                        The requirements are:<br />
                        1. All image files need to be in PNG format and have a ".png" file ending<br />
                        2. All image files need to be named accordingly: "0001.png, 0002.png, 0003.png, ..."<br />
                        <br />
                        To be able to load your project successfully the affected files need to be adjusted.<br />
                        Please choose one of the offered possibilities to continue.<br />
                        <br />
                        Short explanation of the different possibilities:<br />
                        1. <i>Convert files directly</i><br />
                        &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;All image files are adjusted automatically. The existing files will be replaced!<br />
                        &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Use at your own risk, e.g. if you already have a backup of your files or do not need one.<br />
                        2. <i>Backup and convert files</i><br />
                        &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;A backup of all image files will be done automatically before the adjustment.<br />
                        &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;This is the safe option because a backup is create before any changes are made.
                    </p>
            </div>
            <div class="modal-footer">
                <a href="#!" id="cancelConvert" class="modal-action modal-close waves-effect waves-green btn-flat ">Cancel</a>
                <a href="#!" id="directConvert" class="modal-action modal-close waves-effect waves-green btn-flat">Convert files directly</a>
                <a href="#!" id="backupAndConvert" class="modal-action modal-close waves-effect waves-green btn-flat">Backup and convert files</a>
            </div>
         </div>
        <div id="modal_legacy" class="modal">
            <div class="modal-content">
                <h4 class="red-text">Warning: Legacy files found</h4>
                <p>The project you are about to load with the "Latest" mode, includes files of an old version of OCR4all.</p>
                <p>Please use the "Legacy" option under "Project processing mode" for those projects, 
                    since some processing results from your project may otherwise not be available.</p>
                <p>Opening and editing your project with the "Latest" processing mode will not delete any legacy data from your project,
                    but existing legacy data from "Line Segmentations", "Recognitions" and "Ground Truth Productions" will not be accessible in this mode.</p>
                <p>Be aware that the "Legacy" option will be removed in future OCR4all releases.</p>
            </div>
            <div class="modal-footer">
                <a href="#!" id="continueLegacy" class="modal-action modal-close waves-effect waves-green btn-flat">Continue with Latest</a>
                <a href="#!" id="openLegacy" class="modal-action modal-close waves-effect waves-green btn-flat">Load with Legacy</a>
                <a href="#!" id="cancelLegacy" class="modal-action modal-close waves-effect waves-green btn-flat">Cancel</a>
            </div>
        </div>
        <div id="modal_convertpdf" class="modal">
            <div class="modal-content">
                <h4 class="red-text">Convert PDF files</h4>
                <table class="compact">
                    <tbody>
                    <tr>
                        <td><p>
                            The required PNG format was not found in the input folder.<br />
                            A PDF document was found instead.
                            <br />
                            <br />
                            To be able to load your project successfully the PDF needs to be converted to separate PNG files.<br />
                            Please choose one of the offered possibilities to continue.<br /></p></td>
                        <td></td>
                    </tr>
                    <tr>
                        <td><p>
                            The default value of the DPI used when rendering is set to 300: <br />
                            Please note that a higher DPI corresponds to a higher rendering time.
                            <br />
                            <br />
                            This may take a while.</p></td>
                        <td>
                            <br />
                            <div class="input-field">
                                <input id="dpi" type="number" value="300" min="50" max="2000" step="10"/>
                                <label for="dpi" data-type="int" data-error="Has to be integer">Rendering DPI:</label>
                            </div>
                        </td>
                    </tr>
                    </tbody>
                </table>
            </div>
            <div class="modal-footer">
                <a href="#!" id="cancelConvertPdf" class="modal-action modal-close waves-effect waves-green btn-flat ">Cancel</a>
                <a href="#!" id="convertToPdf" class="modal-action modal-close waves-effect waves-green btn-flat">Convert PDF and delete blank pages</a>
                <a href="#!" id="convertToPdfWithBlanks" class="modal-action modal-close waves-effect waves-green btn-flat">convert pdf and leave blank pages</a>
            </div>
        </div>
        <div id="modal_adjustImages_failed" class="modal">
            <div class="modal-content red-text">
                <h4>Error</h4>
                    <p>
                        Adjustment of image files to the required format failed.<br />
                        Due to this error the project could not be loaded.
                    </p>
            </div>
            <div class="modal-footer">
                <a href="#!" id='agree' class="modal-action modal-close waves-effect waves-green btn-flat">Agree</a>
            </div>
         </div>
        <div id="modal_validateDir" class="modal">
            <div class="modal-content red-text">
                <h4>Error</h4>
                    <p>
                        The selected project directory does not have the required structure.<br />
                        Please put the project related image files in a sub-directory named "input".<br />
                        Until then the project cannot be loaded successfully.
                    </p>
            </div>
            <div class="modal-footer">
                <a href="#!" id='agree' class="modal-action modal-close waves-effect waves-green btn-flat">Agree</a>
            </div>
         </div>
        <div id="modal_checkDir_failed" class="modal">
            <div class="modal-content red-text">
                <h4>Error</h4>
                    <p>
                        The specified project directory could not accessed.<br />
                        Due to this error the project could not be loaded.
                    </p>
            </div>
            <div class="modal-footer">
                <a href="#!" id='agree' class="modal-action modal-close waves-effect waves-green btn-flat">Agree</a>
            </div>
         </div>
    </t:body>
</t:html>
