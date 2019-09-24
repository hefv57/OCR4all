package de.uniwue.helper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.uniwue.config.ProjectConfiguration;
import de.uniwue.feature.ProcessConflictDetector;
import de.uniwue.feature.ProcessHandler;
import de.uniwue.feature.ProcessStateCollector;

/**
 * Helper class for recognition module
 */
public class RecognitionHelper {
    /**
     * Object to access project configuration
     */
    private ProjectConfiguration projConf;

    /**
     * Image type of the project
     * Possible values: { Binary, Gray }
     */
    private String projectImageType;


    /**
     * Object to use generic functionalities
     */
    private GenericHelper genericHelper;

    /**
     * Object to determine process states
     */
    private ProcessStateCollector procStateCol;

    /**
     * Helper object for process handling
     */
    private ProcessHandler processHandler;

    /**
     * Progress of the Recognition process
     */
    private int progress = -1;

    /**
     * Indicates if a Recognition process is already running
     */
    private boolean RecognitionRunning = false;

    /**
     * Last time the images/pagexml are modified
     */
    private Map<String,Long> imagesLastModified;

    /**
     * Structure to monitor the progress of the process
     * pageId : segmentId : lineSegmentId : processedState
     *
     * Structure example:
     * {
     *     "0002": {
     *         "0002__000__paragraph" : {
     *             "0002__000__paragraph__000" : true,
     *             "0002__000__paragraph__001" : false,
     *             ...
     *         },
     *         ...
     *     },
     *     ...
     * }
     */
    private TreeMap<String,TreeMap<String, TreeMap<String, Boolean>>> processState =
        new TreeMap<String, TreeMap<String, TreeMap<String, Boolean>>>();

    /**
     * Constructor
     *
     * @param projectDir Path to the project directory
     * @param projectImageType Type of the project (binary, gray)
     * 
     */
    public RecognitionHelper(String projectDir, String projectImageType) {
        this.projectImageType = projectImageType;
        projConf = new ProjectConfiguration(projectDir);
        genericHelper = new GenericHelper(projConf);
        procStateCol = new ProcessStateCollector(projConf, projectImageType);
        processHandler = new ProcessHandler();
    }

    /**
     * Gets the process handler object
     *
     * @return Returns the process Helper
     */
    public ProcessHandler getProcessHandler() {
        return processHandler;
    }

    /**
     * Initializes the structure with which the progress of the process can be monitored
     *
     * @param pageIds Identifiers of the chosen pages (e.g 0002,0003)
     * @throws IOException
     */
    public void initialize(List<String> pageIds) throws IOException {
        // Init the listener for image modification
        imagesLastModified = new HashMap<>();
        for(String pageId: pageIds) {
            final String pageXML = projConf.OCR_DIR + pageId + projConf.CONF_EXT;
            imagesLastModified.put(pageXML,new File(pageXML).lastModified());
        }
    }

    /**
     * Returns the absolute path of all line segment images for the pages in the processState
     *
     * @param pageIds Identifiers of the chosen pages (e.g 0002,0003)
     * @return List of line segment images
     * @throws IOException 
     */
    public List<String> getLineSegmentImagesForCurrentProcess(List<String> pageIds) throws IOException {
        List<String> LineSegmentsOfPage = new ArrayList<String>();
        for (String pageId : processState.keySet()) {
            for (String segmentId : processState.get(pageId).keySet()) {
                for (String lineSegmentId : processState.get(pageId).get(segmentId).keySet()) {
                    LineSegmentsOfPage.add(projConf.PAGE_DIR + pageId + File.separator + segmentId +
                        File.separator + lineSegmentId + projConf.getImageExtensionByType(projectImageType));
                }
            }
        }
        return LineSegmentsOfPage;
    }

    /**
     * Returns the progress of the process
     *
     * @return Progress percentage
     * @throws IOException 
     */
    public int getProgress() throws IOException {
        // Prevent function from calculation progress if process is not running
        if (RecognitionRunning == false)
            return progress;

        int modifiedCount = 0;
        if(imagesLastModified != null) {
            for(String pagexml : imagesLastModified.keySet()) {
                if(imagesLastModified.get(pagexml) < new File(pagexml).lastModified()) {
                    modifiedCount++;
                }
            }
            progress = (modifiedCount*100) / imagesLastModified.size();
        } else {
            progress = -1;
        }
        return progress;
    }

