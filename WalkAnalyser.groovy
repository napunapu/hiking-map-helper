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

    @Option(names = ['-l', '--language'], description = 'Report language: en (English, the default) or fi (Finnish); notes and highlights are read from <walk>.notes.fi.txt and <walk>.highlights.fi.txt for Finnish')
    String language = 'en'

    @Option(names = ['--no-highlights'], description = 'Don\'t look up route highlights in OpenStreetMap (via the Overpass API) or draft highlight files')
    boolean noHighlights = false

    @Option(names = ['--redraft'], description = 'Rewrite highlight files that are still drafts (their draft line not yet removed); checked files are never overwritten')
    boolean redraft = false

    @Option(names = ['-o', '--report'], description = 'Combined Markdown report of all walks: notes, highlights and figures (default: walk-report.md, or walk-report.fi.md in Finnish, next to the first input file)')
    java.io.File reportFile

    @Option(names = ['--no-report'], description = 'Don\'t write the combined Markdown report, only print to the console')
    boolean noReport = false

    @Option(names = ['--maps'], description = 'Cache folder for terrain tiles, weather and OpenStreetMap features (default: the nearest existing maps/ folder next to the input file or one level up, otherwise maps/ next to the input file)')
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
// Moving means faster than this over a 20 s window, or climbing or descending faster than
// MOVING_VERTICAL_M_S over a minute: on a steep climb the horizontal speed can drop to a few
// metres a minute while the walker is still gaining 3–7 m of height a minute. A pause shorter
// than MIN_STOP_S (a hesitation, a look round) counts as moving.
@Field final double MOVING_SPEED_M_S = 0.5
@Field final double MOVING_WINDOW_S = 20.0
@Field final double MOVING_VERTICAL_M_S = 0.05
@Field final double MOVING_VERTICAL_WINDOW_S = 60.0
@Field final double MIN_STOP_S = 30.0
// Heart rate trails a change in effort by roughly half a minute, so each stretch of track is
// paired with the heart rate this long after it when splitting by gradient.
@Field final int HR_LAG_S = 30
@Field final ZoneId LOCAL_ZONE = ZoneId.of('Europe/Madrid')

// ----- localisation -----

