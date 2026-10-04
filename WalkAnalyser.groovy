#!/usr/bin/env groovy
@Grab('info.picocli:picocli:4.7.5')
@Grab('com.garmin:fit:21.176.0')
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import com.garmin.fit.Decode
import com.garmin.fit.DeveloperField
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.TimeInZoneMesg
import com.garmin.fit.TimeInZoneMesgListener
import groovy.json.JsonSlurper
import groovy.transform.Field
import groovy.xml.XmlSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Command(
    name = 'WalkAnalyser',
    description = 'Analyses recorded walks (Apple Watch FIT files exported by HealthFit, with their GPX exports): ascent/descent, gradient bands, heart rate, cadence, energy and weather along the route.',
    mixinStandardHelpOptions = true,
    version = '1.0'
)
class Options {

    @Parameters(arity = '1..*', description = 'Recorded FIT files; a GPX export with the same name next to each supplies full-precision altitude')
    List<java.io.File> files

    @Option(names = ['-t', '--threshold'], description = 'Noise threshold for ascent/descent, in metres (default: 0.4, for full-precision barometric altitude; GPS-only altitude needs about 3)')
    double thresholdM = 0.4

    @Option(names = ['--no-terrain-start'], description = 'Don\'t correct the barometer\'s start error against the IGN MDT05 terrain model (Spain only)')
    boolean noTerrainStart = false

    @Option(names = ['--no-weather'], description = 'Don\'t fetch weather and felt heat along the route from the Open-Meteo Archive API')
    boolean noWeather = false

    @Option(names = ['--fit-altitude'], description = 'Use the FIT file\'s own altitudes (0.2 m steps) even when a GPX export sits next to it')
    boolean fitAltitude = false

    @Option(names = ['--maps'], description = 'Cache folder for terrain tiles and weather (default: the nearest existing maps/ folder next to the input file or one level up, otherwise maps/ next to the input file)')
    java.io.File mapsDir
}

// ----- shared classes, mirrored from ElevationProfiler.groovy for consistency -----

// IGN MDT05 digital terrain model (5 m cells, Spain only), fetched from the public WCS
// service in 0.02 degree tiles (about 2 km, ~1.4 MB each) and cached as files, since the
// terrain doesn't change. Tiles are requested as ESRI ASCII grid rather than GeoTIFF,
// because the GeoTIFF output is whole metres only. The model is bare ground: buildings,
// bridges and embankments are removed.
class TerrainModel {
    static final String WCS_URL_TEMPLATE = 'https://servicios.idee.es/wcs-inspire/mdt?SERVICE=WCS&REQUEST=GetCoverage' +
        '&VERSION=2.0.1&COVERAGEID=Elevacion4258_5&SUBSET=lat(%.4f,%.4f)&SUBSET=long(%.4f,%.4f)&FORMAT=application/asc'
    static final double TILE_DEG = 0.02

    File cacheDir
    int downloadedCount = 0
    private Map<String, Map> tiles = [:]

    TerrainModel(File cacheDir) {
        this.cacheDir = cacheDir
    }

    // Terrain height at a position by bilinear interpolation between cell centres, or NaN
    // where the model has no data (outside Spain, or open sea).
    double height(double lat, double lon) {
        long latIndex = (long) Math.floor(lat / TILE_DEG + 1e-9)
        long lonIndex = (long) Math.floor(lon / TILE_DEG + 1e-9)
        Map tile = tileAt(latIndex * TILE_DEG, lonIndex * TILE_DEG)
        double cell = tile.cellsize as double
        int nrows = tile.nrows as int
        int ncols = tile.ncols as int
        double[] grid = tile.grid as double[]
        // Row 0 is the northern edge.
        double top = (tile.yllcorner as double) + nrows * cell
        double x = (lon - (tile.xllcorner as double)) / cell - 0.5
        double y = (top - lat) / cell - 0.5
        int x0 = Math.min(Math.max((int) Math.floor(x), 0), ncols - 2)
        int y0 = Math.min(Math.max((int) Math.floor(y), 0), nrows - 2)
        double fx = x - x0
        double fy = y - y0
        double north = grid[y0 * ncols + x0] * (1 - fx) + grid[y0 * ncols + x0 + 1] * fx
        double south = grid[(y0 + 1) * ncols + x0] * (1 - fx) + grid[(y0 + 1) * ncols + x0 + 1] * fx
        north * (1 - fy) + south * fy
    }

    private Map tileAt(double lat0, double lon0) {
        String name = String.format(Locale.ROOT, 'mdt05_%.2f_%.2f.asc', lat0, lon0)
        if (tiles.containsKey(name)) {
            return tiles[name]
        }
        File file = new File(cacheDir, name)
        if (!file.exists()) {
            String url = String.format(Locale.ROOT, WCS_URL_TEMPLATE, lat0, lat0 + TILE_DEG, lon0, lon0 + TILE_DEG)
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(180)).GET().build()
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || !response.body().contains('ncols')) {
                throw new RuntimeException("terrain tile ${name} request failed with HTTP ${response.statusCode()}")
            }
            cacheDir.mkdirs()
            file.text = response.body()
            downloadedCount++
        }
        Map tile = parseAsciiGrid(file.text)
        tiles[name] = tile
        tile
    }

    // Parses an ESRI ASCII grid, skipping the multipart wrapper the WCS adds before it and the
    // further sections (.prj) after it - only nrows x ncols values are read.
    private static Map parseAsciiGrid(String text) {
        List<String> lines = text.substring(text.indexOf('ncols')).readLines()
        Map header = [:]
        int lineIndex = 0
        while (lineIndex < lines.size() && lines[lineIndex] && Character.isLetter(lines[lineIndex].charAt(0))) {
            String[] parts = lines[lineIndex].trim().split(/\s+/)
            header[parts[0].toLowerCase()] = parts[1] as double
            lineIndex++
        }
        int nrows = header.nrows as int
        int ncols = header.ncols as int
        Double nodata = header.nodata_value as Double
        double[] grid = new double[nrows * ncols]
        int n = 0
        for (int i = lineIndex; i < lines.size() && n < grid.length; i++) {
            for (String token : lines[i].trim().split(/\s+/)) {
                if (token && n < grid.length) {
                    double value = token as double
                    grid[n++] = (nodata != null && value == nodata) ? Double.NaN : value
                }
            }
        }
        [nrows: nrows, ncols: ncols, cellsize: header.cellsize, xllcorner: header.xllcorner, yllcorner: header.yllcorner, grid: grid]
    }
}