    /**
     * Extracts checkpoints of a String joined by a whitespace
     *
     * @return List of checkpoints
     * @throws IOException 
     */
    public List<String> extractModelsOfJoinedString(String joinedckptString){ 
        String [] checkpoints = joinedckptString.split(ProjectConfiguration.MODEL_EXT + " ");
        List<String> ckptList = new ArrayList<String>();
        Iterator <String> ckptIterator= Arrays.asList(checkpoints).iterator();
        while (ckptIterator.hasNext()) {
            String ckpt = ckptIterator.next();
            if (ckptIterator.hasNext())
                ckpt = ckpt + ProjectConfiguration.MODEL_EXT;
            ckptList.add(ckpt);
        }
        return ckptList;	
    }
    /**
     * Executes OCR on a list of pages
     * Achieved with the help of the external python program "calamary-predict"
     *
     * @param pageIds Identifiers of the pages (e.g 0002,0003)
     * @param cmdArgs Command line arguments for "calamary-predict"
     * @param useAabbyyXml determines if calamari will use aabbyyXML schema as dataset
     * @throws IOException
     */
    public void execute(List<String> pageIds, List<String> cmdArgs, boolean useAabbyyXml) throws IOException {
        RecognitionRunning = true;
        progress = 0;
        int index;
        if (cmdArgs.contains("--checkpoint")) {
            index = cmdArgs.indexOf("--checkpoint");
            for(String ckpt : extractModelsOfJoinedString(cmdArgs.get(index + 1))) {
                if (new File(ckpt).exists() == false)
                    throw new IOException("Model does not exist under the specified path");
            }
        }
        String test = "0001";
        pageIds.add(test);
		// Reset recognition data
		deleteOldFiles(pageIds);
		initialize(pageIds);

        List<String> command = new ArrayList<String>();
        List<String> lineSegmentImages = getLineSegmentImagesForCurrentProcess(pageIds);
        command.add("--files");
        // Create temp json file with all segment images (to not overload parameter list)
		// Temp file in a temp folder named "calamari-<random numbers>.json"
        File segmentListFile = File.createTempFile("calamari-",".json");
        segmentListFile.deleteOnExit(); // Delete if OCR4all terminates
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode segmentList = mapper.createArrayNode();
        for (String pageId : pageIds) {
            // Add affected images with their absolute path to the json file
            segmentList.add(projConf.getImageDirectoryByType(projectImageType) + pageId + 
                                projConf.getImageExtensionByType(projectImageType));
        }
        ObjectNode segmentObj = mapper.createObjectNode();
        segmentObj.set("files", segmentList);
        ObjectWriter writer = mapper.writer();
        writer.writeValue(segmentListFile, segmentObj); 
        command.add(segmentListFile.toString());
        
        
        //Add checkpoints
        Iterator<String> cmdArgsIterator = cmdArgs.iterator();
        while (cmdArgsIterator.hasNext()) {
            String arg = cmdArgsIterator.next();
            command.add(arg);
            if (arg.equals("--checkpoint") && cmdArgsIterator.hasNext()) {
                command.addAll(extractModelsOfJoinedString(cmdArgsIterator.next()));
            }
        }

        command.add("--no_progress_bars");

        command.add("--dataset");
        if(useAabbyyXml) {
            command.add("ABBYY");
        } else{
            command.add("PAGEXML");
        }
        // Set output extension to input extension in order to overwrite the original file
        // (default would've been .pred.xml)
        command.add("--extension");
        command.add(".xml");

        processHandler = new ProcessHandler();
        processHandler.setFetchProcessConsole(true);
        processHandler.startProcess("calamari-predict", command, false);

        // Execute progress update to fill processState data structure with correct values
        getProgress();
        // Process extension to ocropus-gpageseg script
        createSkippedSegments();

        progress = 100;
        RecognitionRunning = false;
        
        // Clean up temp segmentListFile
        segmentListFile.delete();
    }

    /**
     * Resets the progress (use if an error occurs)
     */
    public void resetProgress() {
        RecognitionRunning = false;
        progress = -1;
    }

    /**
     * Cancels the process
     */
    public void cancelProcess() {
        if (processHandler != null)
            processHandler.stopProcess();
        RecognitionRunning = false;
    }

    /**
     * Returns the Ids of the pages, for which line segmentation was already executed
     *
     * @return List with page ids
     * @throws IOException 
     */
    public ArrayList<String> getValidPageIds() throws IOException {
        // Get all pages and check which ones are already line segmented
        ArrayList<String> validPageIds = new ArrayList<String>();
        ArrayList<String> allPageIds = genericHelper.getPageList("Original");
        for (String pageId : allPageIds) {
            if (procStateCol.lineSegmentationState(pageId) == true)
                validPageIds.add(pageId);
        }

        Collections.sort(validPageIds);
        return validPageIds;
    }