// All report text in English (British, following the European Commission's DGT English Style
// Guide) and Finnish; see docs/style-guide.md for the number, unit, date and time conventions.
// Templates take %s placeholders filled with already formatted values (see the formatting
// helpers below), so a translation can reorder nothing but its own words.
@Field final List<String> LANGUAGES = ['en', 'fi']
@Field String lang = 'en'
@Field final Map<String, Map<String, String>> MESSAGES = [
    en: [
        'label.altitudeFrom': 'Altitude from',
        'label.walk': 'Walk',
        'label.howItFelt': 'How it felt',
        'label.highlights': 'Highlights',
        'label.startCorrected': 'Start corrected',
        'label.distance': 'Distance',
        'label.time': 'Time',
        'label.ascentDescent': 'Ascent/descent',
        'label.altitudeRange': 'Altitude range',
        'label.heartRate': 'Heart rate',
        'label.netBeats': 'Net heartbeats',
        'label.zones': 'Heart-rate zones',
        'label.steps': 'Steps',
        'label.energy': 'Energy/effort',
        'label.watchWeather': 'Watch weather',
        'label.weather': 'Weather',
        'label.sunshine': 'Sunshine',
        'label.feltHeat': 'Felt heat (UTCI)',
        'draft': 'draft, unchecked',
        'fallback': 'in Finnish',
        'altitude.fit': 'FIT (0.2 m steps)',
        'altitude.gpx': 'GPX export (%s of %s records), FIT distance',
        'walk': '%s, %s–%s local time',
        'start.corrected': 'watch read %s against terrain, settling over %s',
        'start.notCorrected': 'checked against terrain: no settling error found, not corrected',
        'start.unavailable': 'IGN MDT05 terrain model unavailable (%s); start not corrected.',
        'distance': '%s (watch)',
        'time': '%s elapsed, %s moving (%s), %s stopped',
        'ascent': '%s / %s at %s threshold%s',
        'ascent.watchTotal': ' (watch\'s own total %s)',
        'bands.gradient': 'Gradient',
        'bands.ascent': 'Ascent',
        'bands.descent': 'Descent',
        'steep': 'Steeper than %s: %s up, %s down',
        'heartRate': '%s average / %s maximum / %s minimum (watch); %s average while moving, %s of heart-rate reserve (resting %s, maximum %s)',
        'netBeats': '%s above resting while moving (%s per km, %s per moving minute)',
        'zones': '%s (watch)',
        'byGradient.title': 'Heart rate by gradient while moving (heart rate %s later; net beats per km relative to %s, flat = %s beats/km):',
        'byGradient.km': 'km',
        'byGradient.min': 'min',
        'byGradient.meanHr': 'Mean HR',
        'byGradient.beatsPerKm': 'Beats/km',
        'byGradient.relative': 'Relative',
        'byGradient.minetti': 'Minetti 2002',
        'steps': '%s (%s steps/min while moving, %s average step)',
        'energy.mets': '%s METs average',
        'energy.load': 'training load %s',
        'energy.effort': 'effort %s/10%s',
        'energy.estimated': ' (estimated by the watch)',
        'watchWeather': '%s%s (one value per walk: the weather at the start, not an average)',
        'watchWeather.humidity': ', humidity %s',
        'weather': 'Open-Meteo, every 15 min: %s, mean %s; hottest %s at %s; direct sun up to %s',
        'sunshine': '%s of the walk (direct sun ≥ %s)',
        'feltHeat': 'while sunny, shade %s and full sun %s (means); peak in full sun %s at %s (%s)',
        'weather.failed': 'Open-Meteo request failed (%s); no weather along the route.',
        'heat.no heat stress': 'no heat stress',
        'heat.moderate heat stress': 'moderate heat stress',
        'heat.strong heat stress': 'strong heat stress',
        'heat.very strong heat stress': 'very strong heat stress',
        'heat.extreme heat stress': 'extreme heat stress',
        'input.notFound': 'Input file not found: %s',
        'curve.title': 'Personal effort curve from %s walks',
        'curve.intro': 'Net heartbeats per km relative to near-flat walking, pooled by distance (bands under 1 km left out):',
        'curve.mid': 'Mid',
        'curve.factor': 'Factor',
        'curve.anchors': 'As anchors [grade %, factor]: ',
        'curve.heat': 'Heat: net heartbeats per km of that cost rise %s per °C of mean air temperature (relative to 20 °C; %s walks, %s)',
        'all.title': 'All walks',
        'all.date': 'Date',
        'all.up': 'Up',
        'all.down': 'Down',
        'all.steep': 'Steep up/down',
        'all.moving': 'Moving',
        'all.hr': 'Mean HR',
        'all.netBeats': 'Net beats',
        'all.beatsPerKm': 'Beats/km',
        'all.load': 'Load',
        'all.effort': 'Effort',
        'all.air': 'Air',
        'all.sunUtci': 'Sun UTCI',
        'report.title': 'Recorded walks',
        'report.intro': 'Report of %s recorded walks, written by WalkAnalyser on %s. Notes on how each walk felt and the highlights come from the notes and highlights files next to the FIT files; figures come from the recordings.',
        'report.written': 'Report written to %s',
        'label.file': 'File',
        'hl.draftHeader': '# Draft: best guess from OpenStreetMap and the recorded data – check, edit and delete this line.',
        'hl.route': 'Route: %s',
        'hl.item': 'km %s (%s): %s',
        'hl.stop': '%s-minute stop',
        'hl.highPoint': 'High point: %s at km %s%s',
        'hl.climb': 'Longest climb: %s over %s (km %s, average %s)',
        'hl.descent': 'Longest descent: %s over %s (km %s, average %s)',
        'hl.failed': 'OpenStreetMap request failed (%s); highlights from the recorded data only, no draft written.',
        'hl.written': 'Draft highlights written to %s',
        'kind.castle': 'castle',
        'kind.lighthouse': 'lighthouse',
        'kind.monastery': 'monastery',
        'kind.peak': 'peak',
        'kind.viewpoint': 'viewpoint',
        'kind.beach': 'beach',
        'kind.cove': 'cove',
        'kind.headland': 'headland',
        'kind.tower': 'tower',
        'kind.ruins': 'ruins',
        'kind.archaeological': 'archaeological site',
        'kind.gate': 'town gate',
        'kind.church': 'church',
        'kind.chapel': 'chapel',
        'kind.monument': 'monument',
        'kind.spring': 'spring',
        'kind.waterfall': 'waterfall',
        'kind.cave': 'cave',
        'kind.museum': 'museum',
        'kind.attraction': 'attraction'
    ],
    fi: [
        'label.altitudeFrom': 'Korkeuslähde',
        'label.walk': 'Kävely',
        'label.howItFelt': 'Tuntuma',
        'label.highlights': 'Kohokohdat',
        'label.startCorrected': 'Alun korjaus',
        'label.distance': 'Matka',
        'label.time': 'Aika',
        'label.ascentDescent': 'Nousu/lasku',
        'label.altitudeRange': 'Korkeusvaihtelu',
        'label.heartRate': 'Syke',
        'label.netBeats': 'Nettolyönnit',
        'label.zones': 'Sykealueet',
        'label.steps': 'Askeleet',
        'label.energy': 'Energia/rasitus',
        'label.watchWeather': 'Kellon sää',
        'label.weather': 'Sää',
        'label.sunshine': 'Auringonpaiste',
        'label.feltHeat': 'Tuntuva lämpö (UTCI)',
        'draft': 'luonnos, tarkistamatta',
        'fallback': 'englanniksi',
        'altitude.fit': 'FIT (0,2 m:n portain)',
        'altitude.gpx': 'GPX-vienti (%s/%s tietuetta), matka FIT-tiedostosta',
        'walk': '%s klo %s–%s paikallista aikaa',
        'start.corrected': 'kello näytti %s maastomalliin verrattuna, tasaantui %s:n matkalla',
        'start.notCorrected': 'tarkistettu maastomallista: tasaantumisvirhettä ei löytynyt, ei korjattu',
        'start.unavailable': 'IGN MDT05 -maastomalli ei ole käytettävissä (%s); alkua ei korjattu.',
        'distance': '%s (kello)',
        'time': '%s yhteensä, %s liikkeellä (%s), %s pysähdyksissä',
        'ascent': '%s / %s, kynnys %s%s',
        'ascent.watchTotal': ' (kellon oma summa %s)',
        'bands.gradient': 'Kaltevuus',
        'bands.ascent': 'Nousu',
        'bands.descent': 'Lasku',
        'steep': 'Jyrkempää kuin %s: %s ylös, %s alas',
        'heartRate': 'keskimäärin %s / enintään %s / vähintään %s (kello); liikkeellä keskimäärin %s, %s sykereservistä (leposyke %s, maksimisyke %s)',
        'netBeats': '%s leposykkeen yli liikkeellä (%s/km, %s liikeminuuttia kohden)',
        'zones': '%s (kello)',
        'byGradient.title': 'Syke kaltevuuden mukaan liikkeellä (syke %s myöhemmin; nettolyönnit kilometriä kohden suhteessa kaltevuuteen %s, tasainen = %s lyöntiä/km):',
        'byGradient.km': 'km',
        'byGradient.min': 'min',
        'byGradient.meanHr': 'Keskisyke',
        'byGradient.beatsPerKm': 'Lyöntiä/km',
        'byGradient.relative': 'Suhteellinen',
        'byGradient.minetti': 'Minetti 2002',
        'steps': '%s (liikkeellä %s askelta/min, keskimääräinen askel %s)',
        'energy.mets': 'keskimäärin %s MET',
        'energy.load': 'harjoituskuorma %s',
        'energy.effort': 'rasitus %s/10%s',
        'energy.estimated': ' (kellon arvio)',
        'watchWeather': '%s%s (yksi arvo kävelyä kohden: sää alussa, ei keskiarvo)',
        'watchWeather.humidity': ', kosteus %s',
        'weather': 'Open-Meteo, 15 minuutin välein: %s, keskimäärin %s; lämpimin %s klo %s; suoraa auringonsäteilyä enintään %s',
        'sunshine': '%s kävelystä (suora säteily ≥ %s)',
        'feltHeat': 'auringon paistaessa varjossa %s ja täydessä auringossa %s (keskiarvot); huippu täydessä auringossa %s klo %s (%s)',
        'weather.failed': 'Open-Meteo-pyyntö epäonnistui (%s); reitin säätietoja ei ole.',
        'heat.no heat stress': 'ei lämpökuormitusta',
        'heat.moderate heat stress': 'kohtalainen lämpökuormitus',
        'heat.strong heat stress': 'voimakas lämpökuormitus',
        'heat.very strong heat stress': 'hyvin voimakas lämpökuormitus',
        'heat.extreme heat stress': 'äärimmäinen lämpökuormitus',
        'input.notFound': 'Syötetiedostoa ei löydy: %s',
        'curve.title': 'Henkilökohtainen rasituskäyrä %s kävelystä',
        'curve.intro': 'Nettolyönnit kilometriä kohden suhteessa lähes tasaiseen kävelyyn, matkalla painotettuna (alle 1 km:n kaltevuusluokat jätetty pois):',
        'curve.mid': 'Keskikohta',
        'curve.factor': 'Kerroin',
        'curve.anchors': 'Ankkureina [kaltevuus %, kerroin]: ',
        'curve.heat': 'Lämpö: nettolyönnit tämän kustannuksen kilometriä kohden kasvavat %s ilman keskilämpötilan astetta kohden (suhteessa 20 °C:seen; %s kävelyä, %s)',
        'all.title': 'Kaikki kävelyt',
        'all.date': 'Päivä',
        'all.up': 'Ylös',
        'all.down': 'Alas',
        'all.steep': 'Jyrkkä ylös/alas',
        'all.moving': 'Liikkeellä',
        'all.hr': 'Keskisyke',
        'all.netBeats': 'Nettolyönnit',
        'all.beatsPerKm': 'Lyöntiä/km',
        'all.load': 'Kuorma',
        'all.effort': 'Rasitus',
        'all.air': 'Ilma',
        'all.sunUtci': 'UTCI auringossa',
        'report.title': 'Tallennetut kävelyt',
        'report.intro': 'Raportti %s tallennetusta kävelystä, laadittu WalkAnalyserilla %s. Tuntumat ja kohokohdat ovat FIT-tiedostojen vieressä olevista muistiinpano- ja kohokohtatiedostoista, luvut tallenteista.',
        'report.written': 'Raportti kirjoitettu: %s',
        'label.file': 'Tiedosto',
        'hl.draftHeader': '# Luonnos (draft): paras arvaus OpenStreetMapin ja tallennettujen tietojen perusteella – tarkista, muokkaa ja poista tämä rivi.',
        'hl.route': 'Reitti: %s',
        'hl.item': 'km %s (klo %s): %s',
        'hl.stop': '%s minuutin tauko',
        'hl.highPoint': 'Korkein kohta: %s, km %s%s',
        'hl.climb': 'Pisin nousu: %s %s:n matkalla (km %s, keskimäärin %s)',
        'hl.descent': 'Pisin lasku: %s %s:n matkalla (km %s, keskimäärin %s)',
        'hl.failed': 'OpenStreetMap-pyyntö epäonnistui (%s); kohokohdat vain tallennetuista tiedoista, luonnosta ei kirjoitettu.',
        'hl.written': 'Kohokohtien luonnos kirjoitettu: %s',
        'kind.castle': 'linna',
        'kind.lighthouse': 'majakka',
        'kind.monastery': 'luostari',
        'kind.peak': 'huippu',
        'kind.viewpoint': 'näköalapaikka',
        'kind.beach': 'ranta',
        'kind.cove': 'poukama',
        'kind.headland': 'niemi',
        'kind.tower': 'torni',
        'kind.ruins': 'rauniot',
        'kind.archaeological': 'muinaisjäännös',
        'kind.gate': 'kaupunginportti',
        'kind.church': 'kirkko',
        'kind.chapel': 'kappeli',
        'kind.monument': 'muistomerkki',
        'kind.spring': 'lähde',
        'kind.waterfall': 'vesiputous',
        'kind.cave': 'luola',
        'kind.museum': 'museo',
        'kind.attraction': 'nähtävyys'
    ]
]