// Felt temperature as the UTCI (Universal Thermal Climate Index): a "feels like" temperature
// in degC combining air temperature, humidity, wind and radiation. Uses the published 6th-order
// polynomial approximation (Broede et al. 2012), with its 211 coefficients as implemented in
// pythermalcomfort (MIT licence, copyright (c) 2019 Federico Tartarini), stored here as
// [coefficient, power of air temperature, power of wind, power of (mean radiant - air)
// temperature, power of vapour pressure in kPa].
//
// The sun's radiant gain on a walker comes from the ASHRAE 55 SolarCal model, as in
// pythermalcomfort's solar_gain: standing, under open sky, fully exposed, averaged over the
// sun's direction relative to the body (0-180 degrees), with skin and clothing absorbing 70%
// of sunlight and the ground reflecting 25% (dry soil, rock and vegetation reflect 0.2-0.3).
class ThermalComfort {
    static final double[][] UTCI_TERMS = [
        [1.0, 1, 0, 0, 0],
        [0.607562052, 0, 0, 0, 0],
        [-0.0227712343, 1, 0, 0, 0],
        [0.0008064702490000001, 2, 0, 0, 0],
        [-0.00015427137200000002, 3, 0, 0, 0],
        [-3.24651735e-06, 4, 0, 0, 0],
        [7.32602852e-08, 5, 0, 0, 0],
        [1.3595907300000002e-09, 6, 0, 0, 0],
        [-2.2583652, 0, 1, 0, 0],
        [0.0880326035, 1, 1, 0, 0],
        [0.00216844454, 2, 1, 0, 0],
        [-1.53347087e-05, 3, 1, 0, 0],
        [-5.729837039999999e-07, 4, 1, 0, 0],
        [-2.55090145e-09, 5, 1, 0, 0],
        [-0.751269505, 0, 2, 0, 0],
        [-0.00408350271, 1, 2, 0, 0],
        [-5.2167067500000005e-05, 2, 2, 0, 0],
        [1.9454466699999997e-06, 3, 2, 0, 0],
        [1.1409953100000001e-08, 4, 2, 0, 0],
        [0.158137256, 0, 3, 0, 0],
        [-6.572631430000001e-05, 1, 3, 0, 0],
        [2.2269752399999997e-07, 2, 3, 0, 0],
        [-4.1611703100000005e-08, 3, 3, 0, 0],
        [-0.0127762753, 0, 4, 0, 0],
        [9.66891875e-06, 1, 4, 0, 0],
        [2.5278585200000004e-09, 2, 4, 0, 0],
        [0.00045630667200000004, 0, 5, 0, 0],
        [-1.7420254599999998e-07, 1, 5, 0, 0],
        [-5.91491269e-06, 0, 6, 0, 0],
        [0.398374029, 0, 0, 1, 0],
        [0.00018394531400000002, 1, 0, 1, 0],
        [-0.00017375451, 2, 0, 1, 0],
        [-7.607811589999999e-07, 3, 0, 1, 0],
        [3.77830287e-08, 4, 0, 1, 0],
        [5.430796730000001e-10, 5, 0, 1, 0],
        [-0.0200518269, 0, 1, 1, 0],
        [0.000892859837, 1, 1, 1, 0],
        [3.4543304799999997e-06, 2, 1, 1, 0],
        [-3.7792577399999997e-07, 3, 1, 1, 0],
        [-1.69699377e-09, 4, 1, 1, 0],
        [0.00016999241500000002, 0, 2, 1, 0],
        [-4.99204314e-05, 1, 2, 1, 0],
        [2.4741717799999996e-07, 2, 2, 1, 0],
        [1.07596466e-08, 3, 2, 1, 0],
        [8.492429320000001e-05, 0, 3, 1, 0],
        [1.35191328e-06, 1, 3, 1, 0],
        [-6.21531254e-09, 2, 3, 1, 0],
        [-4.99410301e-06, 0, 4, 1, 0],
        [-1.89489258e-08, 1, 4, 1, 0],
        [8.153001140000001e-08, 0, 5, 1, 0],
        [0.00075504309, 0, 0, 2, 0],
        [-5.650952150000001e-05, 1, 0, 2, 0],
        [-4.52166564e-07, 2, 0, 2, 0],
        [2.46688878e-08, 3, 0, 2, 0],
        [2.42674348e-10, 4, 0, 2, 0],
        [0.00015454725, 0, 1, 2, 0],
        [5.2411097e-06, 1, 1, 2, 0],
        [-8.75874982e-08, 2, 1, 2, 0],
        [-1.50743064e-09, 3, 1, 2, 0],
        [-1.56236307e-05, 0, 2, 2, 0],
        [-1.33895614e-07, 1, 2, 2, 0],
        [2.4970982400000004e-09, 2, 2, 2, 0],
        [6.51711721e-07, 0, 3, 2, 0],
        [1.94960053e-09, 1, 3, 2, 0],
        [-1.0036111299999999e-08, 0, 4, 2, 0],
        [-1.2120667300000002e-05, 0, 0, 3, 0],
        [-2.1820366e-07, 1, 0, 3, 0],
        [7.512694820000001e-09, 2, 0, 3, 0],
        [9.79063848e-11, 3, 0, 3, 0],
        [1.25006734e-06, 0, 1, 3, 0],
        [-1.8158473600000001e-09, 1, 1, 3, 0],
        [-3.5219767100000004e-10, 2, 1, 3, 0],
        [-3.3651463e-08, 0, 2, 3, 0],
        [1.3590835900000001e-10, 1, 2, 3, 0],
        [4.1703262e-10, 0, 3, 3, 0],
        [-1.3036902500000002e-09, 0, 0, 4, 0],
        [4.1390846100000003e-10, 1, 0, 4, 0],
        [9.22652254e-12, 2, 0, 4, 0],
        [-5.08220384e-09, 0, 1, 4, 0],
        [-2.2473096099999998e-11, 1, 1, 4, 0],
        [1.17139133e-10, 0, 2, 4, 0],
        [6.62154879e-10, 0, 0, 5, 0],
        [4.0386326e-13, 1, 0, 5, 0],
        [1.95087203e-12, 0, 1, 5, 0],
        [-4.73602469e-12, 0, 0, 6, 0],
        [5.12733497, 0, 0, 0, 1],
        [-0.312788561, 1, 0, 0, 1],
        [-0.0196701861, 2, 0, 0, 1],
        [0.0009996908700000001, 3, 0, 0, 1],
        [9.51738512e-06, 4, 0, 0, 1],
        [-4.66426341e-07, 5, 0, 0, 1],
        [0.548050612, 0, 1, 0, 1],
        [-0.00330552823, 1, 1, 0, 1],
        [-0.0016411944, 2, 1, 0, 1],
        [-5.16670694e-06, 3, 1, 0, 1],
        [9.526924319999999e-07, 4, 1, 0, 1],
        [-0.0429223622, 0, 2, 0, 1],
        [0.00500845667, 1, 2, 0, 1],
        [1.00601257e-06, 2, 2, 0, 1],
        [-1.81748644e-06, 3, 2, 0, 1],
        [-0.0012581350200000002, 0, 3, 0, 1],
        [-0.000179330391, 1, 3, 0, 1],
        [2.3499444099999997e-06, 2, 3, 0, 1],
        [0.00012973580800000001, 0, 4, 0, 1],
        [1.2906487e-06, 1, 4, 0, 1],
        [-2.28558686e-06, 0, 5, 0, 1],
        [-0.0369476348, 0, 0, 1, 1],
        [0.00162325322, 1, 0, 1, 1],
        [-3.1427968000000004e-05, 2, 0, 1, 1],
        [2.59835559e-06, 3, 0, 1, 1],
        [-4.77136523e-08, 4, 0, 1, 1],
        [0.0086420339, 0, 1, 1, 1],
        [-0.000687405181, 1, 1, 1, 1],
        [-9.138638719999999e-06, 2, 1, 1, 1],
        [5.15916806e-07, 3, 1, 1, 1],
        [-3.5921747600000004e-05, 0, 2, 1, 1],
        [3.2869651100000006e-05, 1, 2, 1, 1],
        [-7.10542454e-07, 2, 2, 1, 1],
        [-1.243823e-05, 0, 3, 1, 1],
        [-7.385844e-09, 1, 3, 1, 1],
        [2.2060929599999998e-07, 0, 4, 1, 1],
        [-0.0007324691800000001, 0, 0, 2, 1],
        [-1.8738196400000002e-05, 1, 0, 2, 1],
        [4.80925239e-06, 2, 0, 2, 1],
        [-8.7549204e-08, 3, 0, 2, 1],
        [2.7786293000000003e-05, 0, 1, 2, 1],
        [-5.06004592e-06, 1, 1, 2, 1],
        [1.14325367e-07, 2, 1, 2, 1],
        [2.53016723e-06, 0, 2, 2, 1],
        [-1.72857035e-08, 1, 2, 2, 1],
        [-3.9507939799999996e-08, 0, 3, 2, 1],
        [-3.59413173e-07, 0, 0, 3, 1],
        [7.043880459999999e-07, 1, 0, 3, 1],
        [-1.89309167e-08, 2, 0, 3, 1],
        [-4.797687309999999e-07, 0, 1, 3, 1],
        [7.96079978e-09, 1, 1, 3, 1],
        [1.6289705800000001e-09, 0, 2, 3, 1],
        [3.94367674e-08, 0, 0, 4, 1],
        [-1.18566247e-09, 1, 0, 4, 1],
        [3.3467804100000003e-10, 0, 1, 4, 1],
        [-1.15606447e-10, 0, 0, 5, 1],
        [-2.80626406, 0, 0, 0, 2],
        [0.548712484, 1, 0, 0, 2],
        [-0.0039942841, 2, 0, 0, 2],
        [-0.000954009191, 3, 0, 0, 2],
        [1.93090978e-05, 4, 0, 0, 2],
        [-0.308806365, 0, 1, 0, 2],
        [0.0116952364, 1, 1, 0, 2],
        [0.000495271903, 2, 1, 0, 2],
        [-1.90710882e-05, 3, 1, 0, 2],
        [0.00210787756, 0, 2, 0, 2],
        [-0.0006984457380000001, 1, 2, 0, 2],
        [2.30109073e-05, 2, 2, 0, 2],
        [0.00041785659, 0, 3, 0, 2],
        [-1.2704387100000003e-05, 1, 3, 0, 2],
        [-3.04620472e-06, 0, 4, 0, 2],
        [0.0514507424, 0, 0, 1, 2],
        [-0.00432510997, 1, 0, 1, 2],
        [8.99281156e-05, 2, 0, 1, 2],
        [-7.146639429999999e-07, 3, 0, 1, 2],
        [-0.000266016305, 0, 1, 1, 2],
        [0.000263789586, 1, 1, 1, 2],
        [-7.0119900299999996e-06, 2, 1, 1, 2],
        [-0.00010682330600000001, 0, 2, 1, 2],
        [3.61341136e-06, 1, 2, 1, 2],
        [2.29748967e-07, 0, 3, 1, 2],
        [0.000304788893, 0, 0, 2, 2],
        [-6.42070836e-05, 1, 0, 2, 2],
        [1.16257971e-06, 2, 0, 2, 2],
        [7.680233839999999e-06, 0, 1, 2, 2],
        [-5.47446896e-07, 1, 1, 2, 2],
        [-3.5993791e-08, 0, 2, 2, 2],
        [-4.36497725e-06, 0, 0, 3, 2],
        [1.6873796899999998e-07, 1, 0, 3, 2],
        [2.67489271e-08, 0, 1, 3, 2],
        [3.2392689700000003e-09, 0, 0, 4, 2],
        [-0.0353874123, 0, 0, 0, 3],
        [-0.22120119, 1, 0, 0, 3],
        [0.0155126038, 2, 0, 0, 3],
        [-0.000263917279, 3, 0, 0, 3],
        [0.0453433455, 0, 1, 0, 3],
        [-0.00432943862, 1, 1, 0, 3],
        [0.000145389826, 2, 1, 0, 3],
        [0.00021750861000000002, 0, 2, 0, 3],
        [-6.66724702e-05, 1, 2, 0, 3],
        [3.3321714e-05, 0, 3, 0, 3],
        [-0.00226921615, 0, 0, 1, 3],
        [0.000380261982, 1, 0, 1, 3],
        [-5.45314314e-09, 2, 0, 1, 3],
        [-0.0007963554480000001, 0, 1, 1, 3],
        [2.5345803400000005e-05, 1, 1, 1, 3],
        [-6.3122365800000004e-06, 0, 2, 1, 3],
        [0.000302122035, 0, 0, 2, 3],
        [-4.77403547e-06, 1, 0, 2, 3],
        [1.73825715e-06, 0, 1, 2, 3],
        [-4.09087898e-07, 0, 0, 3, 3],
        [0.614155345, 0, 0, 0, 4],
        [-0.0616755931, 1, 0, 0, 4],
        [0.00133374846, 2, 0, 0, 4],
        [0.00355375387, 0, 1, 0, 4],
        [-0.0005130278510000001, 1, 1, 0, 4],
        [0.00010244975700000002, 0, 2, 0, 4],
        [-0.00148526421, 0, 0, 1, 4],
        [-4.11469183e-05, 1, 0, 1, 4],
        [-6.80434415e-06, 0, 1, 1, 4],
        [-9.77675906e-06, 0, 0, 2, 4],
        [0.0882773108, 0, 0, 0, 5],
        [-0.00301859306, 1, 0, 0, 5],
        [0.00104452989, 0, 1, 0, 5],
        [0.000247090539, 0, 0, 1, 5],
        [0.00148348065, 0, 0, 0, 6]
    ] as double[][]

