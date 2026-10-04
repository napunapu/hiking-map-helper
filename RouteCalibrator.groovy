#!/usr/bin/env groovy
@Grab('info.picocli:picocli:4.7.5')
@Grab('com.garmin:fit:21.176.0')
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import groovy.xml.XmlSlurper
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

@Command(
    name = 'RouteCalibrator',
    description = 'Compares a planned GPX route against a recorded real-world track (FIT or GPX) and suggests calibrated model coefficients.',
    mixinStandardHelpOptions = true,
    version = '1.0'
)
class Options {

    @Parameters(index = '0', description = 'Planned route GPX file')
    File plannedGpx

    @Parameters(index = '1', description = 'Recorded (actual) track: a FIT file (with its same-named GPX export next to it for full-precision altitude), or a GPX file with per-point <time>')
    File recordedTrack

    @Option(names = ['-c', '--osm-cache'], description = 'Path to a cached Overpass OSM JSON response for the planned route (default: auto-detected as maps/<plannedBaseName>.osm.json next to the planned GPX, or a sibling <plannedBaseName>.osm.json)')
    String osmCachePath

    @Option(names = ['-s', '--speed'], description = 'Baseline flat walking speed in km/h used by the terrain-adjusted prediction model, matching ElevationProfiler.groovy (default: 4.0)')
    double speedKmh = 4.0

    @Option(names = ['-t', '--temp'], description = 'Constant ambient temperature in degC for the optional thermal-penalty check - a simplification, since this diagnostic does not re-fetch per-point historic weather (default: 20.0)')
    double tempCelsius = 20.0

    @Option(names = ['--break'], description = 'Modelled rest-break cadence as interval:duration in minutes, compared against the actual observed pauses (default: 60:5)')
    String breakSpec = '60:5'

    @Option(names = ['--elevation'], description = 'Planned-route elevation source, as in ElevationProfiler.groovy: "terrain" (IGN MDT05 terrain model, Spain only; falls back to the GPX if unavailable) or "gpx" (default: terrain)')
    String elevationSource = 'terrain'

    @Option(names = ['--no-terrain-start'], description = 'Don\'t correct the recorded track\'s barometer start error against the IGN MDT05 terrain model')
    boolean noTerrainStart = false

    @Option(names = ['--fit-altitude'], description = 'Use a FIT file\'s own altitudes (0.2 m steps) even when a GPX export sits next to it')
    boolean fitAltitude = false
}

// ----- shared model functions, mirrored from ElevationProfiler.groovy for consistency -----

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

// Inserts points along each straight segment so that no two are more than stepM apart,
// interpolating position and the GPX's own elevation. A planned route's points can be 20-30 m
// apart, so without this, short climbs between them disappear from a terrain-model profile.
List<Map> densifyRoute(List<Map> points, double stepM) {
    List<Map> dense = [points[0].clone() as Map]
    for (int i = 1; i < points.size(); i++) {
        Map a = points[i - 1]
        Map b = points[i]
        double d = haversine(a.lat as double, a.lon as double, b.lat as double, b.lon as double)
        int steps = Math.max(1, (int) Math.ceil(d / stepM))
        for (int s = 1; s <= steps; s++) {
            double f = s / (double) steps
            dense << [
                lat: (a.lat as double) + ((b.lat as double) - (a.lat as double)) * f,
                lon: (a.lon as double) + ((b.lon as double) - (a.lon as double)) * f,
                ele: (a.ele as double) + ((b.ele as double) - (a.ele as double)) * f
            ]
        }
    }
    dense
}

List<Double> rollingMedian(List<Double> values, int half) {
    int n = values.size()
    List<Double> result = new ArrayList<>(n)
    for (int i = 0; i < n; i++) {
        List<Double> window = values.subList(Math.max(0, i - half), Math.min(n, i + half + 1)).sort(false)
        result << window[window.size().intdiv(2)]
    }
    result
}

// Replaces each point's elevation with the terrain model's, after densifying the route to
// the model's 5 m cell size. A 25 m median damps single-cell jumps where the route line
// runs along a cliff edge or crosses a removed bridge. Returns null (leaving the caller on
// the GPX elevations) if any point lies outside the model's coverage, since mixing two
// sources with different height references would put false steps in the profile.
List<Map> applyTerrainElevation(List<Map> points, TerrainModel terrain) {
    List<Map> dense = densifyRoute(points, 5.0)
    List<Double> heights = dense.collect { terrain.height(it.lat as double, it.lon as double) }
    if (heights.any { Double.isNaN(it) }) {
        return null
    }
    List<Double> damped = rollingMedian(heights, 2)
    for (int i = 0; i < dense.size(); i++) {
        dense[i].ele = damped[i]
    }
    dense
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
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    earthRadiusM * c
}

List<Double> movingAverage(List<Double> values, int windowSize) {
    int n = values.size()
    int half = Math.max(1, windowSize).intdiv(2)
    List<Double> result = new ArrayList<>(n)
    for (int i = 0; i < n; i++) {
        int from = Math.max(0, i - half)
        int to = Math.min(n - 1, i + half)
        double sum = 0.0
        int count = 0
        for (int j = from; j <= to; j++) {
            sum += values[j]
            count++
        }
        result << (sum / count)
    }
    result
}

// Tobler's hiking function: walking speed as a function of slope, peaking on a gentle
// -5% downhill and falling away on both steeper climbs and steeper descents.
double toblerSpeedKmh(double gradeFraction) {
    6.0 * Math.exp(-3.5 * Math.abs(gradeFraction + 0.05))
}

double slopeSpeedFactor(double gradeFraction) {
    toblerSpeedKmh(gradeFraction) / toblerSpeedKmh(0.0)
}

double terrainFactorForSurface(String surface) {
    String s = (surface ?: '').toLowerCase()
    Set<String> paved = ['asphalt', 'concrete', 'paved', 'paving_stones'] as Set
    Set<String> compact = ['compacted', 'fine_gravel', 'hard'] as Set
    Set<String> standardTrail = ['dirt', 'earth', 'ground', 'grass', 'path'] as Set
    Set<String> roughLoose = ['gravel', 'unpaved', 'stones', 'pebbles', 'rock'] as Set
    Set<String> severeLoose = ['scree', 'sand', 'boulders'] as Set

    if (paved.contains(s)) {
        return 1.0
    }
    if (compact.contains(s)) {
        return 1.1
    }
    if (standardTrail.contains(s)) {
        return 1.25
    }
    if (roughLoose.contains(s)) {
        return 1.5
    }
    if (severeLoose.contains(s)) {
        return 1.9
    }
    1.2
}

double technicalFactorForSacScale(String sacScale) {
    String s = (sacScale ?: '').toLowerCase()
    if (s in ['mountain_hiking', 't2']) {
        return 1.15
    }
    if (s in ['demanding_mountain_hiking', 't3']) {
        return 1.35
    }
    if (s in ['alpine_hiking', 't4', 'demanding_alpine_hiking', 't5', 'difficult_alpine_hiking', 't6']) {
        return 1.6
    }
    1.0
}

// Mirrors ElevationProfiler.groovy's inferSurfaceFromHighway, including its unpaved-trail
// fallback (track/path/footway/... default to 'ground' rather than 'unknown') - see TODO.md.
String inferSurfaceFromHighway(String highway) {
    Set<String> paved = ['residential', 'primary', 'secondary', 'tertiary', 'unclassified', 'living_street', 'service', 'trunk', 'motorway'] as Set
    Set<String> unpavedTrail = ['track', 'path', 'footway', 'bridleway', 'steps'] as Set
    if (highway && paved.contains(highway)) {
        return 'paved'
    }
    if (highway && unpavedTrail.contains(highway)) {
        return 'ground'
    }
    'unknown'
}