String tr(String key, Object... args) {
    trIn(lang, key, args)
}

String trIn(String language, String key, Object... args) {
    String template = MESSAGES[language][key] ?: MESSAGES.en[key]
    if (template == null) {
        throw new IllegalArgumentException("No message for ${key}")
    }
    args ? String.format(Locale.ROOT, template, args) : template
}

// Hard (non-breaking) space, used between a number and its unit and to group thousands.
@Field final String NBSP = ' '

// A number with the language's decimal marker (point in English, comma in Finnish), thousands
// grouped with a hard space (15 000) and a true minus sign; NaN prints as an en dash.
String num(double value, int decimals, String language = lang) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
        return '–'
    }
    String plain = String.format(Locale.ROOT, "%.${decimals}f", Math.abs(value))
    int point = plain.indexOf('.')
    String whole = point >= 0 ? plain.substring(0, point) : plain
    String fraction = point >= 0 ? plain.substring(point + 1) : ''
    StringBuilder grouped = new StringBuilder()
    for (int i = 0; i < whole.length(); i++) {
        if (i > 0 && (whole.length() - i) % 3 == 0) {
            grouped.append(NBSP)
        }
        grouped.append(whole.charAt(i))
    }
    boolean negative = value < 0 && plain.any { it ==~ /[1-9]/ }
    (negative ? '−' : '') + grouped + (fraction ? (language == 'fi' ? ',' : '.') + fraction : '')
}

String signed(double value, int decimals, String language = lang) {
    String text = num(value, decimals, language)
    value > 0 && text ==~ /.*[1-9].*/ ? '+' + text : text
}

String unit(double value, int decimals, String symbol, String language = lang) {
    num(value, decimals, language) + NBSP + symbol
}

// Per cent: closed up in English (25%), with a hard space in Finnish (25 %).
String pct(double value, int decimals, String language = lang) {
    num(value, decimals, language) + (language == 'fi' ? NBSP : '') + '%'
}

// A range with a closed-up en dash and the unit once: 10–70 °C.
String range(double from, double to, int decimals, String symbol, String language = lang) {
    String suffix = symbol == '%' ? (language == 'fi' ? NBSP + '%' : '%') : NBSP + symbol
    num(from, decimals, language) + '–' + num(to, decimals, language) + suffix
}

// Time of day on the 24-hour clock: 09:30 in English, 9.30 in Finnish.
String clock(long epochSecond, String language = lang) {
    Instant.ofEpochSecond(epochSecond).atZone(LOCAL_ZONE).format(DateTimeFormatter.ofPattern(language == 'fi' ? 'H.mm' : 'HH:mm'))
}

// Dates in full in running text (6 September 2026) and in the short form in tables (6.9.2026,
// no leading zeros) - the same short form in both languages.
String longDate(LocalDate date, String language = lang) {
    language == 'fi' ? date.format(DateTimeFormatter.ofPattern('d.M.yyyy')) : date.format(DateTimeFormatter.ofPattern('d MMMM yyyy', Locale.UK))
}

String shortDate(LocalDate date) {
    date.format(DateTimeFormatter.ofPattern('d.M.yyyy'))
}

String formatHours(double hours) {
    int totalMinutes = Math.round(hours * 60.0) as int
    String.format(Locale.ROOT, '%d%sh %02d%smin', totalMinutes.intdiv(60), NBSP, totalMinutes % 60, NBSP)
}

// The labels in front of each report line, padded to the longest in the current language.
@Field final List<String> LABEL_KEYS = ['file', 'altitudeFrom', 'walk', 'howItFelt', 'highlights', 'startCorrected', 'distance', 'time',
    'ascentDescent', 'altitudeRange', 'heartRate', 'netBeats', 'zones', 'steps', 'energy', 'watchWeather', 'weather', 'sunshine', 'feltHeat']

// Everything the report prints goes through the functions below, which print it to the
// console and also keep it as blocks for the combined Markdown report (see writeReport). The
// section says where a block belongs in that report: a walk, the effort curve or the table of
// all walks.
@Field List<Map> reportBlocks = []
@Field String reportSection = 'walk'

void printHeading(String consoleText, String markdownText) {
    println ''
    println "=== ${consoleText} ==="
    reportBlocks << [section: reportSection, type: 'heading', text: markdownText]
}

void printLine(String labelKey, String text) {
    int width = LABEL_KEYS.collect { tr("label.${it}").length() }.max()
    println String.format(Locale.ROOT, "%-${width}s : %s", tr("label.${labelKey}"), text)
    reportBlocks << [section: reportSection, type: 'field', label: tr("label.${labelKey}"), text: text]
}

void printText(String text) {
    println text
    reportBlocks << [section: reportSection, type: 'text', text: text]
}

// A labelled paragraph (a note) or list (highlights), with marks such as "(draft, unchecked)".
void printLabelled(String labelKey, String marks, List<String> lines, boolean asList) {
    if (asList) {
        printLineConsoleOnly(labelKey, marks.trim())
        lines.each { println "  ${it}" }
    } else {
        printLineConsoleOnly(labelKey, marks + lines.join(' '))
    }
    reportBlocks << [section: reportSection, type: asList ? 'labelledList' : 'labelledText', label: tr("label.${labelKey}"), marks: marks.trim(), lines: lines]
}

void printLineConsoleOnly(String labelKey, String text) {
    int width = LABEL_KEYS.collect { tr("label.${it}").length() }.max()
    println String.format(Locale.ROOT, "%-${width}s : %s", tr("label.${labelKey}"), text)
}

// A table with columns sized to fit, the first column left-aligned and the rest right-aligned.
void printTable(List<String> headers, List<List<String>> rows, String indent = '  ') {
    List<Integer> widths = (0..<headers.size()).collect { c -> ([headers[c]] + rows.collect { it[c] }).collect { it.length() }.max() }
    Closure<String> format = { List<String> cells ->
        indent + (0..<cells.size()).collect { c -> c == 0 ? cells[c].padRight(widths[c]) : cells[c].padLeft(widths[c]) }.join('  ')
    }
    println format(headers)
    rows.each { println format(it) }
    reportBlocks << [section: reportSection, type: 'table', headers: headers, rows: rows]
}