    // Projected-area factors for a standing person, rows SHARP 0-180 degrees in 15 degree
    // steps, columns solar altitude 0-90 degrees in 15 degree steps (ASHRAE 55 Table C2-1).
    static final double[][] PROJECTED_AREA_STANDING = [
        [0.35, 0.35, 0.314, 0.258, 0.206, 0.144, 0.082],
        [0.342, 0.342, 0.31, 0.252, 0.2, 0.14, 0.082],
        [0.33, 0.33, 0.3, 0.244, 0.19, 0.132, 0.082],
        [0.31, 0.31, 0.275, 0.228, 0.175, 0.124, 0.082],
        [0.283, 0.283, 0.251, 0.208, 0.16, 0.114, 0.082],
        [0.252, 0.252, 0.228, 0.188, 0.15, 0.108, 0.082],
        [0.23, 0.23, 0.214, 0.18, 0.148, 0.108, 0.082],
        [0.242, 0.242, 0.222, 0.18, 0.153, 0.112, 0.082],
        [0.274, 0.274, 0.245, 0.203, 0.165, 0.116, 0.082],
        [0.304, 0.304, 0.27, 0.22, 0.174, 0.121, 0.082],
        [0.328, 0.328, 0.29, 0.234, 0.183, 0.125, 0.082],
        [0.344, 0.344, 0.304, 0.244, 0.19, 0.128, 0.082],
        [0.347, 0.347, 0.308, 0.246, 0.191, 0.128, 0.082]
    ] as double[][]

    static final List<Double> SHARP_DEG = [0.0, 45.0, 90.0, 135.0, 180.0]
    static final double SKIN_ABSORPTIVITY = 0.7
    static final double GROUND_REFLECTANCE = 0.25
    // The UTCI polynomial is only valid for wind from 0.5 to 17 m/s at 10 m height.
    static final double MIN_WIND_M_S = 0.5
    // WMO definition of sunshine: direct normal irradiance of at least this, in W/m2.
    static final double SUNNY_DNI_W_M2 = 120.0

    // UTCI in degC, or NaN outside the approximation's valid input range.
    static double utci(double airTemp, double meanRadiantTemp, double wind10m, double relativeHumidity) {
        double v = Math.max(wind10m, MIN_WIND_M_S)
        double deltaTr = meanRadiantTemp - airTemp
        if (airTemp < -50.0 || airTemp > 50.0 || deltaTr < -30.0 || deltaTr > 70.0 || v > 17.0) {
            return Double.NaN
        }
        double pa = saturationVapourPressureHpa(airTemp) * (relativeHumidity / 100.0) / 10.0
        double sum = 0.0
        for (double[] term : UTCI_TERMS) {
            sum += term[0] * Math.pow(airTemp, term[1]) * Math.pow(v, term[2]) * Math.pow(deltaTr, term[3]) * Math.pow(pa, term[4])
        }
        sum
    }

    // Saturation vapour pressure over water in hPa (Hardy 1998), as the UTCI reference uses.
    static double saturationVapourPressureHpa(double airTemp) {
        double[] g = [-2836.5744, -6028.076559, 19.54263612, -0.02737830188, 0.000016261698, 7.0229056e-10, -1.8680009e-13] as double[]
        double tk = airTemp + 273.15
        double es = 2.7150305 * Math.log(tk)
        for (int i = 0; i < g.length; i++) {
            es += g[i] * Math.pow(tk, i - 2)
        }
        Math.exp(es) * 0.01
    }

    // Increase in mean radiant temperature, in degC, from direct normal irradiance on a
    // standing walker in full sun, averaged over SHARP_DEG.
    static double solarDeltaMrt(double solarAltitudeDeg, double dni) {
        if (solarAltitudeDeg <= 0.0 || dni <= 0.0) {
            return 0.0
        }
        double altitude = Math.min(solarAltitudeDeg, 90.0)
        double fEff = 0.725
        double radiativeCoefficient = 6.0
        double iDiff = 0.2 * dni
        double total = 0.0
        for (double sharp : SHARP_DEG) {
            double fp = projectedAreaFactor(altitude, sharp)
            double eDiff = fEff * 0.5 * iDiff
            double eDirect = fEff * fp * dni
            double eReflected = fEff * 0.5 * (dni * Math.sin(Math.toRadians(altitude)) + iDiff) * GROUND_REFLECTANCE
            double erf = (eDiff + eDirect + eReflected) * (SKIN_ABSORPTIVITY / 0.95)
            total += erf / (radiativeCoefficient * fEff)
        }
        total / SHARP_DEG.size()
    }

    // Bilinear interpolation in PROJECTED_AREA_STANDING.
    static double projectedAreaFactor(double altitudeDeg, double sharpDeg) {
        int altIndex = Math.min((int) Math.floor(altitudeDeg / 15.0), 5)
        int sharpIndex = Math.min((int) Math.floor(sharpDeg / 15.0), 11)
        double fa = (altitudeDeg - altIndex * 15.0) / 15.0
        double fs = (sharpDeg - sharpIndex * 15.0) / 15.0
        double[][] t = PROJECTED_AREA_STANDING
        double low = t[sharpIndex][altIndex] * (1 - fs) + t[sharpIndex + 1][altIndex] * fs
        double high = t[sharpIndex][altIndex + 1] * (1 - fs) + t[sharpIndex + 1][altIndex + 1] * fs
        low * (1 - fa) + high * fa
    }

    // The sun's height above the horizon in degrees at a UTC time (NOAA approximation,
    // within about 1 degree).
    static double solarAltitudeDeg(double lat, double lon, LocalDateTime utc) {
        double hours = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
        double g = 2 * Math.PI / 365 * (utc.dayOfYear - 1 + (hours - 12) / 24)
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) +
            0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g)
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) -
            0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g))
        double solarMinutes = hours * 60 + eqTime + 4 * lon
        double hourAngle = Math.toRadians(solarMinutes / 4 - 180)
        double la = Math.toRadians(lat)
        double sinAlt = Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(hourAngle)
        Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt))))
    }

    // UTCI heat-stress categories (the cold-stress ones don't arise on a summer walk and are
    // all reported as no heat stress).
    static String heatStressCategory(double utci) {
        if (utci > 46.0) {
            return 'extreme heat stress'
        }
        if (utci > 38.0) {
            return 'very strong heat stress'
        }
        if (utci > 32.0) {
            return 'strong heat stress'
        }
        if (utci > 26.0) {
            return 'moderate heat stress'
        }
        'no heat stress'
    }
}