double pointToSegmentDistanceM(double plat, double plon, double alat, double alon, double blat, double blon) {
    double refLat = Math.toRadians((alat + blat) / 2.0)
    double kx = 111320.0 * Math.cos(refLat)
    double ky = 110540.0

    double ax = alon * kx
    double ay = alat * ky
    double bx = blon * kx
    double by = blat * ky
    double px = plon * kx
    double py = plat * ky

    double dx = bx - ax
    double dy = by - ay
    double lengthSq = dx * dx + dy * dy

    double t = lengthSq == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (py - ay) * dy) / lengthSq))
    double projX = ax + t * dx
    double projY = ay + t * dy
    double ddx = px - projX
    double ddy = py - projY
    Math.sqrt(ddx * ddx + ddy * ddy)
}

List<Map> parseOsmWays(Map osmData) {
    if (!osmData || !osmData.elements) {
        return []
    }
    (osmData.elements as List).findAll { el ->
        (el as Map).type == 'way' && (el as Map).geometry
    }.collect { el ->
        Map e = el as Map
        [
            geom: (e.geometry as List).collect { g -> [lat: (g as Map).lat as double, lon: (g as Map).lon as double] },
            tags: (e.tags ?: [:]) as Map
        ]
    }
}

// Grid index over every way segment, so snapping a point only checks segments in nearby
// cells rather than every segment in the bounding box - needed once a terrain-model route is
// densified to a point every 5 m. Segments keep their original way order, so the nearest
// match (first strictly closest) is the same as a full scan would find.
Map buildWaySegmentIndex(List<Map> ways, double cellDeg) {
    List<double[]> segments = []
    List<Map> segmentTags = []
    Map<Long, List<Integer>> cells = [:]
    for (way in ways) {
        List<Map> geom = way.geom as List<Map>
        for (int i = 1; i < geom.size(); i++) {
            double[] seg = [geom[i - 1].lat as double, geom[i - 1].lon as double, geom[i].lat as double, geom[i].lon as double] as double[]
            int segIndex = segments.size()
            segments << seg
            segmentTags << (way.tags as Map)
            long r0 = (long) Math.floor(Math.min(seg[0], seg[2]) / cellDeg)
            long r1 = (long) Math.floor(Math.max(seg[0], seg[2]) / cellDeg)
            long c0 = (long) Math.floor(Math.min(seg[1], seg[3]) / cellDeg)
            long c1 = (long) Math.floor(Math.max(seg[1], seg[3]) / cellDeg)
            for (long r = r0; r <= r1; r++) {
                for (long c = c0; c <= c1; c++) {
                    cells.computeIfAbsent(r * 10000000L + c) { [] } << segIndex
                }
            }
        }
    }
    [cellDeg: cellDeg, segments: segments, segmentTags: segmentTags, cells: cells]
}

Map nearestWayInfo(double plat, double plon, Map wayIndex, double thresholdM) {
    double latPad = thresholdM / 110540.0
    double lonPad = thresholdM / (111320.0 * Math.cos(Math.toRadians(plat)))
    double cellDeg = wayIndex.cellDeg as double
    Map<Long, List<Integer>> cells = wayIndex.cells as Map<Long, List<Integer>>
    List<double[]> segments = wayIndex.segments as List<double[]>

    // Candidate segments from every cell the point's search box touches, in original order.
    TreeSet<Integer> candidates = new TreeSet<>()
    for (long r = (long) Math.floor((plat - latPad) / cellDeg); r <= (long) Math.floor((plat + latPad) / cellDeg); r++) {
        for (long c = (long) Math.floor((plon - lonPad) / cellDeg); c <= (long) Math.floor((plon + lonPad) / cellDeg); c++) {
            List<Integer> cell = cells.get(r * 10000000L + c)
            if (cell) {
                candidates.addAll(cell)
            }
        }
    }

    double bestDist = Double.MAX_VALUE
    Map bestTags = null
    for (int segIndex : candidates) {
        double[] seg = segments[segIndex]
        double aLat = seg[0]
        double aLon = seg[1]
        double bLat = seg[2]
        double bLon = seg[3]

        if (Math.min(aLat, bLat) - latPad > plat || Math.max(aLat, bLat) + latPad < plat) {
            continue
        }
        if (Math.min(aLon, bLon) - lonPad > plon || Math.max(aLon, bLon) + lonPad < plon) {
            continue
        }

        double d = pointToSegmentDistanceM(plat, plon, aLat, aLon, bLat, bLon)
        if (d < bestDist) {
            bestDist = d
            bestTags = wayIndex.segmentTags[segIndex] as Map
        }
    }

    if (bestTags == null || bestDist > thresholdM) {
        return [surface: 'unknown', sacScale: 'none']
    }

    String highway = (bestTags.highway ?: 'unknown') as String
    String surface = bestTags.surface ? (bestTags.surface as String) : inferSurfaceFromHighway(highway)
    String sacScale = bestTags.sac_scale ? (bestTags.sac_scale as String) : 'none'
    [surface: surface, sacScale: sacScale]
}

double din33466Duration(double distanceKm, double ascentM, double descentM) {
    double horizontalHours = distanceKm / 4.0
    double verticalHours = (ascentM / 300.0) + (descentM / 500.0)
    double larger = Math.max(horizontalHours, verticalHours)
    double smaller = Math.min(horizontalHours, verticalHours)
    larger + (smaller / 2.0)
}

// ----- bracket classifiers -----

String gradientBracketFor(double gradePct) {
    if (gradePct < -15.0) {
        return 'Severe descent (< -15%)'
    }
    if (gradePct < -5.0) {
        return 'Moderate descent (-15% to -5%)'
    }
    if (gradePct <= 5.0) {
        return 'Flat / rolling (-5% to +5%)'
    }
    if (gradePct <= 15.0) {
        return 'Moderate climb (+5% to +15%)'
    }
    'Severe climb (> +15%)'
}

List<String> GRADIENT_BRACKET_ORDER = [
    'Severe descent (< -15%)', 'Moderate descent (-15% to -5%)', 'Flat / rolling (-5% to +5%)',
    'Moderate climb (+5% to +15%)', 'Severe climb (> +15%)'
]

String surfaceBracketFor(String surface) {
    String s = (surface ?: 'unknown').toLowerCase()
    Set<String> paved = ['asphalt', 'concrete', 'paved', 'paving_stones'] as Set
    Set<String> rough = ['gravel', 'unpaved', 'stones', 'pebbles', 'rock', 'scree', 'sand', 'boulders'] as Set
    if (paved.contains(s)) {
        return 'Paved'
    }
    if (rough.contains(s)) {
        return 'Rough/unpaved'
    }
    'Track/dirt'
}

List<String> SURFACE_BRACKET_ORDER = ['Paved', 'Track/dirt', 'Rough/unpaved']

// A coarser 3-way split used only by the hierarchical calibration pipeline: "Firm" widens the
// diagnostic report's "Paved" bucket to also include compacted/fine_gravel/hard, since those
// surfaces support the same confident, even foot-strike pace that the flat-speed and
// slope-response stages need to isolate cleanly from surface friction.
String firmnessBracketFor(String surface) {
    String s = (surface ?: 'unknown').toLowerCase()
    Set<String> firm = ['asphalt', 'concrete', 'paved', 'paving_stones', 'compacted', 'fine_gravel', 'hard'] as Set
    Set<String> rough = ['gravel', 'unpaved', 'stones', 'pebbles', 'rock', 'scree', 'sand', 'boulders'] as Set
    if (firm.contains(s)) {
        return 'Firm'
    }
    if (rough.contains(s)) {
        return 'Rough/loose'
    }
    'Standard trail'
}

List<String> FIRMNESS_BRACKET_ORDER = ['Firm', 'Standard trail', 'Rough/loose']

double median(List<Double> values) {
    if (values.isEmpty()) {
        return 0.0
    }
    List<Double> sorted = values.sort(false)
    int n = sorted.size()
    int mid = n.intdiv(2)
    (n % 2 == 0) ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid]
}

Map parseBreakSpec(String spec) {
    List<String> parts = spec.tokenize(':')
    if (parts.size() != 2) {
        throw new IllegalArgumentException("expected 'interval:duration', e.g. '60:5'")
    }
    int intervalMin = parts[0].trim() as int
    int durationMin = parts[1].trim() as int
    [intervalMin: intervalMin, durationMin: durationMin]
}

