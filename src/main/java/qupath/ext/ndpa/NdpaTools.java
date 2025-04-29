package qupath.ext.ndpa;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.locationtech.jts.geom.util.PolygonExtracter;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.tools.ColorToolsFX;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.openslide.jna.OpenSlide;
import qupath.lib.images.servers.openslide.jna.OpenSlideLoader;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Class to support reading and writing NDPA annotation files in QuPath.
 * Based on https://github.com/diapath/qupath_scripts/blob/main/read_write_ndpa.groovy
 *
 * Offset values using ImageIO instead of OpenSlide
 * Based on Pete Bankhead's suggestion: https://forum.image.sc/t/access-to-image-metadata/43842/7
 * 
 * @author Egor Zindy
 */
public class NdpaTools {

	private static final Logger logger = LoggerFactory.getLogger(NdpaTools.class);

    // Helper function
    private static String getTextContent(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            Node node = list.item(0);
            return node.getTextContent().trim();
        }
        return "";
    }

    private static double[] getOffsetUsingOpenSlide(ImageServer<?> server) {
        double centerX = server.getWidth() / 2.0;
        double centerY = server.getHeight() / 2.0;
        double pixelWidthNm = server.getPixelCalibration().getPixelWidthMicrons() * 1000;
        double pixelHeightNm = server.getPixelCalibration().getPixelHeightMicrons() * 1000;

        URI uri = server.getURIs().iterator().next();
        Path path = GeneralTools.toPath(uri);
        if (path == null || !Files.exists(path))
            path = null;

        try (OpenSlide osr = path != null
                ? OpenSlideLoader.openImage(path.toRealPath().toString())
                : OpenSlideLoader.openImage(uri.toString())) {

            if (osr == null) {
                logger.warn("Could not open OpenSlide image: {}", uri);
                return new double[] { centerX, centerY };
            }

            Map<String, String> props = osr.getProperties();

            double xOffset = parseDoubleOrDefault(props.get("hamamatsu.XOffsetFromSlideCentre"));
            double yOffset = parseDoubleOrDefault(props.get("hamamatsu.YOffsetFromSlideCentre"));

            if (!Double.isNaN(xOffset))
                centerX -= xOffset / pixelWidthNm;
            if (!Double.isNaN(yOffset))
                centerY -= yOffset / pixelHeightNm;

        } catch (Exception e) {
            logger.error("Error reading offsets with OpenSlide", e);
        }

        return new double[] { centerX, centerY };
    }

    private static double parseDoubleOrDefault(String str) {
        try {
            return str != null ? Double.parseDouble(str) : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
    /**
     * Read NDPA file and add annotations to the current image
     *
     * @param imageData
     * @param annotationClass The path class to assign to annotations
     * @return true if successful, false otherwise
     */
    public static boolean readNDPA(ImageData<?> imageData, PathClass annotationClass) {
        ImageServer<?> server = imageData.getServer();
        if (server == null)
            return false;
            
        //check that the file extension is ndpi
        String uri = GeneralTools.toPath(server.getURIs().iterator().next()).toString();
        if (!uri.toLowerCase().endsWith(".ndpi")) {
            logger.error("File is not NDPI: "+uri);
            return false;
        }

        // We need the pixel size
        var cal = server.getPixelCalibration();
        if (!cal.hasPixelSizeMicrons()) {
            logger.error("No pixel information for this image!");
            return false;
        }

        double pixelWidthNm = cal.getPixelWidthMicrons() * 1000;
        double pixelHeightNm = cal.getPixelHeightMicrons() * 1000;
        
        //Aperio Image Scope displays images in a different orientation
        boolean rotated = false;

        // need to add annotations to hierarchy so qupath sees them
        var hierarchy = imageData.getHierarchy();
            
        //*********Get NDPA automatically based on naming scheme 
        File ndpaFile = new File(uri + ".ndpa");
        if (!ndpaFile.exists()) {
            logger.error("No NDPA file for this image...");
            return false;
        }

        //Get X Reference from OPENSLIDE data
        //The Open slide numbers are actually offset from IMAGE center (not physical slide center). 
        double[] offset = getOffsetUsingOpenSlide(server);
        double offsetFromTopLeftX = offset[0];
        double offsetFromTopLeftY = offset[1];

        logger.info("offset: {} {}", offsetFromTopLeftX, offsetFromTopLeftY);

        Function<Double, Double> transformX = x -> (x / pixelWidthNm) + offsetFromTopLeftX;
        Function<Double, Double> transformY = y -> (y / pixelHeightNm) + offsetFromTopLeftY;

        try {
            // Parse XML
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(ndpaFile);
            doc.getDocumentElement().normalize();
        
            NodeList ndpviewstates = doc.getDocumentElement().getChildNodes();
        
            for (int i = 0; i < ndpviewstates.getLength(); i++) {
                Node ndpviewstateNode = ndpviewstates.item(i);
                if (ndpviewstateNode.getNodeType() != Node.ELEMENT_NODE)
                    continue;
                
                Element ndpviewstate = (Element) ndpviewstateNode;
            
                String annotationName = getTextContent(ndpviewstate, "title");
                Element annotationElem = (Element) ndpviewstate.getElementsByTagName("annotation").item(0);
                String annotationType = annotationElem.getAttribute("type").toUpperCase();
                String annotationColor = annotationElem.getAttribute("color").toUpperCase();
                String details = getTextContent(ndpviewstate, "details");

                ROI roi = null;
                logger.info("elem: {} {} {} {}", annotationElem, annotationType, annotationName, annotationColor);

                if ("CIRCLE".equals(annotationType)) {
                    double x = Double.parseDouble(getTextContent(annotationElem,"x"));
                    double y = Double.parseDouble(getTextContent(annotationElem,"y"));

                    double radius = Double.parseDouble(getTextContent(annotationElem,"radius"));
                    double rx = radius / pixelWidthNm;
                    double ry = radius / pixelHeightNm;

                    roi = ROIs.createEllipseROI(transformX.apply(x) - rx, transformY.apply(y) - ry,
                        rx * 2, ry * 2, null);
                }

                if ("LINEARMEASURE".equals(annotationType)) {
                    double x1 = Double.parseDouble(getTextContent(annotationElem,"x1"));
                    double y1 = Double.parseDouble(getTextContent(annotationElem,"y1"));
                    double x2 = Double.parseDouble(getTextContent(annotationElem,"x2"));
                    double y2 = Double.parseDouble(getTextContent(annotationElem,"y2"));

                    roi = ROIs.createLineROI(transformX.apply(x1), transformY.apply(y1),
                            transformX.apply(x2), transformY.apply(y2), null);
                }

                if ("PIN".equals(annotationType)) {
                    double x = Double.parseDouble(getTextContent(annotationElem,"x"));
                    double y = Double.parseDouble(getTextContent(annotationElem,"y"));

                    roi = ROIs.createPointsROI(transformX.apply(x), transformY.apply(y), null);
                }

                if ("FREEHAND".equals(annotationType)) {
                    List<Point2> tmpPointsList = new ArrayList<>();

                    Element pointlistElem = (Element) annotationElem.getElementsByTagName("pointlist").item(0);
                    NodeList points = pointlistElem.getElementsByTagName("point");

                    for (int j = 0; j < points.getLength(); j++) {
                        Element pointElem = (Element) points.item(j);

                        double x = Double.parseDouble(getTextContent(pointElem, "x"));
                        double y = Double.parseDouble(getTextContent(pointElem, "y"));

                        if (rotated) {
                            y = server.getHeight() - y;
                        }

                        tmpPointsList.add(new Point2(transformX.apply(x), transformY.apply(y)));
                    }

                    boolean isRectangle = "rectangle".equalsIgnoreCase(annotationElem.getAttribute("specialtype"));
                    boolean isClosed = true; // Could also read from XML if needed

                    if (isRectangle) {
                        double x1 = tmpPointsList.get(0).getX();
                        double y1 = tmpPointsList.get(0).getY();
                        double x3 = tmpPointsList.get(2).getX();
                        double y3 = tmpPointsList.get(2).getY();
                        roi = ROIs.createRectangleROI(x1, y1, x3 - x1, y3 - y1, null);
                    } else if (isClosed) {
                        roi = ROIs.createPolygonROI(tmpPointsList, null);
                    } else {
                        roi = ROIs.createPolylineROI(tmpPointsList, null);
                    }
                }

                if (roi != null) {
                    PathAnnotationObject annotation = new PathAnnotationObject();
                    annotation.setROI(roi);
                    annotation.setName(annotationName);

                    if (annotationClass != null) {
                        annotation.setPathClass(annotationClass);
                    }

                    if (details != null && !details.isEmpty()) {
                        annotation.setDescription(details);
                    }

                    annotation.setLocked(true);
                    hierarchy.addObject(annotation);
                }
            }
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Extract point list from a geometry ring
     *
     * @param ring The geometry ring
     * @return List of points as [x,y] arrays
     */
    private static List<double[]> getPointList(org.locationtech.jts.geom.LineString ring) {
        List<double[]> pointlist = new ArrayList<>();
        var coords = ring.getCoordinates();
        for (int i = 0; i < coords.length - 1; i++) {
            pointlist.add(new double[] { coords[i].x, coords[i].y });
        }
        return pointlist;
    }

    private static File getBackupNdpaFile(File ndpaFile) {
        File backupFile = new File(ndpaFile.getAbsolutePath() + ".bak");
        int counter = 1;
        
        while (backupFile.exists()) {
            backupFile = new File(ndpaFile.getAbsolutePath() + ".bak." + counter);
            counter++;
        }
        
        return backupFile;
    }

    /**
     * Write QuPath annotations to an NDPA file
     *
     * @return true if successful, false otherwise
     */
    public static boolean writeNDPA(ImageData<?> imageData) {
        ImageServer<?> server = imageData.getServer();
        if (server == null)
            return false;

        // Check that the file extension is ndpi
        String uri = GeneralTools.toPath(server.getURIs().iterator().next()).toString();
        if (!uri.toLowerCase().endsWith(".ndpi")) {
            logger.error("File is not NDPI: "+uri);
            return false;
        }

        // Backup any existing ndpa file
        File ndpaFile = new File(uri + ".ndpa");
        if (ndpaFile.exists()) {
            File backup = getBackupNdpaFile(ndpaFile);
            logger.info("Creating backup: {}", backup.getAbsolutePath());
            try {
                Files.copy(ndpaFile.toPath(), backup.toPath());
            } catch (IOException e) {
                logger.error("Failed to create NDPA backup", e);
                return false;
            }
        }

        // We need the pixel size
        var cal = server.getPixelCalibration();
        if (!cal.hasPixelSizeMicrons()) {
            logger.error("No pixel information for this image!");
            return false;
        }

        double pixelWidthNm = cal.getPixelWidthMicrons() * 1000;
        double pixelHeightNm = cal.getPixelHeightMicrons() * 1000;
        
        double imageCenter_X = server.getWidth() / 2.0;
        double imageCenter_Y = server.getHeight() / 2.0;

        //Aperio Image Scope displays images in a different orientation
        boolean rotated = false;

        // need to add annotations to hierarchy so qupath sees them
        var hierarchy = imageData.getHierarchy();
            
        //Get X Reference from OPENSLIDE data
        //The Open slide numbers are actually offset from IMAGE center (not physical slide center). 
        double[] offset = getOffsetUsingOpenSlide(server);
        double offsetFromTopLeftX = offset[0];
        double offsetFromTopLeftY = offset[1];

        logger.info("offset: {} {}", offsetFromTopLeftX, offsetFromTopLeftY);

        // need to add annotations to hierarchy so qupath sees them
        List<PathObject> pathObjects = new ArrayList<>(hierarchy.getAnnotationObjects());

        //create a list of annotations
        List<Map<String, Object>> listAnnot = new ArrayList<>();
        int ndpIndex = 0;

        ToLongFunction<Double> transformX = x -> (long) ((x - offsetFromTopLeftX) * pixelWidthNm);
        ToLongFunction<Double> transformY = y -> (long) ((y - offsetFromTopLeftY) * pixelHeightNm);

        try {
            for (PathObject pathObject : pathObjects) {
                //We make a list of polygons, each has an exterior and interior rings
                var geometry = pathObject.getROI().getGeometry();

                //Here we do some processing to simplify the outlines and remove small holes
                geometry = TopologyPreservingSimplifier.simplify(geometry, 5.0);
                geometry = GeometryTools.refineAreas(geometry, 200, 200);
                List<org.locationtech.jts.geom.Polygon> polygons = PolygonExtracter.getPolygons(geometry);

                for (int p = 0; p < polygons.size(); p++) {
                    var polygon = polygons.get(p);

                    //here we create a list of rings, we'll need to treat the first one differently
                    List<org.locationtech.jts.geom.LineString> rings = new ArrayList<>();
                    rings.add(polygon.getExteriorRing());
                    
                    int nRings = polygon.getNumInteriorRing();
                    for (int i = 0; i < nRings; i++) {
                        var ring = polygon.getInteriorRingN(i);
                        rings.add(ring);
                    }

                    for (int index = 0; index < rings.size(); index++) {
                        var ring = rings.get(index);
                        Map<String, Object> annot = new HashMap<>();
                        
                        if (index == 0) {
                            annot.put("title", pathObject.getName());
                            annot.put("details", pathObject.getPathClass() != null ? 
                                               pathObject.getPathClass().toString() : "");
                            annot.put("color", "#" + Integer.toHexString(ColorToolsFX.getDisplayedColorARGB(pathObject)).substring(2));
                        } else {
                            annot.put("title", "clear");
                            annot.put("details", "clear");
                            annot.put("color", "#000000");
                        }

                        annot.put("id", ++ndpIndex);
                        annot.put("coordformat", "nanometers");
                        annot.put("lens", 0.445623);
                        annot.put("x", (int) imageCenter_X);
                        annot.put("y", (int) imageCenter_Y);
                        annot.put("z", 0);
                        annot.put("showtitle", 0);
                        annot.put("showhistogram", 0);
                        annot.put("showlineprofile", 0);
                        annot.put("type", "freehand");
                        annot.put("displayname", "AnnotateFreehand");
                        annot.put("measuretype", 0);
                        annot.put("closed", 1);

                        //add the point list
                        annot.put("pointlist", getPointList(ring));
                        listAnnot.add(annot);
                    }
                }
            }

            //make an XML string
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n");
            xml.append("<annotations>\n");

            for (Map<String, Object> annot : listAnnot) {
                xml.append("  <ndpviewstate id=\"").append(annot.get("id")).append("\">\n");
                xml.append("    <title>").append(annot.get("title")).append("</title>\n");
                xml.append("    <details>").append(annot.get("details")).append("</details>\n");
                xml.append("    <coordformat>").append(annot.get("coordformat")).append("</coordformat>\n");
                xml.append("    <lens>").append(annot.get("lens")).append("</lens>\n");
                xml.append("    <x>").append(annot.get("x")).append("</x>\n");
                xml.append("    <y>").append(annot.get("y")).append("</y>\n");
                xml.append("    <z>").append(annot.get("z")).append("</z>\n");
                xml.append("    <showtitle>").append(annot.get("showtitle")).append("</showtitle>\n");
                xml.append("    <showhistogram>").append(annot.get("showhistogram")).append("</showhistogram>\n");
                xml.append("    <showlineprofile>").append(annot.get("showlineprofile")).append("</showlineprofile>\n");

                xml.append("    <annotation type=\"").append(annot.get("type"))
                .append("\" displayname=\"").append(annot.get("displayname"))
                .append("\" color=\"").append(annot.get("color")).append("\">\n");

                xml.append("      <measuretype>").append(annot.get("measuretype")).append("</measuretype>\n");
                xml.append("      <closed>").append(annot.get("closed")).append("</closed>\n");
                xml.append("      <pointlist>\n");

                @SuppressWarnings("unchecked")
                List<double[]> pointlist = (List<double[]>) annot.get("pointlist");
                for (double[] pt : pointlist) {
                    xml.append("        <point>\n");
                    xml.append("          <x>").append(transformX.applyAsLong(pt[0])).append("</x>\n");
                    xml.append("          <y>").append(transformY.applyAsLong(pt[1])).append("</y>\n");
                    xml.append("        </point>\n");
                }

                xml.append("      </pointlist>\n");
                xml.append("    </annotation>\n");
                xml.append("  </ndpviewstate>\n");
            }

            xml.append("</annotations>\n");

            FileWriter fileWriter = new FileWriter(ndpaFile);
            fileWriter.write(xml.toString());
            fileWriter.close();

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}