// ----- recorded-walk analysis -----

// FIT positions are in semicircles: 2^31 semicircles = 180 degrees.
@Field final double SEMICIRCLE_TO_DEG = 180.0 / Math.pow(2, 31)
// Altitudes are stored in fixed steps (FIT: 0.2 m); allow for float rounding at the threshold,
// otherwise a 0.4 m step can compute as 0.39999 m and be skipped.
@Field final double EPSILON_M = 1e-6
@Field final List<Double> GRADIENT_BAND_EDGES_PCT = [5.0, 10.0, 15.0, 20.0, 30.0]
@Field final double GRADIENT_BASELINE_M = 25.0
// Moving means faster than this over a 20 s window - slower is standing, map-reading or
// photo stops rather than walking, even on the steepest climbs.
@Field final double MOVING_SPEED_M_S = 0.5
@Field final double MOVING_WINDOW_S = 20.0
// Heart rate trails a change in effort by roughly half a minute, so each stretch of track is
// paired with the heart rate this long after it when splitting by gradient.
@Field final int HR_LAG_S = 30
@Field final ZoneId LOCAL_ZONE = ZoneId.of('Europe/Madrid')

double haversine(double lat1, double lon1, double lat2, double lon2) {
    double earthRadiusM = 6371000.0
    double phi1 = Math.toRadians(lat1)
    double phi2 = Math.toRadians(lat2)
    double deltaPhi = Math.toRadians(lat2 - lat1)
    double deltaLambda = Math.toRadians(lon2 - lon1)
    double sinPhi = Math.sin(deltaPhi / 2)
    double sinLambda = Math.sin(deltaLambda / 2)
    double a = sinPhi * sinPhi + Math.cos(phi1) * Math.cos(phi2) * sinLambda * sinLambda
    2 * earthRadiusM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

// GPX altitudes by timestamp (epoch seconds). HealthFit's GPX export has the same positions
// and altitudes as the FIT, but at full precision rather than the FIT's 0.2 m steps, which
// inflate ascent by about 3% at a 0.4 m threshold.
Map<Long, Double> readGpxAltitudes(java.io.File gpxFile) {
    def gpx = new XmlSlurper(false, false).parse(gpxFile)
    Map<Long, Double> altitudes = [:]
    gpx.trk.trkseg.trkpt.each { trkpt ->
        String time = trkpt.time.text()
        String ele = trkpt.ele.text()
        if (time && ele) {
            altitudes[Instant.parse(time).epochSecond] = ele as double
        }
    }
    altitudes
}

// The GPX export with the same name as a FIT file. Apple Watch names contain a non-breaking
// space ("Apple\u00a0Watch") that a re-export or rename can turn into a normal one, so names
// are compared with all whitespace normalised.
java.io.File matchingGpx(java.io.File fitFile) {
    matchingSibling(fitFile, '.gpx')
}

java.io.File matchingSibling(java.io.File fitFile, String suffix) {
    Closure<String> stem = { String name -> name.replaceFirst(/(?i)\.(fit|gpx|notes\.txt)$/, '').replaceAll(/[\s\u00a0]+/, ' ') }
    String wanted = stem(fitFile.name)
    fitFile.absoluteFile.parentFile.listFiles()?.find { it.name.toLowerCase().endsWith(suffix) && stem(it.name) == wanted }
}

// A short note on how the walk felt, kept as <walk>.notes.txt next to the FIT file. Lines
// starting with # are comments; a comment containing "draft" marks the note as an unchecked
// best guess (e.g. drafted from the recorded data), shown as such.
Map readWalkNotes(java.io.File fitFile) {
    java.io.File notesFile = matchingSibling(fitFile, '.notes.txt')
    if (!notesFile) {
        return null
    }
    List<String> lines = notesFile.readLines('UTF-8')
    String text = lines.findAll { !it.trim().startsWith('#') }.join(' ').replaceAll(/\s+/, ' ').trim()
    text ? [text: text, draft: lines.any { it.trim().startsWith('#') && it.toLowerCase().contains('draft') }] : null
}

// Loads the FIT file's per-second records, session totals and heart-rate zones. Records keep
// lat/lon in degrees, the watch's own cumulative distance, altitude, heart rate and cadence.
Map loadFit(java.io.File fitFile, boolean useGpxAltitude) {
    List<Map> records = []
    Map session = [:]
    Map zones = [:]
    Decode decode = new Decode()
    MesgBroadcaster broadcaster = new MesgBroadcaster(decode)
    broadcaster.addListener({ RecordMesg m ->
        Float altitude = m.enhancedAltitude ?: m.altitude
        records << [
            epochSecond: m.timestamp.date.time.intdiv(1000) as long,
            lat: m.positionLat != null ? m.positionLat * SEMICIRCLE_TO_DEG : null,
            lon: m.positionLong != null ? m.positionLong * SEMICIRCLE_TO_DEG : null,
            distance: m.distance as Double,
            altitude: altitude as Double,
            heartRate: m.heartRate as Integer,
            // Walking cadence is stored as cycles (stride pairs) per minute: one cycle = two steps.
            cadenceSpm: m.cadence != null ? (m.cadence + (m.fractionalCadence ?: 0.0f)) * 2.0 : null
        ]
    } as RecordMesgListener)
    broadcaster.addListener({ SessionMesg m ->
        Map<String, Object> developer = [:]
        for (DeveloperField field : m.developerFields) {
            developer[field.name] = field.value
        }
        session = [
            startEpochSecond: m.startTime?.date?.time?.intdiv(1000),
            totalDistanceM: m.totalDistance,
            timerTimeS: m.totalTimerTime,
            elapsedTimeS: m.totalElapsedTime,
            totalAscentM: m.totalAscent,
            totalDescentM: m.totalDescent,
            totalCalories: m.totalCalories,
            totalCycles: m.totalCycles,
            avgHeartRate: m.avgHeartRate,
            maxHeartRate: m.maxHeartRate,
            minHeartRate: m.minHeartRate,
            minAltitudeM: m.enhancedMinAltitude,
            maxAltitudeM: m.enhancedMaxAltitude,
            trainingLoad: m.trainingLoadPeak,
            // Workout RPE is stored x10 (60 = 6/10 on the Borg CR10 scale).
            rpe: m.workoutRpe != null ? m.workoutRpe / 10.0 : null,
            rpeEstimated: (developer['WORKOUT RPE ESTIMATED'] as Integer) == 1,
            // Apple's average METs are stored x100.
            avgMets: developer['AVG METs'] != null ? (developer['AVG METs'] as double) / 100.0 : null,
            // Apple's workout weather (temperature, and humidity in hundredths of a percent):
            // one value per walk, the weather at the start rather than an average.
            weatherTemp: m.avgTemperature,
            weatherHumidity: developer['SESSION WEATHER HUMIDITY'] != null ? (developer['SESSION WEATHER HUMIDITY'] as double) / 100.0 : null
        ]
    } as SessionMesgListener)
    broadcaster.addListener({ TimeInZoneMesg m ->
        if (m.hrZoneHighBoundary) {
            zones = [
                highBoundaries: m.hrZoneHighBoundary.toList(),
                timeS: m.timeInHrZone?.toList(),
                maxHeartRate: m.maxHeartRate,
                restingHeartRate: m.restingHeartRate
            ]
        }
    } as TimeInZoneMesgListener)
    fitFile.withInputStream { decode.read(it, broadcaster, broadcaster) }

    String altitudeSource = 'FIT (0.2 m steps)'
    java.io.File gpxFile = matchingGpx(fitFile)
    if (useGpxAltitude && gpxFile) {
        Map<Long, Double> gpxAltitudes = readGpxAltitudes(gpxFile)
        int matched = 0
        records.each { r ->
            Double gpxAltitude = gpxAltitudes[r.epochSecond as long]
            if (gpxAltitude != null) {
                r.altitude = gpxAltitude
                matched++
            }
        }
        if (matched > 0) {
            altitudeSource = "GPX export (${matched} of ${records.count { it.altitude != null }} records), FIT distance"
        }
    }
    // Records before the first distance reading are at the start.
    double lastDistance = 0.0
    records.each { r ->
        lastDistance = r.distance != null ? r.distance as double : lastDistance
        r.distance = lastDistance
    }
    [records: records.findAll { it.altitude != null }, session: session, zones: zones, altitudeSource: altitudeSource]
}

// Yields [fromIndex, toIndex, change] for each change the hysteresis filter counts: a change
// counts only once the altitude has moved at least the threshold from the last counted point.
List<List> hysteresisSteps(List<Double> altitudes, double thresholdM) {
    List<List> steps = []
    int ref = 0
    for (int i = 1; i < altitudes.size(); i++) {
        double diff = altitudes[i] - altitudes[ref]
        if (Math.abs(diff) >= thresholdM - EPSILON_M) {
            steps << [ref, i, diff]
            ref = i
        }
    }
    steps
}

// Altitude at a distance along the walk, interpolated in a profile with strictly increasing
// distances.
double profileAltitudeAt(List<Double> distances, List<Double> altitudes, double d) {
    if (d <= distances[0]) {
        return altitudes[0]
    }
    if (d >= distances[-1]) {
        return altitudes[-1]
    }
    int lo = Collections.binarySearch(distances, d)
    if (lo >= 0) {
        return altitudes[lo]
    }
    int hi = -lo - 1
    lo = hi - 1
    double f = (d - distances[lo]) / (distances[hi] - distances[lo])
    altitudes[lo] + (altitudes[hi] - altitudes[lo]) * f
}

// A profile with strictly increasing distance, keeping the last reading at each distance, so
// standing still doesn't create zero-length steps.
Map distanceProfile(List<Double> distances, List<Double> altitudes) {
    List<Double> d = []
    List<Double> a = []
    double runningMax = -1.0
    for (int i = 0; i < distances.size(); i++) {
        double di = Math.max(runningMax, distances[i])
        runningMax = di
        if (d && di == d[-1]) {
            a[-1] = altitudes[i]
        } else {
            d << di
            a << altitudes[i]
        }
    }
    [distances: d, altitudes: a]
}

// Gradient in percent over at least GRADIENT_BASELINE_M centred on a stretch of the walk, so
// short steps between 1 Hz readings don't come out exaggeratedly steep.
double baselineGradePct(Map profile, double d0, double d1) {
    List<Double> pd = profile.distances as List<Double>
    List<Double> pa = profile.altitudes as List<Double>
    if (d1 - d0 < GRADIENT_BASELINE_M) {
        double mid = (d0 + d1) / 2.0
        d0 = mid - GRADIENT_BASELINE_M / 2.0
        d1 = mid + GRADIENT_BASELINE_M / 2.0
    }
    d0 = Math.max(d0, pd[0])
    d1 = Math.min(d1, pd[-1])
    d1 > d0 ? (profileAltitudeAt(pd, pa, d1) - profileAltitudeAt(pd, pa, d0)) / (d1 - d0) * 100.0 : 0.0
}

int gradientBandIndex(double gradePct) {
    int index = 0
    while (index < GRADIENT_BAND_EDGES_PCT.size() && Math.abs(gradePct) >= GRADIENT_BAND_EDGES_PCT[index]) {
        index++
    }
    index
}

List<String> gradientBandLabels() {
    List<Double> edges = [0.0] + GRADIENT_BAND_EDGES_PCT
    List<String> labels = []
    for (int i = 1; i < edges.size(); i++) {
        labels << String.format(Locale.ROOT, '%.0f-%.0f%%', edges[i - 1], edges[i])
    }
    labels << String.format(Locale.ROOT, '>%.0f%%', edges[-1])
    labels
}

// Ascent and descent split by gradient band; each counted change is classed by the gradient
// over at least GRADIENT_BASELINE_M around it, so the bands add up exactly to the totals.
List<double[]> gradientBands(List<Double> altitudes, List<Double> distances, double thresholdM) {
    Map profile = distanceProfile(distances, altitudes)
    List<double[]> bands = gradientBandLabels().collect { new double[2] }
    hysteresisSteps(altitudes, thresholdM).each { step ->
        double change = step[2] as double
        double grade = baselineGradePct(profile, distances[step[0] as int], distances[step[1] as int])
        bands[gradientBandIndex(grade)][change > 0 ? 0 : 1] += Math.abs(change)
    }
    bands
}

// Median ignoring NaN (missing terrain), averaging the two middle values of an even count.
double median(List<Double> values) {
    List<Double> sorted = values.findAll { !Double.isNaN(it) }.sort(false)
    int n = sorted.size()
    if (n == 0) {
        return Double.NaN
    }
    n % 2 == 1 ? sorted[n.intdiv(2)] : (sorted[n.intdiv(2) - 1] + sorted[n.intdiv(2)]) / 2.0
}

List<Double> rollingMedian(List<Double> values, int half) {
    int n = values.size()
    List<Double> result = new ArrayList<>(n)
    for (int i = 0; i < n; i++) {
        result << median(values.subList(Math.max(0, i - half), Math.min(n, i + half + 1)))
    }
    result
}

// The watch's barometer can read wrong for the first seconds or minutes of a walk (up to about
// +-13 m on the GR92 walks). This finds that error against the IGN MDT05 terrain model and the
// point where the watch settles, mirroring the separate GR92 actual analyser's tested rules:
// - the watch and the model differ by a steady amount (geoid/pressure reference), measured
//   beyond the first 2 km; only the extra error at the start is corrected;
// - settled means within 2 m of terrain plus that offset (the offset wobbles by 1-1.5 m), and
//   the first agreement ends the correction, since on steep ground the terrain lookup is noisy
//   itself and replacing further brings in false climbs;
// - nothing is corrected when the start is already within 2 m, or the watch doesn't settle
//   within 2 km (a slow drift rather than a start error).
// Returns null when there's nothing to correct.
Map estimateStartError(List<Map> records, TerrainModel terrain) {
    double settledFromM = 2000.0
    double settledWithinM = 2.0
    List<Map> positioned = records.findAll { it.lat != null && it.lon != null }
    if (!positioned || (positioned[-1].distance as double) <= settledFromM) {
        return null
    }
    // One point per 5 m of distance (the model's cell size), so pauses and GPS jitter while
    // standing still don't add climbs.
    List<Map> points = []
    double nextDistance = 0.0
    for (Map r : positioned) {
        if ((r.distance as double) >= nextDistance) {
            points << r
            nextDistance = (r.distance as double) + 5.0
        }
    }
    List<Double> dist = points.collect { it.distance as double }
    List<Double> dem = rollingMedian(points.collect { terrain.height(it.lat as double, it.lon as double) }, 2)
    List<Double> watch = points.collect { it.altitude as double }
    List<Double> offsets = []
    for (int i = 0; i < points.size(); i++) {
        if (dist[i] >= settledFromM) {
            offsets << watch[i] - dem[i]
        }
    }
    double settledOffset = median(offsets)
    if (Double.isNaN(settledOffset) || Double.isNaN(dem[0])) {
        return null
    }
    List<Double> reference = dem.collect { it + settledOffset }
    List<Double> error = rollingMedian((0..<points.size()).collect { watch[it] - reference[it] }, 2)
    double startError = (positioned[0].altitude as double) - reference[0]
    if (Math.abs(startError) < settledWithinM) {
        return null
    }
    // Start from the second point: the error is often gone within the first few metres.
    for (int i = 0; i < points.size(); i++) {
        if (dist[i] > 0 && dist[i] < settledFromM && !Double.isNaN(error[i]) && Math.abs(error[i]) < settledWithinM) {
            return [startError: startError, settleM: dist[i], distances: dist, reference: reference]
        }
    }
    null
}

// Replaces altitudes before the settle point with terrain plus the watch's usual offset,
// indexed by distance walked, blending back into the watch's own readings over the last 50 m.
void correctStart(List<Map> records, Map correction) {
    double blend = Math.min(50.0, correction.settleM as double)
    double blendFrom = (correction.settleM as double) - blend
    Map profile = [distances: correction.distances, altitudes: correction.reference]
    records.each { r ->
        double d = r.distance as double
        if (d >= (correction.settleM as double)) {
            return
        }
        double ref = profileAltitudeAt(profile.distances as List<Double>, profile.altitudes as List<Double>, d)
        if (Double.isNaN(ref)) {
            return
        }
        if (d <= blendFrom) {
            r.altitude = ref
        } else {
            double k = (d - blendFrom) / blend
            r.altitude = ref * (1 - k) + (r.altitude as double) * k
        }
    }
}

// Marks each record as moving or not (speed over MOVING_WINDOW_S above MOVING_SPEED_M_S, and no
// recording gap over 10 s), and stores the seconds since the previous record.
void markMoving(List<Map> records) {
    int a = 0
    records[0].moving = false
    records[0].dtS = 0.0d
    for (int i = 1; i < records.size(); i++) {
        long t = records[i].epochSecond as long
        while (t - (records[a].epochSecond as long) > MOVING_WINDOW_S) {
            a++
        }
        long span = t - (records[a].epochSecond as long)
        double speed = span > 0 ? ((records[i].distance as double) - (records[a].distance as double)) / span : 0.0
        double dt = t - (records[i - 1].epochSecond as long)
        records[i].dtS = dt
        records[i].moving = dt <= 10.0 && speed > MOVING_SPEED_M_S
    }
}

// Heart rate this many seconds after a record (or the nearest one before it), for HR_LAG_S.
Integer heartRateAfter(List<Map> records, int index, int lagS) {
    long target = (records[index].epochSecond as long) + lagS
    int j = index
    while (j + 1 < records.size() && (records[j + 1].epochSecond as long) <= target) {
        j++
    }
    records[j].heartRate as Integer
}

// Heart rate by gradient while moving: for every band (climbs and descents separately), the
// distance, mean heart rate and net heartbeats above resting per kilometre. Net heartbeats
// track energy expenditure far better than time does - pace on a climb drops much less than
// its metabolic cost rises - so the per-kilometre figure, relative to near-flat walking, is a
// personal check on how much a gradient really costs.
Map heartRateByGradient(List<Map> records, int restingHr) {
    Map profile = distanceProfile(records.collect { it.distance as double }, records.collect { it.altitude as double })
    List<String> labels = gradientBandLabels()
    // Index 0..5 descents (0-5% ... >30%), 6..11 climbs.
    List<double[]> acc = (0..<(labels.size() * 2)).collect { new double[3] }  // distance m, seconds, net beats
    List<double[]> hrSum = (0..<(labels.size() * 2)).collect { new double[1] }
    for (int i = 1; i < records.size(); i++) {
        Map r = records[i]
        if (!r.moving) {
            continue
        }
        Integer hr = heartRateAfter(records, i, HR_LAG_S)
        if (hr == null) {
            continue
        }
        double d0 = records[i - 1].distance as double
        double d1 = r.distance as double
        double grade = baselineGradePct(profile, d0, d1)
        int band = gradientBandIndex(grade) + (grade >= 0 ? labels.size() : 0)
        double dt = r.dtS as double
        acc[band][0] += d1 - d0
        acc[band][1] += dt
        acc[band][2] += Math.max(0, hr - restingHr) * dt / 60.0
        hrSum[band][0] += hr * dt
    }
    List<Map> rows = []
    for (int k = 0; k < labels.size() * 2; k++) {
        boolean climb = k >= labels.size()
        String label = (climb ? '+' : '-') + labels[k % labels.size()]
        double km = acc[k][0] / 1000.0
        rows << [label: label, climb: climb, bandIndex: k % labels.size(), km: km, minutes: acc[k][1] / 60.0,
                 meanHr: acc[k][1] > 0 ? hrSum[k][0] / acc[k][1] : Double.NaN,
                 beatsPerKm: km > 0.05 ? acc[k][2] / km : Double.NaN]
    }
    // Reference: near-flat walking, the 0-5% climb and descent bands together.
    List<Map> flat = rows.findAll { it.bandIndex == 0 }
    double flatKm = flat.sum { it.km } as double
    double flatBeats = flat.sum { Double.isNaN(it.beatsPerKm as double) ? 0.0 : (it.beatsPerKm as double) * (it.km as double) } as double
    double flatBeatsPerKm = flatKm > 0 ? flatBeats / flatKm : Double.NaN
    rows.each { it.relative = (it.beatsPerKm as double) / flatBeatsPerKm }
    [rows: rows.findAll { (it.km as double) > 0.05 }, flatBeatsPerKm: flatBeatsPerKm]
}

// Minetti et al. (2002) metabolic cost of walking per metre as a function of gradient, relative
// to flat ground, for comparison with the heart-rate figures.
double minettiWalkingRelative(double gradeFraction) {
    double i = gradeFraction
    (280.5 * Math.pow(i, 5) - 58.7 * Math.pow(i, 4) - 76.8 * Math.pow(i, 3) + 51.9 * Math.pow(i, 2) + 19.6 * i + 2.5) / 2.5
}

java.io.File resolveMapsDir(java.io.File input, java.io.File explicit) {
    if (explicit) {
        return explicit
    }
    java.io.File here = new java.io.File(input.absoluteFile.parentFile, 'maps')
    java.io.File up = new java.io.File(input.absoluteFile.parentFile.parentFile, 'maps')
    here.isDirectory() ? here : (up.isDirectory() ? up : here)
}

String fetchUrl(String url) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(120)).GET().build()
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        throw new RuntimeException("HTTP ${response.statusCode()}: ${response.body()?.take(200)}")
    }
    response.body()
}