// The combined report as Markdown: a title, the table of all walks, then each walk (its notes
// and highlights first, then its figures and tables) and finally the personal effort curve.
// Consecutive fields become one bullet list. The output passes markdownlint, apart from long
// lines (MD013).
String markdownReport(int walkCount) {
    List<String> out = ["# ${tr('report.title')}".toString(), '',
        tr('report.intro', num(walkCount, 0), longDate(LocalDate.now())), '']
    Closure<String> escape = { String text -> text.replace('|', '\\|') }
    Closure<String> table = { List<String> headers, List<List<String>> rows ->
        List<Integer> widths = (0..<headers.size()).collect { c ->
            Math.max(3, ([headers[c]] + rows.collect { it[c] }).collect { escape(it).length() }.max() as int)
        }
        Closure<String> row = { List<String> cells ->
            '| ' + (0..<cells.size()).collect { c -> c == 0 ? escape(cells[c]).padRight(widths[c]) : escape(cells[c]).padLeft(widths[c]) }.join(' | ') + ' |'
        }
        String rule = '| ' + (0..<headers.size()).collect { c -> c == 0 ? '-' * widths[c] : '-' * (widths[c] - 1) + ':' }.join(' | ') + ' |'
        ([row(headers), rule] + rows.collect { row(it) }).join('\n')
    }
    List<Map> ordered = []
    ['all', 'walk', 'curve'].each { section ->
        List<Map> blocks = reportBlocks.findAll { it.section == section }
        if (section == 'walk') {
            // Within each walk, notes and highlights come straight after the heading.
            List<List<Map>> walks = []
            blocks.each { b ->
                if (b.type == 'heading') {
                    walks << []
                }
                walks[-1] << b
            }
            walks.each { w ->
                ordered.addAll(w.findAll { it.type == 'heading' })
                ordered.addAll(w.findAll { it.type in ['labelledText', 'labelledList'] })
                ordered.addAll(w.findAll { !(it.type in ['heading', 'labelledText', 'labelledList']) })
            }
        } else {
            ordered.addAll(blocks)
        }
    }
    for (int i = 0; i < ordered.size(); i++) {
        Map b = ordered[i]
        switch (b.type) {
            case 'heading':
                out << "## ${b.text}".toString()
                break
            case 'field':
                out << "- **${b.label}:** ${b.text}".toString()
                if (i + 1 < ordered.size() && ordered[i + 1].type == 'field') {
                    continue
                }
                break
            case 'text':
                out << (b.text as String)
                break
            case 'labelledText':
                out << "**${b.label}**${b.marks ? ' ' + b.marks : ''}: ${(b.lines as List<String>).join(' ')}".toString()
                break
            case 'labelledList':
                out << "**${b.label}**${b.marks ? ' ' + b.marks : ''}:".toString()
                out << ''
                (b.lines as List<String>).each { out << "- ${it}".toString() }
                break
            case 'table':
                out << table(b.headers as List<String>, b.rows as List<List<String>>)
                break
        }
        out << ''
    }
    while (out && out[-1] == '') {
        out.remove(out.size() - 1)
    }
    out.join('\n') + '\n'
}

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
    Closure<String> stem = { String name -> name.replaceFirst(/(?i)\.(fit|gpx|(notes|highlights)(\.[a-z]{2})?\.txt)$/, '').replaceAll(/[\s\u00a0]+/, ' ') }
    String wanted = stem(fitFile.name)
    fitFile.absoluteFile.parentFile.listFiles()?.find { it.name.toLowerCase().endsWith(suffix) && stem(it.name) == wanted }
}

// Notes on how a walk felt and its highlights, kept next to the FIT file as <walk>.notes.txt
// and <walk>.highlights.txt in English and <walk>.notes.fi.txt and <walk>.highlights.fi.txt in
// Finnish. Lines starting with # are comments; a comment containing "draft" (or "luonnos")
// marks the text as an unchecked best guess, shown as such. Returns the text in the report
// language, or in the other one (marked as a fallback) when there's none in it yet.
Map readWalkText(java.io.File fitFile, String kind, boolean joinLines) {
    List<String> order = [lang] + (LANGUAGES - lang)
    for (String language : order) {
        java.io.File textFile = matchingSibling(fitFile, language == 'en' ? ".${kind}.txt" : ".${kind}.${language}.txt")
        Map text = textFile ? readWalkFile(textFile, joinLines) : null
        if (text) {
            return text + [fallback: language != lang]
        }
    }
    null
}

Map readWalkFile(java.io.File textFile, boolean joinLines) {
    List<String> all = textFile.readLines('UTF-8')
    List<String> lines = all.findAll { !it.trim().startsWith('#') && it.trim() }.collect { it.replaceAll(/\s+/, ' ').trim() }
    if (joinLines && lines) {
        lines = [lines.join(' ')]
    }
    boolean draft = all.any { it.trim().startsWith('#') && it.toLowerCase() =~ /draft|luonnos/ }
    lines ? [lines: lines, draft: draft] : null
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

    // Null for the FIT file's own altitudes, otherwise how many records got a GPX altitude.
    Map altitudeSource = [matched: null]
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
            altitudeSource = [matched: matched, total: records.count { it.altitude != null }]
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

// Marks each record as moving or not (see MOVING_SPEED_M_S; a recording gap over 10 s is never
// moving), and stores the seconds since the previous record.
void markMoving(List<Map> records) {
    int a = 0
    int v = 0
    records[0].moving = false
    records[0].dtS = 0.0d
    for (int i = 1; i < records.size(); i++) {
        long t = records[i].epochSecond as long
        while (t - (records[a].epochSecond as long) > MOVING_WINDOW_S) {
            a++
        }
        while (t - (records[v].epochSecond as long) > MOVING_VERTICAL_WINDOW_S) {
            v++
        }
        long span = t - (records[a].epochSecond as long)
        double speed = span > 0 ? ((records[i].distance as double) - (records[a].distance as double)) / span : 0.0
        long verticalSpan = t - (records[v].epochSecond as long)
        double verticalSpeed = verticalSpan > 0 && records[i].altitude != null && records[v].altitude != null ?
            Math.abs((records[i].altitude as double) - (records[v].altitude as double)) / verticalSpan : 0.0
        double dt = t - (records[i - 1].epochSecond as long)
        records[i].dtS = dt
        records[i].moving = dt <= 10.0 && (speed > MOVING_SPEED_M_S || verticalSpeed > MOVING_VERTICAL_M_S)
    }
    // Short pauses count as moving, unless they contain a recording gap.
    int i = 1
    while (i < records.size()) {
        if (records[i].moving) {
            i++
            continue
        }
        int j = i
        while (j < records.size() && !records[j].moving) {
            j++
        }
        List<Map> pause = records.subList(i, j)
        double seconds = pause.sum { it.dtS as double } as double
        if (j < records.size() && seconds < MIN_STOP_S && pause.every { (it.dtS as double) <= 10.0 }) {
            pause.each { it.moving = true }
        }
        i = j
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
    // Not "if (explicit)": Groovy counts a folder that doesn't exist yet as false.
    if (explicit != null) {
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

// ----- route highlights -----

// Kinds of OpenStreetMap feature worth listing as a highlight: a base score for ranking, how
// far from the track it may lie and still count as passed (a viewpoint or church only when
// walked right past, a peak or lighthouse also when seen close by), and whether it needs a
// name (an unnamed viewpoint is only listed when the walk stopped there).
@Field final Map<String, Map> HIGHLIGHT_KINDS = [
    castle: [score: 5, maxOffM: 100.0],
    lighthouse: [score: 5, maxOffM: 150.0],
    monastery: [score: 5, maxOffM: 100.0],
    peak: [score: 4, maxOffM: 100.0],
    waterfall: [score: 3, maxOffM: 100.0],
    viewpoint: [score: 3, maxOffM: 75.0, unnamed: true],
    beach: [score: 3, maxOffM: 100.0],
    cove: [score: 3, maxOffM: 100.0],
    tower: [score: 3, maxOffM: 100.0],
    ruins: [score: 3, maxOffM: 75.0],
    headland: [score: 2, maxOffM: 100.0],
    archaeological: [score: 2, maxOffM: 75.0],
    gate: [score: 2, maxOffM: 50.0],
    church: [score: 2, maxOffM: 50.0],
    chapel: [score: 2, maxOffM: 50.0],
    spring: [score: 2, maxOffM: 50.0],
    monument: [score: 1, maxOffM: 50.0],
    cave: [score: 1, maxOffM: 50.0],
    museum: [score: 1, maxOffM: 50.0],
    attraction: [score: 1, maxOffM: 50.0]
]
@Field final int MAX_HIGHLIGHTS = 10
// Stops at least this long, away from the start and finish, are listed and paired with the
// best feature within STOP_MATCH_M of where the walk stopped.
@Field final double STOP_MIN_MINUTES = 10.0
@Field final double STOP_MATCH_M = 150.0
// Climbs and descents end where the altitude turns back by at least this much.
@Field final double LEG_REVERSAL_M = 20.0

// The highlight kind of an OpenStreetMap feature, or null if it isn't one. Catalan and
// Spanish names say what a building is more reliably than its tags (a monastery's church is
// often tagged only as a church).
String highlightKind(Map tags) {
    String historic = tags.historic
    String building = tags.building
    String name = (tags.name ?: '').toString().toLowerCase()
    if (name ==~ /^(monestir|monasterio|cartoixa|cartuja)\b.*/) {
        return 'monastery'
    }
    if (name ==~ /^(ermita|capella|capilla)\b.*/) {
        return 'chapel'
    }
    if (historic == 'castle' || tags.castle_type) {
        return 'castle'
    }
    if (tags.man_made == 'lighthouse') {
        return 'lighthouse'
    }
    if (historic == 'monastery' || tags.amenity == 'monastery' || building == 'monastery') {
        return 'monastery'
    }
    if (tags.natural == 'peak') {
        return 'peak'
    }
    if (tags.waterway == 'waterfall') {
        return 'waterfall'
    }
    if (tags.tourism == 'viewpoint') {
        return 'viewpoint'
    }
    if (tags.natural == 'beach') {
        return 'beach'
    }
    if (tags.natural == 'bay') {
        return 'cove'
    }
    if (tags.man_made == 'tower' || historic == 'tower') {
        return 'tower'
    }
    if (historic == 'ruins' || building == 'ruins') {
        return 'ruins'
    }
    if (tags.natural == 'cape') {
        return 'headland'
    }
    if (historic == 'city_gate') {
        return 'gate'
    }
    if (building == 'chapel') {
        return 'chapel'
    }
    if (building in ['church', 'cathedral'] || (tags.amenity == 'place_of_worship' && tags.religion == 'christian')) {
        return 'church'
    }
    if (historic == 'archaeological_site') {
        return 'archaeological'
    }
    if (tags.natural == 'spring') {
        return 'spring'
    }
    if (historic in ['monument', 'memorial']) {
        return 'monument'
    }
    if (tags.natural == 'cave_entrance') {
        return 'cave'
    }
    if (tags.tourism == 'museum') {
        return 'museum'
    }
    if (tags.tourism == 'attraction') {
        return 'attraction'
    }
    null
}

// Named features and settlements around the walk from the Overpass API: one query for the
// walk's bounding box (much lighter for the shared public server than a corridor along the
// track), filtered by distance from the track afterwards. Settlements get a wider box, since
// a town's node can sit well away from the path through its outskirts. Cached under maps/;
// delete the file to refresh it.
Map fetchOsmFeatures(List<Map> points, java.io.File cacheFile) {
    double south = points.min { it.lat as double }.lat as double
    double north = points.max { it.lat as double }.lat as double
    double west = points.min { it.lon as double }.lon as double
    double east = points.max { it.lon as double }.lon as double
    Closure<String> bbox = { double margin ->
        String.format(Locale.ROOT, '%.4f,%.4f,%.4f,%.4f', south - margin, west - margin, north + margin, east + margin)
    }
    String box = bbox(0.005)
    String query = """[out:json][timeout:90];
(
  nwr[tourism~"^(viewpoint|attraction|museum)\$"](${box});
  nwr[natural~"^(peak|beach|bay|cape|spring|cave_entrance)\$"][name](${box});
  nwr[man_made~"^(lighthouse|tower)\$"][name](${box});
  nwr[historic][name](${box});
  nwr[amenity~"^(place_of_worship|monastery)\$"][name](${box});
  nwr[waterway=waterfall](${box});
  node[place~"^(city|town|village|hamlet)\$"][name](${bbox(0.02)});
);
out center tags;"""
    if (cacheFile.exists()) {
        Map cached = new JsonSlurper().parse(cacheFile) as Map
        if (cached.query == query) {
            return cached.response as Map
        }
    }
    String body = 'data=' + URLEncoder.encode(query, 'UTF-8')
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    HttpResponse<String> response = null
    // The shared public server often answers 429 or 504 when busy; a short wait usually helps.
    for (int attempt = 0; attempt < 3; attempt++) {
        if (attempt > 0) {
            Thread.sleep(20000L * attempt)
        }
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create('https://overpass-api.de/api/interpreter'))
            .timeout(Duration.ofSeconds(120)).header('Content-Type', 'application/x-www-form-urlencoded')
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            break
        }
    }
    if (response.statusCode() != 200) {
        throw new RuntimeException("HTTP ${response.statusCode()}")
    }
    Map data = new JsonSlurper().parseText(response.body()) as Map
    cacheFile.parentFile.mkdirs()
    cacheFile.text = groovy.json.JsonOutput.toJson([query: query, response: data])
    data
}

// Distance in metres on a local flat projection - plenty for distances under a kilometre.
double nearbyDistanceM(double lat1, double lon1, double lat2, double lon2) {
    double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2.0))
    double y = Math.toRadians(lat2 - lat1)
    6371000.0 * Math.sqrt(x * x + y * y)
}

