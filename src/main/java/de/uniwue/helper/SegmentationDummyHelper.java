package de.uniwue.helper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.w3c.dom.Document;

import de.uniwue.config.ProjectConfiguration;
import de.uniwue.feature.ProcessConflictDetector;
import de.uniwue.feature.pageXML.PageXMLWriter;

public class SegmentationDummyHelper {
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
     * Processing structure of the project
     * Possible values: { Directory, Pagexml }
     */
    private String processingMode;

    /**
     * Status of the SegmentationLarex progress
     */
    private int progress = -1;

    /**
     * Indicates if the process should be cancelled
     */
    private boolean stop = false;

    /**
     * Constructor
     *
     * @param projectDir Path to the project directory
     * @param projectImageType Type of the project (binary,gray)
     */
    public SegmentationDummyHelper(String projDir, String projectImageType, String processingMode) {
    	this.processingMode = processingMode;
        this.projectImageType = projectImageType;
        projConf = new ProjectConfiguration(projDir);
    }

    /**
     * Moves the extracted files of the segmentation process to the OCR project folder
     *
     * @param pageIds Identifiers of the pages (e.g 0002,0003)
     * @param segmentationImageType Image type of the segmentation (binary, despeckled)
     * @param findImages Find images and add to Page XML
     * @param minImageSize Min size of ImageRegions if findImages is active
     * @param imageDilationX Dilation X factor to preprocess image before contour finding
     * @param imageDilationY Dilation Y factor to preprocess image before contour finding
     * @throws IOException
     * @throws TransformerException 
     * @throws ParserConfigurationException 
     */
    public void execute(List<String> pageIds, String segmentationImageType, 
						boolean findImages, float minImageSize,
						int imageDilationX, int imageDilationY)
						throws IOException, ParserConfigurationException, TransformerException {
        stop = false;
        progress = 0;

        File ocrDir = new File(projConf.OCR_DIR);
        if (!ocrDir.exists())
            ocrDir.mkdir();

        SegmentationHelper segmentationHelper = new SegmentationHelper(projConf.PROJECT_DIR, this.projectImageType, processingMode);
        segmentationHelper.deleteOldFiles(pageIds);

        String projectSpecificPreprocExtension = projConf.getImageExtensionByType(projectImageType);

        int processedPages = 0;
        // generates XML files for each page
        File segmentationTypeDir = new File(projConf.getImageDirectoryByType(segmentationImageType));
        if (segmentationTypeDir.exists()){
            File[] imageFiles = segmentationTypeDir.listFiles((d, name) -> name.endsWith(projectSpecificPreprocExtension));
            for (File file : imageFiles) {
                if (pageIds.contains(file.getName().replace(projectSpecificPreprocExtension, "")) && stop == false) {
                    extractXML(file, projConf.OCR_DIR, findImages, minImageSize, imageDilationX, imageDilationY);
                    progress = (int) ((double) processedPages / pageIds.size() * 100);
                }
            }
        }

        progress = 100;
    }

    /**
     * Extract a Dummy PAGE XML from an image file with one paragraph
     *  
     * @param file Image file to create a PAGE XML for
     * @param outputFolder Folder to save PAGE XML in
     * @param findImages Find images and add to Page XML
     * @param minImageSize Min size of ImageRegions if findImages is active
     * @param imageDilationX Dilation X factor to preprocess image before contour finding
     * @param imageDilationY Dilation Y factor to preprocess image before contour finding
     * @throws ParserConfigurationException
     * @throws TransformerException
     */
    public void extractXML(File file, String outputFolder,
							boolean findImages, float minImageSize,
							int imageDilationX, int imageDilationY)
    				throws ParserConfigurationException, TransformerException {
        String imagePath = file.getAbsolutePath();
        String imageFilename = imagePath.substring(imagePath.lastIndexOf(File.separator) + 1).
        								 replace(projConf.getImageExtensionByType(projectImageType), projConf.IMG_EXT);
        final Mat image = Imgcodecs.imread(imagePath);
        if(image.width() == 0)
            return;
        
        Collection<Rect> imageRegions = new ArrayList<>();
        if(findImages)
        	imageRegions = findImages(image, minImageSize, imageDilationX, imageDilationY);
        Document xml = PageXMLWriter.getPageXML(image, imageFilename, "2017-07-15", imageRegions);
        PageXMLWriter.saveDocument(xml, imageFilename, outputFolder);
        image.release();
    }
    
    /**
     * Image Search algorithm via image dilation and image min sizes to categorize contours into images.
     * 
     * @param image Source image
     * @param minImageSize Min size of a contour for categorizing it as an ImageRegion
     * @param imageRemovalDilationX Dilation factor to preprocess image before contour finding in X direction
     * @param imageRemovalDilationY Dilation factor to preprocess image before contour finding in X direction
     * @return
     */
    public Collection<Rect> findImages(Mat image, float minImageSize,
    										 int imageRemovalDilationX, int imageRemovalDilationY){
    		final Collection<Rect> imageRects = new ArrayList<>();
			final Mat gray = new Mat();
			final Mat binary = new Mat();
			Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
			Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_OTSU);
			gray.release();
			final Mat inverted = new Mat(binary.size(), binary.type(), new Scalar(255));
			Core.subtract(inverted, binary, inverted);

			final Mat dilate = new Mat();
			final Mat kernel = Mat.ones(new Size(imageRemovalDilationX, imageRemovalDilationY),
										CvType.CV_8U);
			Imgproc.dilate(binary, dilate, kernel);
			binary.release();
			kernel.release();

			List<MatOfPoint> contours = new ArrayList<>();
			Imgproc.findContours(dilate, contours, new Mat(),
								 Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

			for (final MatOfPoint contour : contours) 
				imageRects.add(Imgproc.boundingRect(contour));
		
			return imageRects;
    } 

    /**
     * Returns the progress of the job
     *
     * @return Progress of preprocessAllPages function
     */
    public int getProgress() {
        return progress;
    }

    /**
     * Resets the progress (use if an error occurs)
     */
    public void resetProgress() {
        progress = -1;
    }

    /**
     * Cancels the process
     */
    public void cancelProcess() {
        stop = true;
    }

    /**
     * Determines conflicts with the process
     *
     * @param currentProcesses Processes that are currently running
     * @param inProcessFlow Indicates if the process is executed within the ProcessFlow
     * @return Type of process conflict
     */
    public int getConflictType(List<String> currentProcesses, boolean inProcessFlow) {
        return ProcessConflictDetector.segmentationDummyConflict(currentProcesses, inProcessFlow);
    }
}