// Weather along the recorded walk: one sample every 15 minutes, at the position and altitude
// the walker actually had then, all in one Open-Meteo Archive request (comma-separated
// lists, each with its own elevation so temperature is adjusted for height), interpolated
// between the hourly readings. Cached under maps/ only once the walk is a week old, since
// more recent archive data can still be revised.
List<Map> weatherAlongWalk(List<Map> records, java.io.File cacheFile) {
    List<Map> samples = []
    long nextT = Long.MIN_VALUE
    records.each { r ->
        if (r.lat != null && (r.epochSecond as long) >= nextT) {
            samples << r
            nextT = (r.epochSecond as long) + 15 * 60
        }
    }
    Map last = records.findAll { it.lat != null }[-1]
    if (samples[-1] != last) {
        samples << last
    }
    LocalDate firstDay = Instant.ofEpochSecond(samples[0].epochSecond as long).atZone(ZoneOffset.UTC).toLocalDate()
    LocalDate lastDay = Instant.ofEpochSecond(samples[-1].epochSecond as long).atZone(ZoneOffset.UTC).toLocalDate()
    String url = 'https://archive-api.open-meteo.com/v1/archive?' +
        'latitude=' + samples.collect { String.format(Locale.ROOT, '%.5f', it.lat as double) }.join(',') +
        '&longitude=' + samples.collect { String.format(Locale.ROOT, '%.5f', it.lon as double) }.join(',') +
        '&elevation=' + samples.collect { String.format(Locale.ROOT, '%.0f', it.altitude as double) }.join(',') +
        "&start_date=${firstDay}&end_date=${lastDay}" +
        '&hourly=temperature_2m,relative_humidity_2m,direct_radiation,direct_normal_irradiance,wind_speed_10m' +
        '&wind_speed_unit=ms&timezone=GMT'
    Object data = null
    if (cacheFile.exists()) {
        Map cached = new JsonSlurper().parse(cacheFile) as Map
        if (cached.url == url) {
            data = cached.response
        }
    }
    if (data == null) {
        String raw = fetchUrl(url)
        data = new JsonSlurper().parseText(raw)
        if (!lastDay.isAfter(LocalDate.now().minusDays(7))) {
            cacheFile.parentFile.mkdirs()
            cacheFile.text = groovy.json.JsonOutput.toJson([url: url, response: data])
        }
    }
    List locations = data instanceof List ? data as List : [data]
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    List<Map> result = []
    for (int k = 0; k < samples.size(); k++) {
        Map hourly = (locations[k] as Map).hourly as Map
        List<Long> times = (hourly.time as List<String>).collect { LocalDateTime.parse(it, fmt).toEpochSecond(ZoneOffset.UTC) }
        long t = samples[k].epochSecond as long
        int idx = times.findIndexOf { it >= t }
        Closure<Double> valueOf = { String name ->
            List values = hourly[name] as List
            if (idx <= 0) {
                return values[Math.max(idx, 0)] as Double
            }
            Double a = values[idx - 1] as Double
            Double b = values[idx] as Double
            if (a == null || b == null) {
                return a ?: b
            }
            a + (b - a) * (t - times[idx - 1]) / (double) (times[idx] - times[idx - 1])
        }
        result << [epochSecond: t, lat: samples[k].lat, lon: samples[k].lon, temperature: valueOf('temperature_2m'),
                   humidity: valueOf('relative_humidity_2m'), radiation: valueOf('direct_radiation'),
                   dni: valueOf('direct_normal_irradiance'), wind: valueOf('wind_speed_10m')]
    }
    result
}