// The track point nearest to a position, with the distance off the track.
Map nearestTrackPoint(List<Map> points, double lat, double lon) {
    Map best = null
    double bestM = Double.MAX_VALUE
    for (Map p : points) {
        double m = nearbyDistanceM(lat, lon, p.lat as double, p.lon as double)
        if (m < bestM) {
            bestM = m
            best = p
        }
    }
    [point: best, offM: bestM]
}

// Stops of at least STOP_MIN_MINUTES, away from the first and last 200 m of the walk. GPS
// jitter and walking about at a stop break it into pieces, so pauses less than STOP_MERGE_M
// apart count as one stop.
@Field final double STOP_MERGE_M = 100.0

List<Map> findStops(List<Map> records) {
    List<Map> pauses = []
    int i = 0
    while (i < records.size()) {
        if (records[i].moving) {
            i++
            continue
        }
        int j = i
        while (j + 1 < records.size() && !records[j + 1].moving) {
            j++
        }
        Map last = pauses ? pauses[-1] : null
        if (last && (records[i].distance as double) - (records[last.to as int].distance as double) < STOP_MERGE_M) {
            last.to = j
        } else {
            pauses << [from: i, to: j]
        }
        i = j + 1
    }
    double endM = records[-1].distance as double
    List<Map> stops = []
    pauses.each { p ->
        Map first = records[p.from as int]
        double seconds = records.subList(p.from as int, (p.to as int) + 1).findAll { !it.moving }.sum { it.dtS as double } as double
        double at = first.distance as double
        Map where = records.subList(p.from as int, (p.to as int) + 1).find { it.lat != null }
        if (seconds / 60.0 >= STOP_MIN_MINUTES && at > 200.0 && at < endM - 200.0 && where) {
            stops << [distance: at, epochSecond: first.epochSecond, minutes: seconds / 60.0, lat: where.lat, lon: where.lon]
        }
    }
    stops
}

// Climbs and descents between turning points where the altitude turns back by at least
// LEG_REVERSAL_M, as [fromIndex, toIndex] into the profile.
List<List<Integer>> altitudeLegs(List<Double> altitudes) {
    List<List<Integer>> legs = []
    int turn = 0
    int extreme = 0
    int lowest = 0
    int highest = 0
    int dir = 0
    for (int i = 1; i < altitudes.size(); i++) {
        double a = altitudes[i]
        if (dir == 0) {
            lowest = a < altitudes[lowest] ? i : lowest
            highest = a > altitudes[highest] ? i : highest
            if (a - altitudes[lowest] >= LEG_REVERSAL_M) {
                dir = 1
                turn = lowest
                extreme = i
            } else if (altitudes[highest] - a >= LEG_REVERSAL_M) {
                dir = -1
                turn = highest
                extreme = i
            }
        } else if (dir * (a - altitudes[extreme]) > 0) {
            extreme = i
        } else if (dir * (altitudes[extreme] - a) >= LEG_REVERSAL_M) {
            legs << [turn, extreme]
            turn = extreme
            extreme = i
            dir = -dir
        }
    }
    if (dir != 0) {
        legs << [turn, extreme]
    }
    legs
}

// The feature's name in the language, if OpenStreetMap has one, otherwise its local name.
String featureName(Map tags, String language) {
    (tags["name:${language}"] ?: tags.name) as String
}