String formatDuration(double hours) {
    int totalMinutes = Math.round(hours * 60.0) as int
    int h = totalMinutes.intdiv(60)
    int m = totalMinutes % 60
    String.format(Locale.ROOT, '%dh %02dmin', h, m)
}

// ----- GPX parsing -----

List<Map> parsePlannedGpx(File file) {
    def gpx = new XmlSlurper(false, false).parse(file)
    List<Map> points = []
    gpx.trk.trkseg.trkpt.each { trkpt ->
        double lat = trkpt.@lat.text() as double
        double lon = trkpt.@lon.text() as double
        String eleText = trkpt.ele.text()
        double ele = eleText ? eleText as double : 0.0d
        points << [lat: lat, lon: lon, ele: ele]
    }
    points
}

List<Map> parseRecordedGpx(File file) {
    def gpx = new XmlSlurper(false, false).parse(file)
    List<Map> points = []
    gpx.trk.trkseg.trkpt.each { trkpt ->
        double lat = trkpt.@lat.text() as double
        double lon = trkpt.@lon.text() as double
        String eleText = trkpt.ele.text()
        double ele = eleText ? eleText as double : 0.0d
        String timeText = trkpt.time.text()
        Instant instant = timeText ? Instant.parse(timeText) : null
        if (instant) {
            points << [lat: lat, lon: lon, ele: ele, time: instant]
        }
    }
    points
}

// ----- recorded FIT tracks, mirrored from WalkAnalyser.groovy -----

// The GPX export with the same name as a FIT file. Apple Watch names contain a non-breaking
// space ("Apple\u00a0Watch") that a re-export or rename can turn into a normal one, so names
// are compared with all whitespace normalised.
File matchingGpx(File fitFile) {
    Closure<String> stem = { String name -> name.replaceFirst(/(?i)\.(fit|gpx)$/, '').replaceAll(/[\s\u00a0]+/, ' ') }
    String wanted = stem(fitFile.name)
    fitFile.absoluteFile.parentFile.listFiles()?.find { it.name.toLowerCase().endsWith('.gpx') && stem(it.name) == wanted }
}

// Loads a FIT file's positioned records as recorded points with the watch's own distance.
// Altitude comes from the same-named GPX export, matched by timestamp, when there is one: the
// FIT format rounds altitude to 0.2 m steps, which inflates ascent and steepens 1 Hz grades.
Map parseRecordedFit(File fitFile, boolean useGpxAltitude) {
    double semicircleToDeg = 180.0 / Math.pow(2, 31)
    List<Map> points = []
    Double lastDistance = 0.0d
    Decode decode = new Decode()
    MesgBroadcaster broadcaster = new MesgBroadcaster(decode)
    broadcaster.addListener({ RecordMesg m ->
        if (m.distance != null) {
            lastDistance = m.distance as double
        }
        Float altitude = m.enhancedAltitude ?: m.altitude
        if (m.positionLat != null && m.positionLong != null && altitude != null) {
            points << [lat: m.positionLat * semicircleToDeg, lon: m.positionLong * semicircleToDeg, ele: altitude as double,
                       time: m.timestamp.date.toInstant(), distance: lastDistance]
        }
    } as RecordMesgListener)
    fitFile.withInputStream { decode.read(it, broadcaster, broadcaster) }

    String altitudeSource = 'FIT (0.2 m steps)'
    File gpxFile = useGpxAltitude ? matchingGpx(fitFile) : null
    if (gpxFile) {
        Map<Long, Double> gpxAltitudes = [:]
        new XmlSlurper(false, false).parse(gpxFile).trk.trkseg.trkpt.each { trkpt ->
            if (trkpt.time.text() && trkpt.ele.text()) {
                gpxAltitudes[Instant.parse(trkpt.time.text()).epochSecond] = trkpt.ele.text() as double
            }
        }
        int matched = 0
        points.each { p ->
            Double gpxAltitude = gpxAltitudes[(p.time as Instant).epochSecond]
            if (gpxAltitude != null) {
                p.ele = gpxAltitude
                matched++
            }
        }
        if (matched > 0) {
            altitudeSource = "GPX export (${matched} of ${points.size()} records), FIT distance"
        }
    }
    [points: points, altitudeSource: altitudeSource]
}

// Median ignoring NaN (missing terrain), averaging the two middle values of an even count.
double nanMedian(List<Double> values) {
    List<Double> sorted = values.findAll { !Double.isNaN(it) }.sort(false)
    int n = sorted.size()
    if (n == 0) {
        return Double.NaN
    }
    n % 2 == 1 ? sorted[n.intdiv(2)] : (sorted[n.intdiv(2) - 1] + sorted[n.intdiv(2)]) / 2.0
}

List<Double> rollingNanMedian(List<Double> values, int half) {
    int n = values.size()
    List<Double> result = new ArrayList<>(n)
    for (int i = 0; i < n; i++) {
        result << nanMedian(values.subList(Math.max(0, i - half), Math.min(n, i + half + 1)))
    }
    result
}

double interpolateAt(List<Double> xs, List<Double> ys, double x) {
    if (x <= xs[0]) {
        return ys[0]
    }
    if (x >= xs[-1]) {
        return ys[-1]
    }
    int hi = Collections.binarySearch(xs, x)
    if (hi >= 0) {
        return ys[hi]
    }
    hi = -hi - 1
    double f = (x - xs[hi - 1]) / (xs[hi] - xs[hi - 1])
    ys[hi - 1] + (ys[hi] - ys[hi - 1]) * f
}

// The barometer start correction, with WalkAnalyser.groovy's rules (see estimateStartError
// there): the watch's steady offset from terrain is measured beyond the first 2 km, and until
// the watch first comes within 2 m of terrain plus that offset its altitude is replaced by that
// reference, blending back over the last 50 m. Points need lat, lon, ele and cumulative
// distance. Returns the correction applied, or null.
Map correctRecordedStart(List<Map> points, TerrainModel terrain) {
    double settledFromM = 2000.0
    double settledWithinM = 2.0
    if ((points[-1].distance as double) <= settledFromM) {
        return null
    }
    List<Map> sampled = []
    double nextDistance = 0.0
    for (Map p : points) {
        if ((p.distance as double) >= nextDistance) {
            sampled << p
            nextDistance = (p.distance as double) + 5.0
        }
    }
    List<Double> dist = sampled.collect { it.distance as double }
    List<Double> dem = rollingNanMedian(sampled.collect { terrain.height(it.lat as double, it.lon as double) }, 2)
    List<Double> watch = sampled.collect { it.ele as double }
    double settledOffset = nanMedian((0..<sampled.size()).findAll { dist[it] >= settledFromM }.collect { watch[it] - dem[it] })
    if (Double.isNaN(settledOffset) || Double.isNaN(dem[0])) {
        return null
    }
    List<Double> reference = dem.collect { it + settledOffset }
    List<Double> error = rollingNanMedian((0..<sampled.size()).collect { watch[it] - reference[it] }, 2)
    double startError = (points[0].ele as double) - reference[0]
    if (Math.abs(startError) < settledWithinM) {
        return null
    }
    Integer settleIndex = (0..<sampled.size()).find { dist[it] > 0 && dist[it] < settledFromM && !Double.isNaN(error[it]) && Math.abs(error[it]) < settledWithinM }
    if (settleIndex == null) {
        return null
    }
    double settleM = dist[settleIndex]
    double blend = Math.min(50.0, settleM)
    double blendFrom = settleM - blend
    for (Map p : points) {
        double d = p.distance as double
        if (d >= settleM) {
            break
        }
        double ref = interpolateAt(dist, reference, d)
        if (!Double.isNaN(ref)) {
            p.ele = d <= blendFrom ? ref : ref * (1 - (d - blendFrom) / blend) + (p.ele as double) * ((d - blendFrom) / blend)
        }
    }
    [startError: startError, settleM: settleM]
}

// ----- planned-route model (mirrors ElevationProfiler.groovy's terrain+thermal model) -----