    /**
     * Deletion of old process related files
     *
     * @param pageIds Identifiers of the pages (e.g 0002,0003)
     */
    public void deleteOldFiles(List<String> pageIds) throws IOException {
        // Delete potential TextEquivs already existing in the page xmls
        for(String pageId : pageIds) {
            File pageXML = new File(projConf.OCR_DIR + pageId + projConf.CONF_EXT);
            if (!pageXML.exists())
                return;
            
            // Load pageXML and replace/delete all Textline text content
            String pageXMLContent = new String(Files.readAllBytes(pageXML.toPath()));
            pageXMLContent = pageXMLContent.replaceAll("\\<TextEquiv[^>]+?index=\"[^0]\"[^>]*?\\>[^<]*?\\<\\/TextEquiv\\>", "");
            
            // Save new pageXML
            try (FileWriter fileWriter = new FileWriter(pageXML)) {
                fileWriter.write(pageXMLContent);
                fileWriter.flush();
                fileWriter.close();
            }
    	}
    }

    /**
     * Creates the recognition files of the linesegments that were skipped by the ocropus-rpred script
     *
     * @throws IOException
     */
    public void createSkippedSegments() throws IOException{
        for(String pageId : processState.keySet()) {
            for(String segmentId :processState.get(pageId).keySet()) {
                for (String lineSegmentId : processState.get(pageId).get(segmentId).keySet()) {
                    if (processState.get(pageId).get(segmentId).get(lineSegmentId) == true)
                        continue;

                    FileUtils.writeStringToFile(new File(projConf.PAGE_DIR + pageId + File.separator +
                        segmentId + File.separator + lineSegmentId + projConf.REC_EXT), "", "UTF8");
                }
            }
        }
    }

    /**
     * Checks if process depending files already exist
     *
     * @param pageIds Identifiers of the pages (e.g 0002,0003)
     * @return Information if files exist
     */
    public boolean doOldFilesExist(String[] pageIds) {
        for (String pageId : pageIds) {
            if (procStateCol.recognitionState(pageId) == true)
                return true;
        }
        return false;
    }

    /**
     * Lists all available Models from the model directory
     * Consider the subsequent information to load models correctly
     *
     * Possible model location directories:
     * ProjectConfiguration.PROJ_MODEL_DEFAULT_DIR
     * ProjectConfiguration.PROJ_MODEL_CUSTOM_DIR
     *
     * Model path structures on the filesystem:
     * Default: OS_PATH/{TRAINING_IDENTIFIER}/{ID}.ckpt.json
     * Custom:  OS_PATH/{PROJECT_NAME}/{TRAINING_IDENTIFIER}/{ID}.ckpt.json
     *
     * Example: /var/ocr4all/models/default/Baiter_000/Baiter.ckpt.json
     * Display: Baiter_000/Baiter
     * Example: /var/ocr4all/models/custom/Bibel/0/0.ckpt.json
     * Display: Bibel/0/0
     * Example: /var/ocr4all/models/custom/Bibel/heading/0.ckpt.json
     * Display: Bibel/heading/0
     *
     * The models need to be in the following structure:
     * ANY_PATH/{MODEL_NAME}/ANY_NAME.ckpt.json
     *
     * @return Map of models (key = modelName | value = path)
     * @throws IOException 
     */
    public static TreeMap<String, String> listModels() throws IOException{
        TreeMap<String, String> models = new TreeMap<String, String>();

        File modelsDir = new File(ProjectConfiguration.PROJ_MODEL_DIR);
        if (!modelsDir.exists())
            return models;

        // Add all models to map (follow symbolic links on the filesystem due to Docker container)
        Files.walk(Paths.get(ProjectConfiguration.PROJ_MODEL_DIR), FileVisitOption.FOLLOW_LINKS)
        .map(Path::toFile)
        .filter(fileEntry -> fileEntry.getName().endsWith(ProjectConfiguration.MODEL_EXT))
        .forEach(
            fileEntry -> {
                // Remove OS path and model extension from display string (only display significant information)
                String modelName = fileEntry.getAbsolutePath();
                modelName = modelName.replace(ProjectConfiguration.PROJ_MODEL_DEFAULT_DIR, "");
                modelName = modelName.replace(ProjectConfiguration.PROJ_MODEL_CUSTOM_DIR, "");
                modelName = modelName.replace(ProjectConfiguration.MODEL_EXT, "");

                models.put(modelName, fileEntry.getAbsolutePath());
        });

        return models;
    }

    /**
     * Determines conflicts with the process
     *
     * @param currentProcesses Processes that are currently running
     * @param inProcessFlow Indicates if the process is executed within the ProcessFlow
     * @return Type of process conflict
     */
    public int getConflictType(List<String> currentProcesses, boolean inProcessFlow) {
        return ProcessConflictDetector.recognitionConflict(currentProcesses, inProcessFlow);
    }
}