// Highlights of a walk: OpenStreetMap features passed (ranked by kind, with a bonus for a
// Wikipedia article and for stopping there), long stops, the route through settlements and
// the high point and longest climb and descent from the recorded altitude. The result is
// language-neutral; highlightLines words it.
Map routeHighlights(List<Map> records, Map osm) {
    // One point per 10 m walked, for the nearest-point searches.
    List<Map> points = []
    double next = 0.0
    records.each { r ->
        if (r.lat != null && (r.distance as double) >= next) {
            points << r
            next = (r.distance as double) + 10.0
        }
    }
    List<Map> features = []
    List<Map> places = []
    ((osm?.elements ?: []) as List<Map>).each { e ->
        Map tags = (e.tags ?: [:]) as Map
        Double lat = (e.lat ?: (e.center as Map)?.lat) as Double
        Double lon = (e.lon ?: (e.center as Map)?.lon) as Double
        if (lat == null || lon == null) {
            return
        }
        Map nearest = nearestTrackPoint(points, lat, lon)
        if (tags.place in ['city', 'town', 'village', 'hamlet']) {
            places << [tags: tags, place: tags.place, offM: nearest.offM, point: nearest.point, lat: lat, lon: lon]
            return
        }
        String kind = highlightKind(tags)
        if (kind == null || (!tags.name && !HIGHLIGHT_KINDS[kind].unnamed)) {
            return
        }
        // Inventory entries (e.g. the dry-stone huts "barraca de pedra seca 19111") aren't sights.
        if (tags.name ==~ /.*\d{3,}.*/) {
            return
        }
        int score = (HIGHLIGHT_KINDS[kind].score as int) + (tags.wikipedia || tags.wikidata ? 1 : 0)
        features << [tags: tags, kind: kind, score: score, offM: nearest.offM, point: nearest.point, lat: lat, lon: lon]
    }
    // The same place is often mapped twice (a castle's outline and a monument node): keep the
    // best-scoring of each name, then the nearest.
    List<Map> passed = features.findAll { (it.offM as double) <= (HIGHLIGHT_KINDS[it.kind].maxOffM as double) }
        .groupBy { (it.tags.name ?: "${it.kind}@${it.lat},${it.lon}").toString().toLowerCase() }
        .collect { name, same -> same.min { a, b -> (b.score as int) <=> (a.score as int) ?: (a.offM as double) <=> (b.offM as double) } }

    List<Map> stops = findStops(records)
    List<Map> items = []
    Set<Map> used = [] as Set
    stops.each { stop ->
        Map best = features.findAll { !used.contains(it) && nearbyDistanceM(stop.lat as double, stop.lon as double, it.lat as double, it.lon as double) <= STOP_MATCH_M }
            .max { (it.score as int) * 1000 - nearbyDistanceM(stop.lat as double, stop.lon as double, it.lat as double, it.lon as double) }
        if (best) {
            used << best
        }
        items << [distance: stop.distance, epochSecond: stop.epochSecond, feature: best, stopMinutes: stop.minutes]
    }
    Set<String> usedNames = used.collect { (it.tags.name ?: '').toString().toLowerCase() } as Set
    passed.findAll { !used.contains(it) && it.tags.name && !usedNames.contains(it.tags.name.toString().toLowerCase()) }
        .sort { a, b -> (b.score as int) <=> (a.score as int) ?: (a.offM as double) <=> (b.offM as double) }
        .take(Math.max(0, MAX_HIGHLIGHTS - items.size()))
        .each { items << [distance: it.point.distance, epochSecond: it.point.epochSecond, feature: it] }
    items.sort { it.distance as double }

    // Route through settlements: the nearest to the start and finish (within 2 km) and any
    // town or village passed through on the way.
    Closure<Map> nearestPlace = { Map at ->
        places.collect { [place: it, m: nearbyDistanceM(at.lat as double, at.lon as double, it.lat as double, it.lon as double)] }
            .findAll { (it.m as double) <= 2000.0 }.min { it.m as double }?.place
    }
    Map startPlace = nearestPlace(points[0])
    Map finishPlace = nearestPlace(points[-1])
    List<Map> via = places.findAll { it != startPlace && it != finishPlace && (it.offM as double) <= (it.place == 'hamlet' ? 150.0 : 300.0) }
        .sort { it.point.distance as double }
    List<Map> route = []
    ([startPlace] + via + [finishPlace]).findAll { it != null }.each { p ->
        if (!route || route[-1].tags.name != p.tags.name) {
            route << p
        }
    }

    // High point, and the longest climb and descent.
    Map profile = distanceProfile(records.collect { it.distance as double }, records.collect { it.altitude as double })
    List<Double> pd = profile.distances as List<Double>
    List<Double> pa = profile.altitudes as List<Double>
    int top = (0..<pa.size()).max { pa[it] }
    Map peakFeature = features.findAll { it.kind == 'peak' && nearbyDistanceM(it.lat as double, it.lon as double, it.point.lat as double, it.point.lon as double) <= 150.0 &&
        Math.abs((it.point.distance as double) - pd[top]) <= 300.0 }.min { it.offM as double }
    List<List<Integer>> legs = altitudeLegs(pa)
    Closure<Map> legInfo = { List<Integer> leg ->
        leg == null ? null : [change: pa[leg[1]] - pa[leg[0]], fromM: pd[leg[0]], toM: pd[leg[1]]]
    }
    [items: items, route: route,
     highPoint: [altitude: pa[top], distance: pd[top], feature: peakFeature],
     climb: legInfo(legs.findAll { pa[it[1]] > pa[it[0]] }.max { pa[it[1]] - pa[it[0]] }),
     descent: legInfo(legs.findAll { pa[it[1]] < pa[it[0]] }.max { pa[it[0]] - pa[it[1]] })]
}

// The highlights worded in a language, one line each.
List<String> highlightLines(Map highlights, String language) {
    List<String> lines = []
    if ((highlights.route as List).size() >= 2) {
        lines << trIn(language, 'hl.route', (highlights.route as List<Map>).collect { featureName(it.tags as Map, language) }.join(' – '))
    }
    (highlights.items as List<Map>).each { item ->
        List<String> parts = []
        Map f = item.feature as Map
        if (f) {
            String kind = trIn(language, "kind.${f.kind}")
            String name = featureName(f.tags as Map, language)
            String elevation = f.kind == 'peak' && (f.tags as Map).ele ? ', ' + unit(((f.tags as Map).ele as String).replace(',', '.') as double, 0, 'm', language) : ''
            parts << (name ? "${name}, ${kind}${elevation}" : kind.capitalize())
        }
        if (item.stopMinutes != null) {
            String stop = trIn(language, 'hl.stop', num(item.stopMinutes as double, 0, language))
            parts << (parts ? stop : stop.capitalize())
        }
        lines << trIn(language, 'hl.item', num((item.distance as double) / 1000.0, 1, language), clock(item.epochSecond as long, language), parts.join(' – '))
    }
    Map high = highlights.highPoint as Map
    String peakName = high.feature ? " (${featureName((high.feature as Map).tags as Map, language)})" : ''
    lines << trIn(language, 'hl.highPoint', unit(high.altitude as double, 0, 'm', language), num((high.distance as double) / 1000.0, 1, language), peakName)
    [climb: 'hl.climb', descent: 'hl.descent'].each { field, key ->
        Map leg = highlights[field] as Map
        if (leg) {
            double km = ((leg.toM as double) - (leg.fromM as double)) / 1000.0
            lines << trIn(language, key, unit(Math.abs(leg.change as double), 0, 'm', language), unit(km, 1, 'km', language),
                num((leg.fromM as double) / 1000.0, 1, language) + '–' + num((leg.toM as double) / 1000.0, 1, language),
                pct(Math.abs(leg.change as double) / (km * 1000.0) * 100.0, 0, language))
        }
    }
    lines
}

// A gradient band for display: 0–5% (0–5 % in Finnish), with + for climbs and − for descents.
String bandLabel(int bandIndex, Boolean climb = null) {
    List<Double> edges = [0.0] + GRADIENT_BAND_EDGES_PCT
    String sign = climb == null ? '' : (climb ? '+' : '−')
    String text = bandIndex < edges.size() - 1
        ? range(edges[bandIndex], edges[bandIndex + 1], 0, '%')
        : '>' + pct(edges[-1], 0)
    sign + text
}