// Builds the planned route's own per-point distance, smoothed elevation, windowed-baseline
// grade (matching ElevationProfiler's 10 m minimum baseline, to avoid GPS-cluster spikes),
// OSM-derived surface/SAC/eta/T-factor, and the model's predicted per-segment speed/time.
Map buildPlannedModel(List<Map> points, List<Map> osmWays, double speedKmh, double tempCelsius, double ascentThresholdM) {
    List<Double> smoothedEle = movingAverage(points.collect { it.ele as double }, 5)
    for (int i = 0; i < points.size(); i++) {
        points[i].smoothedEle = smoothedEle[i]
    }

    double cumulative = 0.0
    points[0].distance = 0.0
    for (int i = 1; i < points.size(); i++) {
        double d = haversine(points[i - 1].lat as double, points[i - 1].lon as double, points[i].lat as double, points[i].lon as double)
        cumulative += d
        points[i].distance = cumulative
    }

    double surfaceSnapThresholdM = 30.0
    Map wayIndex = buildWaySegmentIndex(osmWays, 0.001)
    for (int i = 0; i < points.size(); i++) {
        Map wayInfo = nearestWayInfo(points[i].lat as double, points[i].lon as double, wayIndex, surfaceSnapThresholdM)
        points[i].surface = wayInfo.surface
        points[i].sacScale = wayInfo.sacScale
        points[i].eta = terrainFactorForSurface(wayInfo.surface as String)
        points[i].tFactor = technicalFactorForSacScale(wayInfo.sacScale as String)
    }

    // Constant-temperature thermal factor: a simplification versus ElevationProfiler's live
    // per-segment weather simulation, since this diagnostic does not re-fetch historic
    // per-point radiation data. Still lets the "was thermal throttling too aggressive?"
    // check run, just without radiation's contribution.
    double effectiveHeat = tempCelsius
    double thermalFactor = Math.max(0.65, 1.0 - Math.max(0.0, effectiveHeat - 15.0) * 0.008)

    double minGradeBaselineM = 10.0
    double maxPlausibleGrade = 100.0
    double totalAscent = 0.0
    double totalDescent = 0.0
    double predictedCumTimeHours = 0.0
    double predictedCumTimeNoThermalHours = 0.0
    points[0].grade = 0.0
    points[0].predictedCumTimeHours = 0.0
    points[0].predictedCumTimeNoThermalHours = 0.0
    int gradeRef = 0

    // Ascent/descent hysteresis, as in ElevationProfiler.groovy: 0.5 m on a terrain-model
    // profile, 0 (every change summed) on the GPX's own elevations.
    double ascentReferenceEle = points[0].smoothedEle as double
    for (int i = 1; i < points.size(); i++) {
        double eleChange = (points[i].smoothedEle as double) - ascentReferenceEle
        if (Math.abs(eleChange) >= ascentThresholdM) {
            if (eleChange > 0) {
                totalAscent += eleChange
            } else {
                totalDescent += -eleChange
            }
            ascentReferenceEle = points[i].smoothedEle as double
        }

        while (gradeRef < i - 1 && (points[i].distance as double) - (points[gradeRef + 1].distance as double) >= minGradeBaselineM) {
            gradeRef++
        }
        double baselineDist = (points[i].distance as double) - (points[gradeRef].distance as double)
        double baselineEle = (points[i].smoothedEle as double) - (points[gradeRef].smoothedEle as double)
        double grade = baselineDist > 0 ? (baselineEle / baselineDist) * 100.0 : 0.0
        if (Math.abs(grade) > maxPlausibleGrade) {
            grade = Math.signum(grade) * maxPlausibleGrade
        }
        points[i].grade = grade

        double segDist = (points[i].distance as double) - (points[i - 1].distance as double)
        double segDistKm = segDist / 1000.0
        double eta = points[i].eta as double
        double tFactor = points[i].tFactor as double
        double slopeFactor = slopeSpeedFactor(grade / 100.0)
        double vSegEffort = speedKmh * slopeFactor / (eta * tFactor)
        double vSegThermal = vSegEffort * thermalFactor

        double tSegHoursNoThermal = segDistKm / vSegEffort
        double tSegHoursThermal = segDistKm / vSegThermal
        predictedCumTimeNoThermalHours += tSegHoursNoThermal
        predictedCumTimeHours += tSegHoursThermal

        points[i].predictedSpeedKmh = vSegThermal
        points[i].predictedSpeedNoThermalKmh = vSegEffort
        points[i].predictedCumTimeHours = predictedCumTimeHours
        points[i].predictedCumTimeNoThermalHours = predictedCumTimeNoThermalHours
    }

    double totalDistanceKm = cumulative / 1000.0
    [
        totalDistanceKm: totalDistanceKm, totalAscent: totalAscent, totalDescent: totalDescent,
        predictedTotalHours: predictedCumTimeHours, predictedTotalNoThermalHours: predictedCumTimeNoThermalHours,
        thermalFactor: thermalFactor
    ]
}

// ----- recorded-track metrics -----

// Computes per-segment distance/time/speed/gradient on the recorded track, classifies
// stationary pauses, and groups consecutive paused segments into discrete break events.
Map buildRecordedMetrics(List<Map> points) {
    List<Double> smoothedEle = movingAverage(points.collect { it.ele as double }, 5)
    for (int i = 0; i < points.size(); i++) {
        points[i].smoothedEle = smoothedEle[i]
    }

    // Distance is already set: the watch's own from a FIT file, or summed from positions for a
    // GPX (see assignRecordedDistances).
    double cumulative = points[-1].distance as double

    double totalMovingTimeHours = 0.0
    double totalPauseTimeHours = 0.0
    double cumMovingTimeHours = 0.0
    points[0].moving = false
    points[0].cumMovingTimeHours = 0.0
    points[0].grade = 0.0
    points[0].speedKmh = 0.0

    List<Map> breakEvents = []
    Map currentBreak = null

    for (int i = 1; i < points.size(); i++) {
        double segDist = (points[i].distance as double) - (points[i - 1].distance as double)
        double segTimeS = java.time.Duration.between(points[i - 1].time as Instant, points[i].time as Instant).toMillis() / 1000.0
        double speedKmh = segTimeS > 0 ? (segDist / segTimeS) * 3.6 : 0.0

        double deltaEle = (points[i].smoothedEle as double) - (points[i - 1].smoothedEle as double)
        double grade = segDist > 0.5 ? (deltaEle / segDist) * 100.0 : 0.0
        grade = Math.max(-100.0, Math.min(100.0, grade))

        boolean isPause = speedKmh < 0.8 || (segTimeS > 15.0 && segDist < 2.0)

        points[i].speedKmh = speedKmh
        points[i].grade = grade
        points[i].moving = !isPause

        if (isPause) {
            totalPauseTimeHours += segTimeS / 3600.0
            if (currentBreak == null) {
                currentBreak = [
                    startTime: points[i - 1].time as Instant,
                    distanceKm: (points[i - 1].distance as double) / 1000.0,
                    durationS: 0.0
                ]
            }
            currentBreak.durationS = (currentBreak.durationS as double) + segTimeS
        } else {
            totalMovingTimeHours += segTimeS / 3600.0
            if (currentBreak != null) {
                if ((currentBreak.durationS as double) >= 30.0) {
                    breakEvents << currentBreak
                }
                currentBreak = null
            }
        }

        cumMovingTimeHours += (isPause ? 0.0 : segTimeS / 3600.0)
        points[i].cumMovingTimeHours = cumMovingTimeHours
    }
    if (currentBreak != null && (currentBreak.durationS as double) >= 30.0) {
        breakEvents << currentBreak
    }

    double totalDistanceKm = cumulative / 1000.0
    double totalElapsedHours = java.time.Duration.between(points[0].time as Instant, points[-1].time as Instant).toMillis() / 3600000.0

    [
        totalDistanceKm: totalDistanceKm, totalMovingTimeHours: totalMovingTimeHours,
        totalPauseTimeHours: totalPauseTimeHours, totalElapsedHours: totalElapsedHours,
        breakEvents: breakEvents
    ]
}