String clock(long epochSecond, ZoneId zone) {
    Instant.ofEpochSecond(epochSecond).atZone(zone).format(DateTimeFormatter.ofPattern('HH:mm'))
}

String formatHours(double hours) {
    int totalMinutes = Math.round(hours * 60.0) as int
    String.format(Locale.ROOT, '%dh %02dmin', totalMinutes.intdiv(60), totalMinutes % 60)
}

Map analyse(java.io.File file, Options options) {
    Map loaded = loadFit(file, !options.fitAltitude)
    List<Map> records = loaded.records as List<Map>
    Map session = loaded.session as Map
    Map zones = loaded.zones as Map
    java.io.File mapsDir = resolveMapsDir(file, options.mapsDir)
    String baseName = file.name.replaceFirst(/(?i)\.fit$/, '')
    Map summary = [name: file.name, date: Instant.ofEpochSecond(records[0].epochSecond as long).atZone(LOCAL_ZONE).toLocalDate()]

    println ''
    println "=== ${file.name} ==="
    println "Altitude from  : ${loaded.altitudeSource}"
    long start = records[0].epochSecond as long
    long finish = records[-1].epochSecond as long
    println String.format(Locale.ROOT, 'Walk           : %s, %s-%s local time', summary.date, clock(start, LOCAL_ZONE), clock(finish, LOCAL_ZONE))
    Map notes = readWalkNotes(file)
    if (notes) {
        println "How it felt${notes.draft ? ' (draft, unchecked)' : ''}: ${notes.text}"
    }

    Map correction = null
    if (!options.noTerrainStart) {
        try {
            correction = estimateStartError(records, new TerrainModel(new java.io.File(mapsDir, 'mdt05')))
            if (correction) {
                correctStart(records, correction)
                println String.format(Locale.ROOT, 'Start corrected: watch read %+.0f m against terrain, settling over %.0f m', correction.startError as double, correction.settleM as double)
            } else {
                println 'Start checked against terrain: no settling error found, not corrected'
            }
        } catch (Exception ex) {
            System.err.println("IGN MDT05 terrain model unavailable (${ex.message}); start not corrected.")
        }
    }

    markMoving(records)
    double movingHours = (records.findAll { it.moving }.sum { it.dtS as double } ?: 0.0) / 3600.0
    double distanceKm = (session.totalDistanceM ?: records[-1].distance) / 1000.0
    double elapsedHours = (finish - start) / 3600.0
    println String.format(Locale.ROOT, 'Distance       : %.2f km (watch)', distanceKm)
    println String.format(Locale.ROOT, 'Time           : %s elapsed, %s moving (%.2f km/h), %s stopped',
        formatHours(elapsedHours), formatHours(movingHours), distanceKm / movingHours, formatHours(elapsedHours - movingHours))
    summary.km = distanceKm
    summary.movingHours = movingHours
    summary.elapsedHours = elapsedHours

    List<Double> altitudes = records.collect { it.altitude as double }
    List<Double> distances = records.collect { it.distance as double }
    double ascent = 0.0
    double descent = 0.0
    hysteresisSteps(altitudes, options.thresholdM).each { step -> if ((step[2] as double) > 0) { ascent += step[2] as double } else { descent -= step[2] as double } }
    String watchTotal = session.totalAscentM != null ? " (watch's own total ${session.totalAscentM} m)" : ''
    println String.format(Locale.ROOT, 'Ascent/descent : %.0f m / %.0f m at %.1f m threshold%s', ascent, descent, options.thresholdM, watchTotal)
    println String.format(Locale.ROOT, 'Altitude range : %.0f-%.0f m', altitudes.min(), altitudes.max())
    List<double[]> bands = gradientBands(altitudes, distances, options.thresholdM)
    println 'Gradient band    ascent      descent'
    gradientBandLabels().eachWithIndex { String label, int i ->
        println String.format(Locale.ROOT, '  %-8s %6.0f m %3.0f%%  %6.0f m %3.0f%%', label,
            bands[i][0], ascent > 0 ? bands[i][0] / ascent * 100.0 : 0.0, bands[i][1], descent > 0 ? bands[i][1] / descent * 100.0 : 0.0)
    }
    int steepFrom = gradientBandIndex(15.0)
    double steepUp = bands.drop(steepFrom).sum { it[0] } as double
    double steepDown = bands.drop(steepFrom).sum { it[1] } as double
    println String.format(Locale.ROOT, 'Steeper than 15%%: %.0f m up, %.0f m down', steepUp, steepDown)
    summary.ascent = ascent
    summary.descent = descent
    summary.steepUp = steepUp
    summary.steepDown = steepDown

    // Heart rate. Resting and maximum come from the watch's own heart-rate zone settings.
    List<Map> withHr = records.findAll { it.heartRate != null }
    if (withHr) {
        int restingHr = (zones.restingHeartRate ?: 60) as int
        int maxHr = (zones.maxHeartRate ?: 220) as int
        double movingSeconds = 0.0
        double hrSeconds = 0.0
        double netBeats = 0.0
        records.each { r ->
            if (r.moving && r.heartRate != null) {
                double dt = r.dtS as double
                movingSeconds += dt
                hrSeconds += (r.heartRate as int) * dt
                netBeats += Math.max(0, (r.heartRate as int) - restingHr) * dt / 60.0
            }
        }
        double meanMovingHr = movingSeconds > 0 ? hrSeconds / movingSeconds : Double.NaN
        double reservePct = (meanMovingHr - restingHr) / (maxHr - restingHr) * 100.0
        println String.format(Locale.ROOT, 'Heart rate     : %s avg / %s max / %s min (watch); %.0f avg while moving, %.0f%% of heart-rate reserve (resting %d, max %d)',
            session.avgHeartRate, session.maxHeartRate, session.minHeartRate, meanMovingHr, reservePct, restingHr, maxHr)
        println String.format(Locale.ROOT, 'Net heartbeats : %.0f above resting while moving (%.0f per km, %.0f per moving minute)',
            netBeats, netBeats / distanceKm, netBeats / (movingSeconds / 60.0))
        summary.meanHr = meanMovingHr
        summary.netBeats = netBeats
        if (zones.highBoundaries && zones.timeS) {
            List<Integer> highs = zones.highBoundaries as List<Integer>
            List<Float> times = zones.timeS as List<Float>
            double total = times.sum { (it ?: 0.0f) as double } as double
            List<String> parts = []
            for (int z = 0; z < times.size(); z++) {
                String range = z == 0 ? "<${highs[0]}" : (z < highs.size() ? "${highs[z - 1]}-${highs[z]}" : ">${highs[-1]}")
                parts << String.format(Locale.ROOT, 'Z%d %s %.0f%%', z, range, total > 0 ? ((times[z] ?: 0.0f) as double) / total * 100.0 : 0.0)
            }
            println "Heart-rate zones (watch): ${parts.join(' | ')}"
        }
        Map byGradient = heartRateByGradient(records, restingHr)
        println String.format(Locale.ROOT, 'Heart rate by gradient while moving (heart rate %d s later; net beats per km relative to 0-5%%, flat = %.0f beats/km):', HR_LAG_S, byGradient.flatBeatsPerKm as double)
        println '  Gradient     km   min   mean HR  beats/km  relative  Minetti 2002'
        (byGradient.rows as List<Map>).each { row ->
            List<Double> edges = [0.0] + GRADIENT_BAND_EDGES_PCT + [40.0]
            int b = row.bandIndex as int
            double midPct = (edges[b] + edges[b + 1]) / 2.0 * ((row.climb as boolean) ? 1 : -1)
            println String.format(Locale.ROOT, '  %-9s %5.2f %5.0f   %6.0f    %6.0f     %5.2f       %5.2f',
                row.label, row.km as double, row.minutes as double, row.meanHr as double, row.beatsPerKm as double, row.relative as double, minettiWalkingRelative(midPct / 100.0))
        }
        summary.hrByGradient = byGradient
    }

    // Cadence and steps.
    if (session.totalCycles) {
        long steps = (session.totalCycles as long) * 2
        List<Map> moving = records.findAll { it.moving && it.cadenceSpm != null }
        double meanCadence = moving ? (moving.sum { (it.cadenceSpm as double) * (it.dtS as double) } as double) / (moving.sum { it.dtS as double } as double) : Double.NaN
        println String.format(Locale.ROOT, 'Steps          : %d (%.0f steps/min while moving, %.2f m average step)', steps, meanCadence, distanceKm * 1000.0 / steps)
        summary.steps = steps
    }

    // Energy and the watch's own effort figures.
    List<String> energy = []
    if (session.totalCalories != null) {
        energy << "${session.totalCalories} kcal"
        summary.kcal = session.totalCalories
    }
    if (session.avgMets != null) {
        energy << String.format(Locale.ROOT, '%.1f METs average', session.avgMets as double)
    }
    if (session.trainingLoad != null) {
        energy << String.format(Locale.ROOT, 'training load %.0f', session.trainingLoad as double)
        summary.trainingLoad = session.trainingLoad
    }
    if (session.rpe != null) {
        energy << String.format(Locale.ROOT, 'effort %.0f/10%s', session.rpe as double, session.rpeEstimated ? ' (estimated by the watch)' : '')
        summary.rpe = session.rpe
    }
    if (energy) {
        println "Energy/effort  : ${energy.join(', ')}"
    }

    // Weather: the watch's single value is the weather at the start; Open-Meteo along the route
    // gives the real range.
    if (session.weatherTemp != null) {
        String humidity = session.weatherHumidity != null ? String.format(Locale.ROOT, ', humidity %.0f%%', session.weatherHumidity as double) : ''
        println "Watch weather  : ${session.weatherTemp} degC${humidity} (one value per walk: the weather at the start, not an average)"
    }
    if (!options.noWeather) {
        try {
            String day = summary.date.toString()
            List<Map> weather = weatherAlongWalk(records, new java.io.File(mapsDir, "${baseName}.weather.${day}.json"))
            List<Map> valid = weather.findAll { it.temperature != null }
            if (valid) {
                Map hottest = valid.max { it.temperature as double }
                println String.format(Locale.ROOT, 'Weather (Open-Meteo, every 15 min): %.0f-%.0f degC, mean %.0f degC; hottest %.0f degC at %s; direct sun up to %.0f W/m2',
                    valid.min { it.temperature as double }.temperature as double, hottest.temperature as double,
                    (valid.sum { it.temperature as double } as double) / valid.size(), hottest.temperature as double,
                    clock(hottest.epochSecond as long, LOCAL_ZONE), valid.max { (it.radiation ?: 0.0) as double }.radiation as double)
                List<Map> heat = valid.findAll { it.humidity != null && it.dni != null && it.wind != null }.collect { s ->
                    LocalDateTime utc = LocalDateTime.ofEpochSecond(s.epochSecond as long, 0, ZoneOffset.UTC)
                    double altitude = ThermalComfort.solarAltitudeDeg(s.lat as double, s.lon as double, utc)
                    double t = s.temperature as double
                    [sample: s, sunny: altitude > 0 && (s.dni as double) >= ThermalComfort.SUNNY_DNI_W_M2,
                     shade: ThermalComfort.utci(t, t, s.wind as double, s.humidity as double),
                     sun: ThermalComfort.utci(t, t + ThermalComfort.solarDeltaMrt(altitude, s.dni as double), s.wind as double, s.humidity as double)]
                }.findAll { !Double.isNaN(it.shade as double) && !Double.isNaN(it.sun as double) }
                List<Map> sunny = heat.findAll { it.sunny }
                if (heat) {
                    println String.format(Locale.ROOT, 'Sunshine       : %.0f%% of the walk (direct sun >= %.0f W/m2)', sunny.size() * 100.0 / heat.size(), ThermalComfort.SUNNY_DNI_W_M2)
                    summary.meanTemp = (valid.sum { it.temperature as double } as double) / valid.size()
                }
                if (sunny) {
                    Map peak = sunny.max { it.sun as double }
                    println String.format(Locale.ROOT, 'Felt heat (UTCI) while sunny: shade %.0f degC, full sun %.0f degC (means); peak in full sun %.0f degC at %s (%s)',
                        (sunny.sum { it.shade as double } as double) / sunny.size(), (sunny.sum { it.sun as double } as double) / sunny.size(),
                        peak.sun as double, clock((peak.sample as Map).epochSecond as long, LOCAL_ZONE), ThermalComfort.heatStressCategory(peak.sun as double))
                    summary.meanSunUtci = (sunny.sum { it.sun as double } as double) / sunny.size()
                }
            }
        } catch (Exception ex) {
            System.err.println("Open-Meteo request failed (${ex.message}); no weather along the route.")
        }
    }
    summary
}