// Writes draft highlight files in every language next to the FIT file, unless one exists
// already (with --redraft, unless it has been checked, i.e. its draft line removed).
void writeDraftHighlights(java.io.File fitFile, Map highlights, boolean redraft) {
    String stem = fitFile.name.replaceFirst(/(?i)\.fit$/, '')
    LANGUAGES.each { language ->
        String suffix = language == 'en' ? '.highlights.txt' : ".highlights.${language}.txt"
        java.io.File existing = matchingSibling(fitFile, suffix)
        if (existing && !(redraft && readWalkFile(existing, false)?.draft)) {
            return
        }
        java.io.File target = existing ?: new java.io.File(fitFile.absoluteFile.parentFile, stem + suffix)
        target.setText(([trIn(language, 'hl.draftHeader')] + highlightLines(highlights, language)).join('\n') + '\n', 'UTF-8')
        println tr('hl.written', target.name)
    }
}

// Highlights: the checked (or draft) file in the report language, after drafting one from
// OpenStreetMap and the recorded data where none exists yet.
void showHighlights(java.io.File file, List<Map> records, java.io.File mapsDir, String baseName, Options options) {
    if (!options.noHighlights) {
        List<Map> positioned = records.findAll { it.lat != null }
        Map osm = null
        try {
            osm = fetchOsmFeatures(positioned, new java.io.File(mapsDir, "${baseName}.osm-highlights.json"))
        } catch (Exception ex) {
            System.err.println(tr('hl.failed', ex.message))
        }
        Map highlights = routeHighlights(records, osm)
        if (osm != null) {
            writeDraftHighlights(file, highlights, options.redraft)
        } else if (!readWalkText(file, 'highlights', false)) {
            printLabelled('highlights', '(' + tr('draft') + ')', highlightLines(highlights, lang), true)
        }
    }
    Map highlightText = readWalkText(file, 'highlights', false)
    if (highlightText) {
        printLabelled('highlights', walkTextMarks(highlightText), highlightText.lines as List<String>, true)
    }
}

Map analyse(java.io.File file, Options options) {
    Map loaded = loadFit(file, !options.fitAltitude)
    List<Map> records = loaded.records as List<Map>
    Map session = loaded.session as Map
    Map zones = loaded.zones as Map
    java.io.File mapsDir = resolveMapsDir(file, options.mapsDir)
    String baseName = file.name.replaceFirst(/(?i)\.fit$/, '')
    Map summary = [name: file.name, date: Instant.ofEpochSecond(records[0].epochSecond as long).atZone(LOCAL_ZONE).toLocalDate()]

    long start = records[0].epochSecond as long
    long finish = records[-1].epochSecond as long

    // Correct the start and mark moving first: the highlights, shown near the top, need both.
    String correctionText = null
    if (!options.noTerrainStart) {
        try {
            Map correction = estimateStartError(records, new TerrainModel(new java.io.File(mapsDir, 'mdt05')))
            if (correction) {
                correctStart(records, correction)
                correctionText = tr('start.corrected', signed(correction.startError as double, 0) + NBSP + 'm', unit(correction.settleM as double, 0, 'm'))
            } else {
                correctionText = tr('start.notCorrected')
            }
        } catch (Exception ex) {
            System.err.println(tr('start.unavailable', ex.message))
        }
    }
    markMoving(records)

    printHeading(file.name, tr('walk', longDate(summary.date as LocalDate), clock(start), clock(finish)))
    printLine('file', file.name)
    Map source = loaded.altitudeSource as Map
    printLine('altitudeFrom', source.matched != null ? tr('altitude.gpx', num(source.matched as double, 0), num(source.total as double, 0)) : tr('altitude.fit'))
    printLine('walk', tr('walk', longDate(summary.date as LocalDate), clock(start), clock(finish)))
    Map notes = readWalkText(file, 'notes', true)
    if (notes) {
        printLabelled('howItFelt', walkTextMarks(notes), notes.lines as List<String>, false)
    }
    showHighlights(file, records, mapsDir, baseName, options)
    if (correctionText) {
        printLine('startCorrected', correctionText)
    }

    double movingHours = (records.findAll { it.moving }.sum { it.dtS as double } ?: 0.0) / 3600.0
    double distanceKm = (session.totalDistanceM ?: records[-1].distance) / 1000.0
    double elapsedHours = (finish - start) / 3600.0
    printLine('distance', tr('distance', unit(distanceKm, 2, 'km')))
    printLine('time', tr('time', formatHours(elapsedHours), formatHours(movingHours), unit(distanceKm / movingHours, 2, 'km/h'), formatHours(elapsedHours - movingHours)))
    summary.km = distanceKm
    summary.movingHours = movingHours
    summary.elapsedHours = elapsedHours

    List<Double> altitudes = records.collect { it.altitude as double }
    List<Double> distances = records.collect { it.distance as double }
    double ascent = 0.0
    double descent = 0.0
    hysteresisSteps(altitudes, options.thresholdM).each { step -> if ((step[2] as double) > 0) { ascent += step[2] as double } else { descent -= step[2] as double } }
    String watchTotal = session.totalAscentM != null ? tr('ascent.watchTotal', unit(session.totalAscentM as double, 0, 'm')) : ''
    printLine('ascentDescent', tr('ascent', unit(ascent, 0, 'm'), unit(descent, 0, 'm'), unit(options.thresholdM, 1, 'm'), watchTotal))
    printLine('altitudeRange', range(altitudes.min(), altitudes.max(), 0, 'm'))
    List<double[]> bands = gradientBands(altitudes, distances, options.thresholdM)
    printTable([tr('bands.gradient'), tr('bands.ascent'), tr('bands.descent')],
        (0..<bands.size()).collect { int i ->
            [bandLabel(i), unit(bands[i][0], 0, 'm') + ' (' + pct(ascent > 0 ? bands[i][0] / ascent * 100.0 : 0.0, 0).padLeft(4) + ')',
             unit(bands[i][1], 0, 'm') + ' (' + pct(descent > 0 ? bands[i][1] / descent * 100.0 : 0.0, 0).padLeft(4) + ')']
        })
    int steepFrom = gradientBandIndex(15.0)
    double steepUp = bands.drop(steepFrom).sum { it[0] } as double
    double steepDown = bands.drop(steepFrom).sum { it[1] } as double
    printText(tr('steep', pct(15.0, 0), unit(steepUp, 0, 'm'), unit(steepDown, 0, 'm')))
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
        Closure<String> bpm = { Object v -> v == null ? '–' : unit(v as double, 0, 'bpm') }
        printLine('heartRate', tr('heartRate', bpm(session.avgHeartRate), bpm(session.maxHeartRate), bpm(session.minHeartRate),
            bpm(meanMovingHr), pct(reservePct, 0), bpm(restingHr), bpm(maxHr)))
        printLine('netBeats', tr('netBeats', num(netBeats, 0), num(netBeats / distanceKm, 0), num(netBeats / (movingSeconds / 60.0), 0)))
        summary.meanHr = meanMovingHr
        summary.netBeats = netBeats
        if (zones.highBoundaries && zones.timeS) {
            List<Integer> highs = zones.highBoundaries as List<Integer>
            List<Float> times = zones.timeS as List<Float>
            double total = times.sum { (it ?: 0.0f) as double } as double
            List<String> parts = []
            for (int z = 0; z < times.size(); z++) {
                String zoneRange = z == 0 ? "<${highs[0]}" : (z < highs.size() ? "${highs[z - 1]}–${highs[z]}" : ">${highs[-1]}")
                parts << "Z${z} ${zoneRange} ${pct(total > 0 ? ((times[z] ?: 0.0f) as double) / total * 100.0 : 0.0, 0)}"
            }
            printLine('zones', tr('zones', parts.join(' | ')))
        }
        Map byGradient = heartRateByGradient(records, restingHr)
        printText(tr('byGradient.title', unit(HR_LAG_S, 0, 's'), bandLabel(0), num(byGradient.flatBeatsPerKm as double, 0)))
        List<Double> edges = [0.0] + GRADIENT_BAND_EDGES_PCT + [40.0]
        printTable([tr('bands.gradient'), tr('byGradient.km'), tr('byGradient.min'), tr('byGradient.meanHr'), tr('byGradient.beatsPerKm'),
                    tr('byGradient.relative'), tr('byGradient.minetti')],
            (byGradient.rows as List<Map>).collect { row ->
                int b = row.bandIndex as int
                double midPct = (edges[b] + edges[b + 1]) / 2.0 * ((row.climb as boolean) ? 1 : -1)
                [bandLabel(b, row.climb as boolean), num(row.km as double, 2), num(row.minutes as double, 0), num(row.meanHr as double, 0),
                 num(row.beatsPerKm as double, 0), num(row.relative as double, 2), num(minettiWalkingRelative(midPct / 100.0), 2)]
            })
        summary.hrByGradient = byGradient
    }

    // Cadence and steps.
    if (session.totalCycles) {
        long steps = (session.totalCycles as long) * 2
        List<Map> moving = records.findAll { it.moving && it.cadenceSpm != null }
        double meanCadence = moving ? (moving.sum { (it.cadenceSpm as double) * (it.dtS as double) } as double) / (moving.sum { it.dtS as double } as double) : Double.NaN
        printLine('steps', tr('steps', num(steps, 0), num(meanCadence, 0), unit(distanceKm * 1000.0 / steps, 2, 'm')))
        summary.steps = steps
    }

    // Energy and the watch's own effort figures.
    List<String> energy = []
    if (session.totalCalories != null) {
        energy << unit(session.totalCalories as double, 0, 'kcal')
        summary.kcal = session.totalCalories
    }
    if (session.avgMets != null) {
        energy << tr('energy.mets', num(session.avgMets as double, 1))
    }
    if (session.trainingLoad != null) {
        energy << tr('energy.load', num(session.trainingLoad as double, 0))
        summary.trainingLoad = session.trainingLoad
    }
    if (session.rpe != null) {
        energy << tr('energy.effort', num(session.rpe as double, 0), session.rpeEstimated ? tr('energy.estimated') : '')
        summary.rpe = session.rpe
    }
    if (energy) {
        printLine('energy', energy.join(', '))
    }

    // Weather: the watch's single value is the weather at the start; Open-Meteo along the route
    // gives the real range.
    if (session.weatherTemp != null) {
        String humidity = session.weatherHumidity != null ? tr('watchWeather.humidity', pct(session.weatherHumidity as double, 0)) : ''
        printLine('watchWeather', tr('watchWeather', unit(session.weatherTemp as double, 0, '°C'), humidity))
    }
    if (!options.noWeather) {
        try {
            String day = summary.date.toString()
            List<Map> weather = weatherAlongWalk(records, new java.io.File(mapsDir, "${baseName}.weather.${day}.json"))
            List<Map> valid = weather.findAll { it.temperature != null }
            if (valid) {
                Map hottest = valid.max { it.temperature as double }
                double meanTemp = (valid.sum { it.temperature as double } as double) / valid.size()
                printLine('weather', tr('weather', range(valid.min { it.temperature as double }.temperature as double, hottest.temperature as double, 0, '°C'),
                    unit(meanTemp, 0, '°C'), unit(hottest.temperature as double, 0, '°C'), clock(hottest.epochSecond as long),
                    unit(valid.max { (it.radiation ?: 0.0) as double }.radiation as double, 0, 'W/m²')))
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
                    printLine('sunshine', tr('sunshine', pct(sunny.size() * 100.0 / heat.size(), 0), unit(ThermalComfort.SUNNY_DNI_W_M2, 0, 'W/m²')))
                    summary.meanTemp = meanTemp
                }
                if (sunny) {
                    Map peak = sunny.max { it.sun as double }
                    printLine('feltHeat', tr('feltHeat', unit((sunny.sum { it.shade as double } as double) / sunny.size(), 0, '°C'),
                        unit((sunny.sum { it.sun as double } as double) / sunny.size(), 0, '°C'), unit(peak.sun as double, 0, '°C'),
                        clock((peak.sample as Map).epochSecond as long), tr('heat.' + ThermalComfort.heatStressCategory(peak.sun as double))))
                    summary.meanSunUtci = (sunny.sum { it.sun as double } as double) / sunny.size()
                }
            }
        } catch (Exception ex) {
            System.err.println(tr('weather.failed', ex.message))
        }
    }

    summary
}