// Cumulative distance from positions, for a recorded GPX without the watch's own distance.
void assignRecordedDistances(List<Map> points) {
    if (points.every { it.distance != null }) {
        return
    }
    double cumulative = 0.0
    points[0].distance = 0.0d
    for (int i = 1; i < points.size(); i++) {
        cumulative += haversine(points[i - 1].lat as double, points[i - 1].lon as double, points[i].lat as double, points[i].lon as double)
        points[i].distance = cumulative
    }
}

// Snaps each recorded point to the nearest point (by index) on the planned route, using a
// small local search window around the previous match (both tracks are ordered end-to-end,
// so this is far cheaper than a full search per point) with a full-route fallback if the
// local window doesn't find anything reasonably close.
void snapRecordedToPlanned(List<Map> recordedPoints, List<Map> plannedPoints) {
    int searchWindow = 80
    int lastIdx = 0
    int n = plannedPoints.size()

    for (rp in recordedPoints) {
        double rLat = rp.lat as double
        double rLon = rp.lon as double
        int from = Math.max(0, lastIdx - searchWindow)
        int to = Math.min(n - 1, lastIdx + searchWindow)
        double bestDist = Double.MAX_VALUE
        int bestIdx = lastIdx

        for (int j = from; j <= to; j++) {
            double d = haversine(rLat, rLon, plannedPoints[j].lat as double, plannedPoints[j].lon as double)
            if (d < bestDist) {
                bestDist = d
                bestIdx = j
            }
        }

        if (bestDist > 150.0) {
            for (int j = 0; j < n; j++) {
                double d = haversine(rLat, rLon, plannedPoints[j].lat as double, plannedPoints[j].lon as double)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = j
                }
            }
        }

        rp.plannedIdx = bestIdx
        rp.plannedDistanceM = plannedPoints[bestIdx].distance as double
        rp.snapDistanceM = bestDist
        lastIdx = bestIdx
    }
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

if (!options.plannedGpx.exists()) {
    System.err.println("Planned GPX file not found: ${options.plannedGpx}")
    System.exit(1)
}
if (!options.recordedTrack.exists()) {
    System.err.println("Recorded track not found: ${options.recordedTrack}")
    System.exit(1)
}

Map breakSpecParsed
try {
    breakSpecParsed = parseBreakSpec(options.breakSpec)
} catch (Exception ex) {
    System.err.println("Invalid --break '${options.breakSpec}' (${ex.message}); using 60:5.")
    breakSpecParsed = [intervalMin: 60, durationMin: 5]
}
double modelledBreakIntervalHours = (breakSpecParsed.intervalMin as int) / 60.0
double modelledBreakDurationHours = (breakSpecParsed.durationMin as int) / 60.0

println '=== RouteCalibrator ==='
println "Planned route  : ${options.plannedGpx}"
println "Recorded track : ${options.recordedTrack}"

// Locate the OSM cache: explicit override, else maps/<name>.osm.json (ElevationProfiler's
// convention), else a plain sibling <name>.osm.json next to the planned GPX.
String plannedBaseName = options.plannedGpx.name.replaceFirst(/(?i)\.gpx$/, '')
File osmCacheFile = null
if (options.osmCachePath) {
    osmCacheFile = new File(options.osmCachePath)
} else {
    File underMaps = new File(options.plannedGpx.absoluteFile.parentFile, "maps/${plannedBaseName}.osm.json")
    File sibling = new File(options.plannedGpx.absoluteFile.parentFile, "${plannedBaseName}.osm.json")
    osmCacheFile = underMaps.exists() ? underMaps : sibling
}

List<Map> osmWays = []
if (osmCacheFile.exists()) {
    Map osmData = new JsonSlurper().parse(osmCacheFile) as Map
    osmWays = parseOsmWays(osmData)
    println "OSM cache      : ${osmCacheFile.path} (${osmWays.size()} ways)"
} else {
    println "OSM cache      : not found (${osmCacheFile.path}) - surface/SAC brackets will show as unknown"
}

List<Map> plannedPoints = parsePlannedGpx(options.plannedGpx)
if (plannedPoints.size() < 2) {
    System.err.println('Planned GPX file must contain at least two track points.')
    System.exit(1)
}
// Planned-route elevation, as in ElevationProfiler.groovy: the terrain model by default,
// since a planned route's GPX elevations are usually smoothed map data.
File mapsDir = new File(options.plannedGpx.absoluteFile.parentFile, 'maps')
TerrainModel terrain = new TerrainModel(new File(mapsDir, 'mdt05'))
boolean plannedFromTerrain = false
if (options.elevationSource == 'terrain') {
    try {
        List<Map> terrainPoints = applyTerrainElevation(plannedPoints, terrain)
        if (terrainPoints) {
            plannedPoints = terrainPoints
            plannedFromTerrain = true
        } else {
            System.err.println('Planned route leaves the IGN MDT05 coverage (Spain only); using its GPX elevations.')
        }
    } catch (Exception ex) {
        System.err.println("IGN MDT05 terrain model unavailable (${ex.message}); using the planned GPX elevations.")
    }
}
println "Planned elevation: ${plannedFromTerrain ? 'IGN MDT05 terrain model (every 5 m)' : 'GPX file'}"
Map plannedModel = buildPlannedModel(plannedPoints, osmWays, options.speedKmh, options.tempCelsius, plannedFromTerrain ? 0.5 : 0.0)

List<Map> recordedPoints
if (options.recordedTrack.name.toLowerCase().endsWith('.fit')) {
    Map loaded = parseRecordedFit(options.recordedTrack, !options.fitAltitude)
    recordedPoints = loaded.points as List<Map>
    println "Recorded altitude: ${loaded.altitudeSource}"
} else {
    recordedPoints = parseRecordedGpx(options.recordedTrack)
}
if (recordedPoints.size() < 2) {
    System.err.println('Recorded track must contain at least two timestamped, positioned points.')
    System.exit(1)
}
assignRecordedDistances(recordedPoints)
if (!options.noTerrainStart) {
    try {
        Map correction = correctRecordedStart(recordedPoints, terrain)
        println correction
            ? String.format(Locale.ROOT, 'Start corrected: watch read %+.0f m against terrain, settling over %.0f m', correction.startError as double, correction.settleM as double)
            : 'Start checked against terrain: no settling error found, not corrected'
    } catch (Exception ex) {
        System.err.println("IGN MDT05 terrain model unavailable (${ex.message}); recorded start not corrected.")
    }
}
Map recordedModel = buildRecordedMetrics(recordedPoints)
snapRecordedToPlanned(recordedPoints, plannedPoints)

double dinDurationHours = din33466Duration(plannedModel.totalDistanceKm as double, plannedModel.totalAscent as double, plannedModel.totalDescent as double)

// ----- gradient- and surface-bracket comparison -----
// For each recorded MOVING segment, use the segment's OWN actual grade (so "predicted" and
// "actual" are compared at the same grade, isolating the model formula rather than
// conflating it with GPX-to-GPX route mismatch), and the snapped planned point's
// surface/eta/T-factor for the surface-derived components.
//
// A segment whose nearest planned-route point is still far away (beyond routeDeviationThresholdM)
// is not actually walking the planned route - most likely a real on-the-ground course change
// (a rerouted section, a shortcut, a detour around an obstacle). Attributing that segment's
// pace to the nearest-but-unrelated planned point's surface/eta would misrepresent the model's
// accuracy, so these segments are tallied separately and excluded from the bracket comparison.

double routeDeviationThresholdM = 40.0
double routeDeviationDistM = 0.0
double routeDeviationTimeS = 0.0

Map gradientActualDistM = [:].withDefault { 0.0 }
Map gradientActualTimeS = [:].withDefault { 0.0 }
Map gradientPredictedTimeS = [:].withDefault { 0.0 }

Map surfaceActualDistM = [:].withDefault { 0.0 }
Map surfaceActualTimeS = [:].withDefault { 0.0 }
Map surfacePredictedTimeS = [:].withDefault { 0.0 }
Map surfaceEtaSum = [:].withDefault { 0.0 }
Map surfaceEtaDistM = [:].withDefault { 0.0 }