// ----- main -----

Options options = new Options()
CommandLine cmd = new CommandLine(options)
try {
    cmd.parseArgs(args)
} catch (CommandLine.ParameterException ex) {
    System.err.println(ex.message)
    cmd.usage(System.err)
    System.exit(2)
}
if (cmd.isUsageHelpRequested()) {
    cmd.usage(System.out)
    System.exit(0)
}
if (cmd.isVersionHelpRequested()) {
    cmd.printVersionHelp(System.out)
    System.exit(0)
}

List<Map> summaries = []
for (java.io.File file : options.files) {
    if (!file.exists()) {
        System.err.println("Input file not found: ${file}")
        continue
    }
    summaries << analyse(file, options)
}

// Pools the heart-rate-by-gradient curves of several walks into one personal cost curve, each
// walk's bands relative to its own near-flat walking and weighted by distance. Bands with less
// than a kilometre in total are left out as too noisy. Then fits how net heartbeats per
// kilometre of that curve's cost rise with each walk's mean air temperature along the route.
Map pooledEffortCurve(List<Map> summaries) {
    Map<String, double[]> pooled = [:]
    Map<String, Map> meta = [:]
    summaries.findAll { it.hrByGradient }.each { s ->
        ((s.hrByGradient as Map).rows as List<Map>).each { row ->
            if (!Double.isNaN(row.relative as double)) {
                double[] acc = pooled.computeIfAbsent(row.label as String) { new double[2] }
                acc[0] += row.km as double
                acc[1] += (row.km as double) * (row.relative as double)
                meta[row.label as String] = row
            }
        }
    }
    List<Double> edges = [0.0] + GRADIENT_BAND_EDGES_PCT + [40.0]
    List<Map> anchors = pooled.findAll { label, acc -> acc[0] >= 1.0 }.collect { label, acc ->
        Map row = meta[label]
        int b = row.bandIndex as int
        double mid = (edges[b] + edges[b + 1]) / 2.0 * ((row.climb as boolean) ? 1 : -1)
        [label: label, gradePct: mid, km: acc[0], factor: acc[1] / acc[0]]
    }.sort { it.gradePct as double }
    Closure<Double> factorFor = { String label -> pooled[label] && pooled[label][0] >= 1.0 ? pooled[label][1] / pooled[label][0] : Double.NaN }

    // Heat: net heartbeats per kilometre of curve cost against mean air temperature.
    List<double[]> points = []
    summaries.findAll { it.hrByGradient && it.meanTemp != null && it.netBeats != null }.each { s ->
        double costKm = 0.0
        double coveredKm = 0.0
        ((s.hrByGradient as Map).rows as List<Map>).each { row ->
            double f = factorFor(row.label as String)
            if (!Double.isNaN(f)) {
                costKm += (row.km as double) * f
                coveredKm += row.km as double
            }
        }
        if (costKm > 0) {
            // Scale the cost to the whole walk: bands left out as too noisy are a tiny share.
            double movingKm = ((s.hrByGradient as Map).rows as List<Map>).sum { it.km as double } as double
            points << ([s.meanTemp as double, (s.netBeats as double) / (costKm * movingKm / coveredKm)] as double[])
        }
    }
    Map heat = null
    if (points.size() >= 3) {
        double mx = points.sum { it[0] } / points.size()
        double my = points.sum { it[1] } / points.size()
        double sxy = points.sum { (it[0] - mx) * (it[1] - my) } as double
        double sxx = points.sum { (it[0] - mx) * (it[0] - mx) } as double
        double slope = sxy / sxx
        double intercept = my - slope * mx
        double at20 = intercept + slope * 20.0
        heat = [slope: slope, intercept: intercept, pctPerDegAt20: slope / at20 * 100.0, walks: points.size(),
                minTemp: points.min { it[0] }[0], maxTemp: points.max { it[0] }[0]]
    }
    [anchors: anchors, heat: heat]
}