// "(draft, unchecked, in Finnish) " and the like, for a note or highlights file.
String walkTextMarks(Map text) {
    List<String> marks = []
    if (text.draft) {
        marks << tr('draft')
    }
    if (text.fallback) {
        marks << tr('fallback')
    }
    marks ? '(' + marks.join(', ') + ') ' : ''
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
if (!(options.language in LANGUAGES)) {
    System.err.println("Unknown language '${options.language}': use one of ${LANGUAGES.join(', ')}")
    System.exit(2)
}
lang = options.language
// The report uses non-ASCII characters (°C, en dashes, hard spaces, Finnish letters), so write
// it as UTF-8 whatever the platform default.
System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, 'UTF-8'))
System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, 'UTF-8'))

List<Map> summaries = []
for (java.io.File file : options.files) {
    if (!file.exists()) {
        System.err.println(tr('input.notFound', file))
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
        [label: label, bandIndex: b, climb: row.climb, gradePct: mid, km: acc[0], factor: acc[1] / acc[0]]
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
        reportSection = 'curve'
        printHeading(tr('curve.title', summaries.count { it.hrByGradient }), tr('curve.title', summaries.count { it.hrByGradient }))
        printText(tr('curve.intro'))
        printTable([tr('bands.gradient'), tr('curve.mid'), 'km', tr('curve.factor'), tr('byGradient.minetti')],
            (curve.anchors as List<Map>).collect { a ->
                [bandLabel(a.bandIndex as int, a.climb as boolean), pct(a.gradePct as double, 1), num(a.km as double, 1),
                 num(a.factor as double, 2), num(minettiWalkingRelative((a.gradePct as double) / 100.0), 2)]
            })
        // For pasting into ElevationProfiler's code, so always in code notation.
        printText(tr('curve.anchors') + (curve.anchors as List<Map>).collect { String.format(Locale.ROOT, '[%.1f, %.2f]', it.gradePct as double, it.factor as double) }.join(', '))
        if (curve.heat) {
            Map h = curve.heat as Map
            printText(tr('curve.heat', pct(h.pctPerDegAt20 as double, 1), num(h.walks as double, 0), range(h.minTemp as double, h.maxTemp as double, 0, '°C')))
        }
    }

    reportSection = 'all'
    printHeading(tr('all.title'), tr('all.title'))
    Closure<String> orDash = { Object v, int decimals -> v == null ? '–' : num(v as double, decimals) }
    printTable([tr('all.date'), 'km', tr('all.up') + ' (m)', tr('all.down') + ' (m)', tr('all.steep') + ' (m)', tr('all.moving'), tr('all.hr'),
                tr('all.netBeats'), tr('all.beatsPerKm'), 'kcal', tr('all.load'), tr('all.effort'), tr('all.air') + ' (°C)', tr('all.sunUtci') + ' (°C)'],
        summaries.collect { s ->
            [shortDate(s.date as LocalDate), num(s.km as double, 1), num(s.ascent as double, 0), num(s.descent as double, 0),
             num(s.steepUp as double, 0) + '/' + num(s.steepDown as double, 0), formatHours(s.movingHours as double), orDash(s.meanHr, 0),
             orDash(s.netBeats, 0), s.netBeats != null ? num((s.netBeats as double) / (s.km as double), 0) : '–', orDash(s.kcal, 0),
             orDash(s.trainingLoad, 0), orDash(s.rpe, 0), orDash(s.meanTemp, 0), orDash(s.meanSunUtci, 0)]
        }, '')
}

if (summaries && !options.noReport) {
    java.io.File first = options.files.find { it.exists() }.absoluteFile
    // An explicit null check: Groovy counts a File that doesn't exist yet as false.
    java.io.File target = options.reportFile != null ? options.reportFile : new java.io.File(first.parentFile, lang == 'en' ? 'walk-report.md' : "walk-report.${lang}.md")
    target.setText(markdownReport(summaries.size()), 'UTF-8')
    println ''
    println tr('report.written', target.path)
}