for (int i = 1; i < recordedPoints.size(); i++) {
    Map rp = recordedPoints[i]
    if (!(rp.moving as boolean)) {
        continue
    }
    double segDist = (rp.distance as double) - (recordedPoints[i - 1].distance as double)
    double segTimeS = java.time.Duration.between(recordedPoints[i - 1].time as Instant, rp.time as Instant).toMillis() / 1000.0
    if (segDist <= 0 || segTimeS <= 0) {
        continue
    }

    if ((rp.snapDistanceM as double) > routeDeviationThresholdM) {
        routeDeviationDistM += segDist
        routeDeviationTimeS += segTimeS
        continue
    }

    double actualGrade = rp.grade as double
    int plannedIdx = rp.plannedIdx as int
    Map pp = plannedPoints[plannedIdx]
    double eta = pp.eta as double
    double tFactor = pp.tFactor as double
    String surface = pp.surface as String

    double slopeFactor = slopeSpeedFactor(actualGrade / 100.0)
    double predictedSpeedKmh = options.speedKmh * slopeFactor / (eta * tFactor) * (plannedModel.thermalFactor as double)
    double predictedTimeS = predictedSpeedKmh > 0 ? (segDist / 1000.0) / predictedSpeedKmh * 3600.0 : 0.0

    String gradBracket = gradientBracketFor(actualGrade)
    gradientActualDistM[gradBracket] = (gradientActualDistM[gradBracket] ?: 0.0) + segDist
    gradientActualTimeS[gradBracket] = (gradientActualTimeS[gradBracket] ?: 0.0) + segTimeS
    gradientPredictedTimeS[gradBracket] = (gradientPredictedTimeS[gradBracket] ?: 0.0) + predictedTimeS

    String surfBracket = surfaceBracketFor(surface)
    surfaceActualDistM[surfBracket] = (surfaceActualDistM[surfBracket] ?: 0.0) + segDist
    surfaceActualTimeS[surfBracket] = (surfaceActualTimeS[surfBracket] ?: 0.0) + segTimeS
    surfacePredictedTimeS[surfBracket] = (surfacePredictedTimeS[surfBracket] ?: 0.0) + predictedTimeS
    surfaceEtaSum[surfBracket] = (surfaceEtaSum[surfBracket] ?: 0.0) + eta * segDist
    surfaceEtaDistM[surfBracket] = (surfaceEtaDistM[surfBracket] ?: 0.0) + segDist
}

Closure<Double> avgSpeedKmh = { double distM, double timeS -> timeS > 0 ? (distM / 1000.0) / (timeS / 3600.0) : 0.0 }

// ----- km-bucket divergence analysis -----
// At each 1 km mark along the planned route, compares the model's predicted cumulative time
// (interpolated from the planned model) against the actual cumulative moving time (from the
// nearest recorded point whose snapped planned distance reaches that mark), then reports the
// buckets where that gap grew the most as the top overestimation sections.

double totalPlannedKm = plannedModel.totalDistanceKm as double
int bucketCount = Math.floor(totalPlannedKm) as int
List<Map> bucketRows = []

Closure<Double> predictedCumTimeAtDistanceM = { double distM ->
    if (distM <= 0) {
        return 0.0
    }
    for (int i = 1; i < plannedPoints.size(); i++) {
        if ((plannedPoints[i].distance as double) >= distM) {
            Map a = plannedPoints[i - 1]
            Map b = plannedPoints[i]
            double aDist = a.distance as double
            double bDist = b.distance as double
            double frac = bDist > aDist ? (distM - aDist) / (bDist - aDist) : 0.0
            double aTime = a.predictedCumTimeHours as double
            double bTime = b.predictedCumTimeHours as double
            return aTime + (bTime - aTime) * frac
        }
    }
    plannedPoints[-1].predictedCumTimeHours as double
}

Closure<Double> actualCumMovingTimeAtDistanceM = { double distM ->
    for (rp in recordedPoints) {
        if ((rp.plannedDistanceM as double) >= distM) {
            return rp.cumMovingTimeHours as double
        }
    }
    recordedPoints[-1].cumMovingTimeHours as double
}

Closure<String> dominantGradientBracketInRange = { double fromM, double toM ->
    Map<String, Double> tally = [:].withDefault { 0.0 }
    for (pp in plannedPoints) {
        double d = pp.distance as double
        if (d >= fromM && d <= toM) {
            tally[gradientBracketFor(pp.grade as double)] = (tally[gradientBracketFor(pp.grade as double)] ?: 0.0) + 1
        }
    }
    tally.isEmpty() ? 'n/a' : tally.max { it.value }.key
}

Closure<String> dominantSurfaceBracketInRange = { double fromM, double toM ->
    Map<String, Double> tally = [:].withDefault { 0.0 }
    for (pp in plannedPoints) {
        double d = pp.distance as double
        if (d >= fromM && d <= toM) {
            String b = surfaceBracketFor(pp.surface as String)
            tally[b] = (tally[b] ?: 0.0) + 1
        }
    }
    tally.isEmpty() ? 'n/a' : tally.max { it.value }.key
}

for (int km = 0; km < bucketCount; km++) {
    double fromM = km * 1000.0
    double toM = (km + 1) * 1000.0
    double predictedFrom = predictedCumTimeAtDistanceM(fromM)
    double predictedTo = predictedCumTimeAtDistanceM(toM)
    double actualFrom = actualCumMovingTimeAtDistanceM(fromM)
    double actualTo = actualCumMovingTimeAtDistanceM(toM)
    double deltaMinutes = ((predictedTo - predictedFrom) - (actualTo - actualFrom)) * 60.0
    bucketRows << [
        km: km, deltaMinutes: deltaMinutes,
        gradeBracket: dominantGradientBracketInRange(fromM, toM),
        surfaceBracket: dominantSurfaceBracketInRange(fromM, toM)
    ]
}

List<Map> topOverestimations = bucketRows.sort { -(it.deltaMinutes as double) }.take(3)

// ----- console report -----

println ''
println '--- Route match ---'
println String.format(Locale.ROOT, 'Planned route distance  : %.2f km', plannedModel.totalDistanceKm as double)
println String.format(Locale.ROOT, 'Recorded track distance : %.2f km', recordedModel.totalDistanceKm as double)
if (routeDeviationDistM > 0) {
    println String.format(Locale.ROOT, 'Route deviation         : %.2f km (%.0f min moving) of the recorded track lies more than %.0f m from the planned route - likely a real course change on the ground, not a model error - and was excluded from the bracket comparison below.', routeDeviationDistM / 1000.0, routeDeviationTimeS / 60.0, routeDeviationThresholdM)
} else {
    println 'Route deviation         : none detected (recorded track tracks the planned route closely throughout)'
}

println ''
println '--- Moving time ---'
println String.format(Locale.ROOT, 'Actual moving time            : %s', formatDuration(recordedModel.totalMovingTimeHours as double))
println String.format(Locale.ROOT, 'DIN 33466 predicted           : %s', formatDuration(dinDurationHours))
println String.format(Locale.ROOT, 'Terrain/thermal model predicted: %s', formatDuration(plannedModel.predictedTotalHours as double))
double movingDeltaPct = (recordedModel.totalMovingTimeHours as double) > 0
    ? (((plannedModel.predictedTotalHours as double) - (recordedModel.totalMovingTimeHours as double)) / (recordedModel.totalMovingTimeHours as double)) * 100.0
    : 0.0
println String.format(Locale.ROOT, 'Model vs actual                : %+.1f%%', movingDeltaPct)