if (summaries.size() > 1) {
    Map curve = pooledEffortCurve(summaries)
    if (curve.anchors) {
        println ''
        println "=== Personal effort curve from ${summaries.count { it.hrByGradient }} walks ==="
        println 'Net heartbeats per km relative to near-flat walking, pooled by distance (bands under 1 km left out):'
        println '  Gradient  mid %      km  factor  Minetti 2002'
        (curve.anchors as List<Map>).each { a ->
            println String.format(Locale.ROOT, '  %-8s %6.1f  %6.1f   %5.2f       %5.2f', a.label, a.gradePct as double, a.km as double, a.factor as double, minettiWalkingRelative((a.gradePct as double) / 100.0))
        }
        println 'As anchors [grade %, factor]: ' + (curve.anchors as List<Map>).collect { String.format(Locale.ROOT, '[%.1f, %.2f]', it.gradePct as double, it.factor as double) }.join(', ')
        if (curve.heat) {
            Map h = curve.heat as Map
            println String.format(Locale.ROOT, 'Heat: net heartbeats per km of that cost rise %.1f%% per degC of mean air temperature (relative to 20 degC; %d walks, %.0f-%.0f degC)',
                h.pctPerDegAt20 as double, h.walks as int, h.minTemp as double, h.maxTemp as double)
        }
    }

    println ''
    println '=== All walks ==='
    println 'Date        km   up(m) down(m) steep up/down  moving   avg HR  net beats  beats/km   kcal  load  effort  air degC  sun UTCI'
    summaries.each { s ->
        println String.format(Locale.ROOT, '%s %5.1f %6.0f %6.0f    %4.0f/%-4.0f    %s  %5.0f  %8.0f  %7.0f  %6s %5s  %5s   %6s   %6s',
            s.date, s.km as double, s.ascent as double, s.descent as double, s.steepUp as double, s.steepDown as double,
            formatHours(s.movingHours as double), (s.meanHr ?: Double.NaN) as double, (s.netBeats ?: Double.NaN) as double,
            s.netBeats != null ? (s.netBeats as double) / (s.km as double) : Double.NaN,
            s.kcal ?: '-', s.trainingLoad != null ? String.format(Locale.ROOT, '%.0f', s.trainingLoad as double) : '-',
            s.rpe != null ? String.format(Locale.ROOT, '%.0f', s.rpe as double) : '-',
            s.meanTemp != null ? String.format(Locale.ROOT, '%.0f', s.meanTemp as double) : '-',
            s.meanSunUtci != null ? String.format(Locale.ROOT, '%.0f', s.meanSunUtci as double) : '-')
    }
}