println ''
println '--- Pause time ---'
List<Map> actualBreaks = recordedModel.breakEvents as List<Map>
println String.format(Locale.ROOT, 'Actual pause time    : %s (%d observed break%s)', formatDuration(recordedModel.totalPauseTimeHours as double), actualBreaks.size(), actualBreaks.size() == 1 ? '' : 's')
int modelledBreakCount = modelledBreakIntervalHours > 0 ? Math.floor((plannedModel.predictedTotalHours as double) / modelledBreakIntervalHours) as int : 0
double modelledPauseHours = modelledBreakCount * modelledBreakDurationHours
println String.format(Locale.ROOT, 'Modelled pause time  : %s (--break %s, %d break%s)', formatDuration(modelledPauseHours), options.breakSpec, modelledBreakCount, modelledBreakCount == 1 ? '' : 's')
if (!actualBreaks.isEmpty()) {
    println 'Observed break intervals:'
    actualBreaks.each { brk ->
        println String.format(Locale.ROOT, '  - km %.2f, %s, %.0f min', brk.distanceKm as double, brk.startTime as String, (brk.durationS as double) / 60.0)
    }
}

println ''
println '--- Speed by gradient bracket (actual vs predicted) ---'
GRADIENT_BRACKET_ORDER.each { bracket ->
    double distM = gradientActualDistM[bracket] ?: 0.0
    if (distM <= 0) {
        return
    }
    double actual = avgSpeedKmh(distM, gradientActualTimeS[bracket] ?: 0.0)
    double predicted = avgSpeedKmh(distM, gradientPredictedTimeS[bracket] ?: 0.0)
    println String.format(Locale.ROOT, '%-32s actual %5.1f km/h | predicted %5.1f km/h | %.1f km', bracket, actual, predicted, distM / 1000.0)
}

println ''
println '--- Speed by surface bracket (actual vs predicted) ---'
SURFACE_BRACKET_ORDER.each { bracket ->
    double distM = surfaceActualDistM[bracket] ?: 0.0
    if (distM <= 0) {
        return
    }
    double actual = avgSpeedKmh(distM, surfaceActualTimeS[bracket] ?: 0.0)
    double predicted = avgSpeedKmh(distM, surfacePredictedTimeS[bracket] ?: 0.0)
    println String.format(Locale.ROOT, '%-14s actual %5.1f km/h | predicted %5.1f km/h | %.1f km', bracket, actual, predicted, distM / 1000.0)
}

println ''
println '--- Top 3 sections responsible for the largest time overestimations ---'
topOverestimations.each { row ->
    println String.format(Locale.ROOT, 'km %2d-%2d | grade: %-30s | surface: %-12s | model overestimates by %.1f min',
        row.km as int, (row.km as int) + 1, row.gradeBracket, row.surfaceBracket, row.deltaMinutes as double)
}

println ''
println '--- Compounding-error checks ---'
double severeDescentActual = avgSpeedKmh(gradientActualDistM['Severe descent (< -15%)'] ?: 0.0, gradientActualTimeS['Severe descent (< -15%)'] ?: 0.0)
double severeDescentPredicted = avgSpeedKmh(gradientActualDistM['Severe descent (< -15%)'] ?: 0.0, gradientPredictedTimeS['Severe descent (< -15%)'] ?: 0.0)
if (severeDescentActual > 0 && severeDescentPredicted > 0 && severeDescentActual - severeDescentPredicted > 1.0) {
    println String.format(Locale.ROOT, '- Downhill speeds look over-penalised: model predicts %.1f km/h on severe descents, actual was %.1f km/h.', severeDescentPredicted, severeDescentActual)
} else {
    println '- Downhill speed penalty looks reasonably aligned with observed severe-descent pace.'
}

double flatActual = avgSpeedKmh(gradientActualDistM['Flat / rolling (-5% to +5%)'] ?: 0.0, gradientActualTimeS['Flat / rolling (-5% to +5%)'] ?: 0.0)
double flatPredicted = avgSpeedKmh(gradientActualDistM['Flat / rolling (-5% to +5%)'] ?: 0.0, gradientPredictedTimeS['Flat / rolling (-5% to +5%)'] ?: 0.0)
double flatDistM = gradientActualDistM['Flat / rolling (-5% to +5%)'] ?: 0.0
double flatPredictedNoThermalTimeS = (plannedModel.thermalFactor as double) > 0 ? (flatDistM > 0 ? (gradientPredictedTimeS['Flat / rolling (-5% to +5%)'] ?: 0.0) * (plannedModel.thermalFactor as double) : 0.0) : 0.0
double flatPredictedNoThermal = avgSpeedKmh(flatDistM, flatPredictedNoThermalTimeS)
if (flatActual > 0 && flatPredicted > 0 && Math.abs(flatActual - flatPredictedNoThermal) < Math.abs(flatActual - flatPredicted) - 0.2) {
    println String.format(Locale.ROOT, '- Thermal/radiation scalar (x%.3f at a constant %.0f degC) looks too aggressive on flat ground: without it, predicted %.1f km/h is closer to actual %.1f km/h than the thermally-adjusted %.1f km/h.', plannedModel.thermalFactor as double, options.tempCelsius, flatPredictedNoThermal, flatActual, flatPredicted)
} else {
    println String.format(Locale.ROOT, '- Thermal/radiation scalar (x%.3f at a constant %.0f degC) does not appear to be the dominant source of error on flat ground.', plannedModel.thermalFactor as double, options.tempCelsius)
}

List<Map> lowFloorPoints = plannedPoints.findAll { (it.predictedSpeedKmh ?: options.speedKmh) as double < 2.5 }
if (!lowFloorPoints.isEmpty()) {
    double worstEtaTFactor = lowFloorPoints.collect { (it.eta as double) * (it.tFactor as double) }.max()
    println String.format(Locale.ROOT, '- %d planned points predict a floor speed below 2.5 km/h (worst eta x T-factor = %.2f) - eta/T-factor combination may be too punishing.', lowFloorPoints.size(), worstEtaTFactor)
} else {
    println '- No planned points predict an unrealistically low (< 2.5 km/h) floor speed.'
}

// ----- hierarchical 3-stage residual calibration -----
// Each stage is locked in turn against matched, on-route, moving segments only (excluding the
// route deviation and stationary pauses detected above), so later stages solve residuals
// against an already-fixed earlier stage instead of three independent heuristics compounding
// multiplicatively - which is what previously drove the calibrated prediction from +36.3% past
// zero to -19.8% instead of converging.

println ''
println '--- Hierarchical calibration (3-stage residual solver) ---'

// Shared dataset for all three stages: (grade, speed, firmness) for every matched, on-route,
// moving recorded segment.
List<Map> onRouteSamples = []
for (int i = 1; i < recordedPoints.size(); i++) {
    Map rp = recordedPoints[i]
    if (!(rp.moving as boolean) || (rp.snapDistanceM as double) > routeDeviationThresholdM) {
        continue
    }
    double segDist = (rp.distance as double) - (recordedPoints[i - 1].distance as double)
    double segTimeS = java.time.Duration.between(recordedPoints[i - 1].time as Instant, rp.time as Instant).toMillis() / 1000.0
    if (segDist <= 0 || segTimeS <= 0) {
        continue
    }
    Map pp = plannedPoints[rp.plannedIdx as int]
    onRouteSamples << [
        grade: rp.grade as double, speedKmh: (segDist / 1000.0) / (segTimeS / 3600.0),
        firmness: firmnessBracketFor(pp.surface as String)
    ]
}

// Stage 1: lock the baseline flat speed on firm terrain (-3% to +3% grade). This is the one
// number every later stage is expressed relative to.
List<Double> flatFirmSpeeds = onRouteSamples.findAll {
    it.firmness == 'Firm' && (it.grade as double) >= -3.0 && (it.grade as double) <= 3.0
}.collect { it.speedKmh as double }
double vBaseCalibrated = flatFirmSpeeds.isEmpty() ? options.speedKmh : median(flatFirmSpeeds)

println ''
println 'Step 1 - baseline flat speed (firm terrain, -3% to +3% grade):'
println String.format(Locale.ROOT, '  %d matching segment(s) -> calibrated v_base = %.2f km/h (raw model used %.2f km/h)', flatFirmSpeeds.size(), vBaseCalibrated, options.speedKmh)

// Stage 2: slope response and descent ceiling, still firm terrain only, v_base now fixed.
double descentSpeedCeilingKmh = 5.2

Closure<Map> lockSlopeAnchor = { String label, Closure<Boolean> gradeFilter ->
    List<Map> matches = onRouteSamples.findAll { it.firmness == 'Firm' && gradeFilter(it.grade as double) }
    if (matches.isEmpty()) {
        return null
    }
    [
        label: label, grade: median(matches.collect { it.grade as double }),
        speedKmh: median(matches.collect { it.speedKmh as double }), sampleCount: matches.size()
    ]
}

Map severeDescentAnchor = lockSlopeAnchor('Severe descent', { double g -> g < -15.0 })
Map moderateDescentAnchor = lockSlopeAnchor('Moderate descent', { double g -> g >= -15.0 && g < -5.0 })
Map moderateClimbAnchor = lockSlopeAnchor('Moderate climb', { double g -> g > 5.0 && g <= 15.0 })
Map severeClimbAnchor = lockSlopeAnchor('Severe climb', { double g -> g > 15.0 })

// A hiker's downhill cadence does not keep accelerating with grade - foot placement and
// control, not metabolic cost, become the limiting factor. Enforce a hard walking-speed
// ceiling on the descent anchors so the calibrated curve reflects that physical limit rather
// than whatever an occasionally sparse sample median happened to show.
[severeDescentAnchor, moderateDescentAnchor].each { anchor ->
    if (anchor != null && (anchor.speedKmh as double) > descentSpeedCeilingKmh) {
        anchor.speedKmh = descentSpeedCeilingKmh
    }
}

println ''
println String.format(Locale.ROOT, 'Step 2 - slope response on firm terrain (descent speed ceiling: %.1f km/h):', descentSpeedCeilingKmh)
[severeDescentAnchor, moderateDescentAnchor, moderateClimbAnchor, severeClimbAnchor].each { anchor ->
    if (anchor == null) {
        return
    }
    double rawPredictedSpeed = options.speedKmh * slopeSpeedFactor((anchor.grade as double) / 100.0)
    double dampingFactor = rawPredictedSpeed > 0 ? (anchor.speedKmh as double) / rawPredictedSpeed : 1.0
    println String.format(Locale.ROOT, '  %-16s grade %+5.1f%% -> calibrated %.2f km/h (%d samples, x%.2f vs raw Tobler-based model)',
        anchor.label, anchor.grade as double, anchor.speedKmh as double, anchor.sampleCount as int, dampingFactor)
}

// Anchor points for the calibrated slope-response curve, as (grade %, speed factor relative
// to v_base), sorted by grade and pinned flat at (0, 1.0) since v_base is that point by
// construction. Each bracket contributes exactly one anchor - its calibrated one where firm
// samples exist, otherwise the theoretical Tobler ratio at a representative grade - so the
// fallback and calibrated values for the same bracket never both end up in the curve.
Closure<Map> slopeAnchorFor = { double fallbackGrade, Map anchor ->
    (anchor != null && vBaseCalibrated > 0)
        ? [grade: anchor.grade as double, factor: (anchor.speedKmh as double) / vBaseCalibrated]
        : [grade: fallbackGrade, factor: slopeSpeedFactor(fallbackGrade / 100.0)]
}
List<Map> slopeAnchors = [
    slopeAnchorFor(-20.0, severeDescentAnchor), slopeAnchorFor(-10.0, moderateDescentAnchor),
    [grade: 0.0, factor: 1.0],
    slopeAnchorFor(10.0, moderateClimbAnchor), slopeAnchorFor(20.0, severeClimbAnchor)
].sort { it.grade as double }

Closure<Double> calibratedSlopeFactor = { double grade ->
    if (grade <= (slopeAnchors[0].grade as double)) {
        return slopeAnchors[0].factor as double
    }
    if (grade >= (slopeAnchors[-1].grade as double)) {
        return slopeAnchors[-1].factor as double
    }
    for (int i = 1; i < slopeAnchors.size(); i++) {
        double g1 = slopeAnchors[i].grade as double
        if (grade <= g1) {
            double g0 = slopeAnchors[i - 1].grade as double
            double f0 = slopeAnchors[i - 1].factor as double
            double f1 = slopeAnchors[i].factor as double
            double frac = g1 > g0 ? (grade - g0) / (g1 - g0) : 0.0
            return f0 + (f1 - f0) * frac
        }
    }
    slopeAnchors[-1].factor as double
}

// Stage 3: surface friction (eta) as a pure residual - v_base and the slope curve are now
// locked, so whatever gap remains on non-firm terrain is attributed entirely to surface eta.
println ''
println 'Step 3 - residual surface friction (eta), v_base and slope curve now locked:'
Map etaCalibrated = [Firm: 1.0]
['Standard trail', 'Rough/loose'].each { firmness ->
    List<Map> samples = onRouteSamples.findAll { it.firmness == firmness }
    if (samples.isEmpty()) {
        etaCalibrated[firmness] = 1.2
        println String.format(Locale.ROOT, '  %-14s no matching on-route segments - keeping a default eta of 1.20', firmness)
        return
    }
    List<Double> ratios = samples.collect { Map sample ->
        double expectedFirmSpeed = vBaseCalibrated * calibratedSlopeFactor(sample.grade as double)
        double actualSpeed = sample.speedKmh as double
        actualSpeed > 0 ? expectedFirmSpeed / actualSpeed : 1.0
    }
    double rawEta = median(ratios)
    double clampedEta = Math.max(1.05, Math.min(1.60, rawEta))
    etaCalibrated[firmness] = clampedEta
    double meanActualSpeed = (samples.collect { it.speedKmh as double }.sum() as double) / samples.size()
    String clampNote = Math.abs(rawEta - clampedEta) > 0.001 ? String.format(Locale.ROOT, ' (clamped from %.2f)', rawEta) : ''
    println String.format(Locale.ROOT, '  %-14s %d samples, mean actual %.1f km/h -> eta_calibrated = %.2f%s', firmness, samples.size(), meanActualSpeed, clampedEta, clampNote)
}
println String.format(Locale.ROOT, '  %-14s locked as the step 1/2 reference surface -> eta_calibrated = %.2f', 'Firm', etaCalibrated['Firm'] as double)

// ----- verification: rebuild the full-route prediction from the locked parameter set -----
// v_base is measured from the actual recorded day, so it already embeds whatever
// thermal/fatigue conditions applied then - reapplying the constant-temperature thermal
// scalar on top would double-count that effect, so it is intentionally left out here (unlike
// the raw/uncalibrated model above, which uses the theoretical -s speed and still needs it).
double calibratedTotalHours = 0.0
for (int i = 1; i < plannedPoints.size(); i++) {
    Map pp = plannedPoints[i]
    Map prevPp = plannedPoints[i - 1]
    double segDistKm = ((pp.distance as double) - (prevPp.distance as double)) / 1000.0
    double eta = etaCalibrated[firmnessBracketFor(pp.surface as String)] as double
    double tFactor = pp.tFactor as double
    double vSeg = vBaseCalibrated * calibratedSlopeFactor(pp.grade as double) / (eta * tFactor)
    calibratedTotalHours += vSeg > 0 ? segDistKm / vSeg : 0.0
}
double calibratedDeltaPct = (recordedModel.totalMovingTimeHours as double) > 0
    ? ((calibratedTotalHours - (recordedModel.totalMovingTimeHours as double)) / (recordedModel.totalMovingTimeHours as double)) * 100.0
    : 0.0

println ''
println '--- Moving time comparison ---'
println String.format(Locale.ROOT, '%-28s %s', 'Actual moving time:', formatDuration(recordedModel.totalMovingTimeHours as double))
println String.format(Locale.ROOT, '%-28s %s (%+.1f%%)', 'Raw uncalibrated prediction:', formatDuration(plannedModel.predictedTotalHours as double), movingDeltaPct)
println String.format(Locale.ROOT, '%-28s %s (%+.1f%%)', 'Hierarchically calibrated:', formatDuration(calibratedTotalHours), calibratedDeltaPct)
println (Math.abs(calibratedDeltaPct) <= 5.0
    ? 'Within the target +/-5% band.'
    : 'Still outside the target +/-5% band - consider further manual tuning of the brackets above.')
