#!/usr/bin/env groovy
@Grab('info.picocli:picocli:4.7.5')
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import groovy.xml.XmlSlurper
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Command(
    name = 'ElevationProfiler',
    description = 'Parses a GPX track and generates a colour-coded elevation profile.',
    mixinStandardHelpOptions = true,
    version = '1.0'
)
class Options {

    @Parameters(index = '0', description = 'Input GPX file')
    File gpxFile

    @Option(names = ['-w', '--window'], description = 'Moving-average smoothing window size in points (default: 5)')
    int window = 5

    @Option(names = ['-o', '--output'], description = 'Output HTML file path (default: <input>-profile.html)')
    String outputPath

    @Option(names = ['-t', '--temp'], description = 'Forecast max ambient temperature in shade, in degC (default: 20.0)')
    double tempCelsius = 20.0

    @Option(names = ['-e', '--exposure'], description = 'Route shading factor: 1.0 = forest/partial shade, 1.1 = fully exposed ridges (default: 1.0)')
    double exposureFactor = 1.0

    @Option(names = ['--no-cache'], description = 'Force re-querying the Overpass API even if a cached OpenStreetMap response exists')
    boolean noCache = false

    @Option(names = ['-s', '--speed'], description = 'Base flat walking speed in km/h, used for the effort-adjusted duration model (default: 4.59, calibrated against a recorded GR92 track via RouteCalibrator.groovy)')
    double speedKmh = 4.59

    @Option(names = ['--start-time'], description = 'Planned hike start time, HH:mm 24h (default: 07:00)')
    String startTime = '07:00'

    @Option(names = ['--date'], description = 'Planned hike date, yyyy-MM-dd (default: today)')
    String date

    @Option(names = ['--break'], description = 'Rest break cadence as interval:duration in minutes, e.g. "60:6" for a 6-min pause every 60 min of movement; "0:0" disables breaks (default: 60:5)')
    String breakSpec = '60:5'
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

List<Map> parseGpx(File file) {
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

Map computeBoundingBox(List<Map> points) {
    double padDegrees = 0.005
    double minLat = points.collect { it.lat as double }.min() - padDegrees
    double maxLat = points.collect { it.lat as double }.max() + padDegrees
    double minLon = points.collect { it.lon as double }.min() - padDegrees
    double maxLon = points.collect { it.lon as double }.max() + padDegrees
    [minLat: minLat, minLon: minLon, maxLat: maxLat, maxLon: maxLon]
}

String buildOverpassQuery(Map bbox) {
    String bboxStr = [bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon]
        .collect { String.format(Locale.ROOT, '%.6f', it as double) }
        .join(',')
    "[out:json][timeout:30];\nway[\"highway\"](${bboxStr});\nout tags geom;"
}

String fetchOverpassRaw(Map bbox) {
    String query = buildOverpassQuery(bbox)
    String url = 'https://overpass-api.de/api/interpreter'
    String body = "data=${URLEncoder.encode(query, 'UTF-8')}"

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header('Content-Type', 'application/x-www-form-urlencoded')
        .timeout(Duration.ofSeconds(45))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        throw new RuntimeException("Overpass API request failed with HTTP ${response.statusCode()}: ${response.body()?.take(200)}")
    }
    response.body()
}

List<Map> parseOsmWays(Map osmData) {
    if (!osmData || !osmData.elements) {
        return []
    }
    // Overpass's "geom" query keyword requests full geometry, but the JSON response
    // key for a way's coordinate list is "geometry", not "geom".
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

// OSM mappers very often record a rural trail's existence (track/path/footway/...) without
// ever adding a surface tag - defaulting that gap to "unknown" (the model's lowest, most
// generic terrain penalty) systematically understates difficulty on routes with sparse
// tagging, which some GR92 stages have plenty of. 'ground' is a reasonable unpaved-trail
// default until a secondary data source can confirm the actual surface - see TODO.md.
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

// A local equirectangular projection is accurate enough at the scale of a 30 m matching
// threshold, and is far cheaper than repeated great-circle math over many OSM segments.
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

Map nearestWayInfo(double plat, double plon, List<Map> ways, double thresholdM) {
    double latPad = thresholdM / 110540.0
    double lonPad = thresholdM / (111320.0 * Math.cos(Math.toRadians(plat)))

    double bestDist = Double.MAX_VALUE
    Map bestTags = null

    for (way in ways) {
        List<Map> geom = way.geom as List<Map>
        for (int i = 1; i < geom.size(); i++) {
            Map a = geom[i - 1]
            Map b = geom[i]
            double aLat = a.lat as double
            double aLon = a.lon as double
            double bLat = b.lat as double
            double bLon = b.lon as double

            // Cheap bounding-box rejection before the more expensive projected distance.
            if (Math.min(aLat, bLat) - latPad > plat || Math.max(aLat, bLat) + latPad < plat) {
                continue
            }
            if (Math.min(aLon, bLon) - lonPad > plon || Math.max(aLon, bLon) + lonPad < plon) {
                continue
            }

            double d = pointToSegmentDistanceM(plat, plon, aLat, aLon, bLat, bLon)
            if (d < bestDist) {
                bestDist = d
                bestTags = way.tags as Map
            }
        }
    }

    if (bestTags == null || bestDist > thresholdM) {
        return [surface: 'unknown', sacScale: 'none', tracktype: 'unknown', highway: 'unknown',
                tunnel: '', covered: '', natural: '', landuse: '', distanceM: bestDist]
    }

    String highway = (bestTags.highway ?: 'unknown') as String
    String surface = bestTags.surface ? (bestTags.surface as String) : inferSurfaceFromHighway(highway)
    String sacScale = bestTags.sac_scale ? (bestTags.sac_scale as String) : 'none'
    String tracktype = bestTags.tracktype ? (bestTags.tracktype as String) : 'unknown'
    String tunnel = (bestTags.tunnel ?: '') as String
    String covered = (bestTags.covered ?: '') as String
    String natural = (bestTags.natural ?: '') as String
    String landuse = (bestTags.landuse ?: '') as String

    [surface: surface, sacScale: sacScale, tracktype: tracktype, highway: highway,
     tunnel: tunnel, covered: covered, natural: natural, landuse: landuse, distanceM: bestDist]
}

Map computeCentroid(List<Map> points) {
    double lat = points.collect { it.lat as double }.sum() / points.size()
    double lon = points.collect { it.lon as double }.sum() / points.size()
    [lat: lat, lon: lon]
}

String fetchWeatherResponse(String url) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build()

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        throw new RuntimeException("Open-Meteo request failed with HTTP ${response.statusCode()}: ${response.body()?.take(200)}")
    }
    response.body()
}

// forecast_days=16 (Open-Meteo's maximum) means one cached response stays valid for any
// --date within the next 16 days, not just today, without a date-specific query.
String fetchWeatherForecastRaw(double lat, double lon) {
    String url = "https://api.open-meteo.com/v1/forecast?latitude=${String.format(Locale.ROOT, '%.5f', lat)}" +
        "&longitude=${String.format(Locale.ROOT, '%.5f', lon)}" +
        '&hourly=temperature_2m,direct_radiation,cloud_cover&timezone=auto&forecast_days=16'
    fetchWeatherResponse(url)
}

// The Archive API has no rolling forward window like the Forecast API does, so it's
// queried for the single requested day only - the cache is only valid for that exact
// date (checked via timelineCoversDate before trusting it).
String fetchWeatherArchiveRaw(double lat, double lon, LocalDate date) {
    String url = "https://archive-api.open-meteo.com/v1/archive?latitude=${String.format(Locale.ROOT, '%.5f', lat)}" +
        "&longitude=${String.format(Locale.ROOT, '%.5f', lon)}" +
        "&start_date=${date}&end_date=${date}" +
        '&hourly=temperature_2m,direct_radiation,cloud_cover&timezone=auto'
    fetchWeatherResponse(url)
}

Map parseWeatherTimeline(Map weatherData) {
    if (!weatherData || !weatherData.hourly) {
        return null
    }
    Map hourly = weatherData.hourly as Map
    if (!hourly.time || !hourly.temperature_2m || !hourly.direct_radiation) {
        return null
    }
    [
        times: hourly.time as List,
        temps: (hourly.temperature_2m as List).collect { (it ?: 0.0) as double },
        radiation: (hourly.direct_radiation as List).collect { (it ?: 0.0) as double }
    ]
}

// The Archive API's response only covers the single requested day, unlike the Forecast
// API's rolling 16-day window, so a cached response must be checked against the currently
// requested date rather than trusted just because a cache file exists.
boolean timelineCoversDate(Map timeline, LocalDate date) {
    if (!timeline) {
        return false
    }
    String prefix = date.toString()
    (timeline.times as List<String>).any { it.startsWith(prefix) }
}

// Linearly interpolates the hourly Open-Meteo timeline at an arbitrary local timestamp,
// falling back to the nearest end point if the target falls outside the fetched range.
Map weatherAtTime(Map timeline, LocalDateTime target) {
    if (!timeline) {
        return null
    }
    List<String> times = timeline.times as List<String>
    List<Double> temps = timeline.temps as List<Double>
    List<Double> radiation = timeline.radiation as List<Double>
    int n = times.size()
    if (n == 0) {
        return null
    }

    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    int idx = -1
    for (int i = 0; i < n; i++) {
        LocalDateTime t = LocalDateTime.parse(times[i], fmt)
        if (!t.isBefore(target)) {
            idx = i
            break
        }
    }

    if (idx == -1) {
        int last = n - 1
        return [temp: temps[last], radiation: radiation[last]]
    }
    if (idx == 0) {
        return [temp: temps[0], radiation: radiation[0]]
    }

    LocalDateTime beforeT = LocalDateTime.parse(times[idx - 1], fmt)
    LocalDateTime afterT = LocalDateTime.parse(times[idx], fmt)
    long totalMinutes = Duration.between(beforeT, afterT).toMinutes()
    long elapsedMinutes = Duration.between(beforeT, target).toMinutes()
    double frac = totalMinutes > 0 ? Math.max(0.0, Math.min(1.0, elapsedMinutes / (double) totalMinutes)) : 0.0

    double temp = (temps[idx - 1] as double) + ((temps[idx] as double) - (temps[idx - 1] as double)) * frac
    double rad = (radiation[idx - 1] as double) + ((radiation[idx] as double) - (radiation[idx - 1] as double)) * frac

    [temp: temp, radiation: rad]
}

// Baseline solar factor from direct radiation alone, before any canopy/terrain damping.
double baseSunFactorForRadiation(double radiation) {
    if (radiation < 100.0) {
        return 1.0
    }
    if (radiation <= 500.0) {
        return 1.0 + (radiation - 100.0) / 400.0 * 0.10
    }
    1.10 + Math.min(0.10, (radiation - 500.0) / 500.0 * 0.10)
}

// Classifies a point's overhead cover from its matched OSM tags: fully covered (tunnel/
// covered=yes), forested (wood/forest landuse, or a rough grade4/5 track implying dense
// vegetation), or exposed (everything else - open ridges, roads, tracks without canopy).
String canopyClassFor(Map point) {
    String tunnel = (point.tunnel ?: '') as String
    String covered = (point.covered ?: '') as String
    String natural = (point.natural ?: '') as String
    String landuse = (point.landuse ?: '') as String
    String tracktype = (point.tracktype ?: '') as String

    if (tunnel.equalsIgnoreCase('yes') || covered.equalsIgnoreCase('yes')) {
        return 'covered'
    }
    if (natural.equalsIgnoreCase('wood') || landuse.equalsIgnoreCase('forest') || tracktype in ['grade4', 'grade5']) {
        return 'forest'
    }
    'exposed'
}

double effectiveExposureFor(String canopyClass, double baseSunFactor) {
    if (canopyClass == 'covered') {
        return 1.0
    }
    if (canopyClass == 'forest') {
        return 1.0 + (baseSunFactor - 1.0) * 0.5
    }
    baseSunFactor
}

String sunLabelFor(String canopyClass, double radiation) {
    if (canopyClass == 'covered') {
        return 'Covered'
    }
    if (canopyClass == 'forest') {
        return 'Forest shade'
    }
    if (radiation < 100.0) {
        return 'Shade/twilight'
    }
    if (radiation <= 500.0) {
        return 'Partial sun'
    }
    'Full sun'
}

String solarBandColour(double radiation) {
    if (radiation < 100.0) {
        return '#37474F'
    }
    if (radiation <= 500.0) {
        return '#FFB300'
    }
    '#FF6F00'
}

// Terrain factor (eta): how much harder a surface is to move over than firm pavement,
// independent of gradient. Grouped from OSM's "surface" tag values.
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

// Speed-model terrain factor: a separate, empirically calibrated surface multiplier used only
// by the effort-adjusted duration model (see speedSlopeFactor below), not by the trail strain
// score. RouteCalibrator.groovy measured real hikers' actual pace against a recorded GR92
// track and found surface roughness costs far less real-world pace than terrainFactorForSurface
// assumes; that function is left untouched since the strain score's own calibration (a
// reference 20 km/500 m T1-paved route scored at 50/100) and its "high-strain descent" and
// mechanical-descent-strain thresholds are tuned against its original 1.0-1.9 scale, not this
// one. Scree/sand/boulders were not distinctly represented in the one calibration track used so
// far, so they share the same calibrated value as gravel/rock pending a route that covers them.
double speedTerrainFactorForSurface(String surface) {
    String s = (surface ?: '').toLowerCase()
    Set<String> firm = ['asphalt', 'concrete', 'paved', 'paving_stones', 'compacted', 'fine_gravel', 'hard'] as Set

    firm.contains(s) ? 1.0 : 1.05
}

// Technical penalty (T-factor): the extra care/exposure/scrambling load implied by the
// SAC hiking scale, independent of gradient and surface.
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
    // hiking / T1 / none, and anything unrecognised, is treated as the neutral baseline.
    1.0
}

// Minetti's polynomial approximation of metabolic cost per unit distance, as a function of
// gradient (a fraction, not a percentage), normalised so flat ground (i = 0) costs exactly
// 1.0 - i.e. "1.0x" means "as costly as walking flat pavement". Clamped at 0.5 since even a
// gentle descent still costs some minimum effort to keep moving.
double minettiCostMultiplier(double gradeFraction) {
    double i = gradeFraction
    double cw = 280.5 * Math.pow(i, 5) - 58.7 * Math.pow(i, 4) - 268.3 * Math.pow(i, 3) + 95.8 * Math.pow(i, 2) + 4.13 * i + 3.6
    Math.max(0.5, cw / 3.6)
}

String strainColour(double intensity) {
    if (intensity < 0.8) {
        return '#4DD0E1'
    }
    if (intensity < 1.3) {
        return '#66BB6A'
    }
    if (intensity < 2.5) {
        return '#FDD835'
    }
    if (intensity < 5.0) {
        return '#FB8C00'
    }
    '#B71C1C'
}

// A 0-100 index scaled against a hypothetical reference route: 20 km with 500 m of gentle
// climbing then 500 m of gentle descending (+/-5% grade, well below the -10% braking
// threshold), entirely on T1/paved terrain. That reference route is defined to land at 50
// on the scale, so routes roughly twice as metabolically/mechanically costly land near 100.
double compositeStrainScore(double effortDistanceKm, double totalBrakingIndex) {
    double refHalfDistanceM = 10000.0
    double refEffortDistanceKm = refHalfDistanceM * (minettiCostMultiplier(0.05) + minettiCostMultiplier(-0.05)) / 1000.0
    double refCombined = refEffortDistanceKm

    double actualCombined = effortDistanceKm + (totalBrakingIndex / 1000.0)
    double score = refCombined > 0 ? (actualCombined / refCombined) * 50.0 : 0.0
    Math.max(0.0, Math.min(100.0, score))
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

double din33466Duration(double distanceKm, double ascentM, double descentM) {
    double horizontalHours = distanceKm / 4.0
    double verticalHours = (ascentM / 400.0) + (descentM / 800.0)
    double larger = Math.max(horizontalHours, verticalHours)
    double smaller = Math.min(horizontalHours, verticalHours)
    larger + (smaller / 2.0)
}

// Calibrated slope-response curve for the effort-adjusted duration model: a piecewise-linear
// interpolation between empirically observed anchor points (grade %, speed factor relative to
// the calibrated flat base speed), replacing Tobler's theoretical hiking function. Tobler
// predicts an exponential fall-off in both directions from a -5% peak; RouteCalibrator.groovy
// measured a real hiker's pace against a recorded GR92 track and found actual speed does not
// fall away nearly that sharply - a moderate descent was even slightly faster than flat pace.
// Beyond the outermost anchor (steeper than +25.8% or -16.5%), the factor holds flat at that
// anchor's value rather than extrapolating further, since no calibration data exists past
// those grades. Anchor grade -> calibrated speed, with v_base = 4.59 km/h: -16.5% -> 4.48 km/h,
// -7.4% -> 4.88 km/h, 0% -> 4.59 km/h, +7.3% -> 4.35 km/h, +25.8% -> 2.39 km/h.
double slopeSpeedFactor(double gradeFraction) {
    List<List<Double>> anchors = [
        [-16.5, 4.48 / 4.59], [-7.4, 4.88 / 4.59], [0.0, 1.0], [7.3, 4.35 / 4.59], [25.8, 2.39 / 4.59]
    ]
    double gradePct = gradeFraction * 100.0
    if (gradePct <= anchors[0][0]) {
        return anchors[0][1]
    }
    if (gradePct >= anchors[-1][0]) {
        return anchors[-1][1]
    }
    for (int i = 1; i < anchors.size(); i++) {
        double g1 = anchors[i][0]
        if (gradePct <= g1) {
            double g0 = anchors[i - 1][0]
            double f0 = anchors[i - 1][1]
            double f1 = anchors[i][1]
            double frac = g1 > g0 ? (gradePct - g0) / (g1 - g0) : 0.0
            return f0 + (f1 - f0) * frac
        }
    }
    anchors[-1][1]
}

Map durationComparison(double dinDurationHours, double effortDurationHours, double distanceKm, double activeHourlyRate, double baseSpeedKmh) {
    double reserveVolume = 0.5
    double dinConsumption = dinDurationHours * activeHourlyRate + reserveVolume
    double effortConsumption = effortDurationHours * activeHourlyRate + reserveVolume
    double deltaMinutes = (effortDurationHours - dinDurationHours) * 60.0
    double dinPaceKmh = dinDurationHours > 0 ? distanceKm / dinDurationHours : 0.0
    double effortPaceKmh = effortDurationHours > 0 ? distanceKm / effortDurationHours : 0.0

    String reason = String.format(
        Locale.ROOT,
        'The effort-adjusted model integrates a per-segment speed derived from Tobler\'s hiking ' +
        'function (slowing for both steep climbs and steep descents, rather than assuming a fixed ' +
        '800 m/h descent rate), divided by the terrain factor (eta) and technical factor (T-factor) ' +
        'at each point from the matched OpenStreetMap surface and SAC scale, then further slowed by ' +
        'a thermal pace penalty from the simulated ambient temperature and solar radiation at the ' +
        'time each segment is reached. Rough, technical or hot, sun-exposed sections are explicitly ' +
        'slowed rather than sped up, unlike a pure energy-cost model.'
    )

    [
        dinDurationHours: dinDurationHours, effortDurationHours: effortDurationHours,
        deltaMinutes: deltaMinutes, dinConsumption: dinConsumption, effortConsumption: effortConsumption,
        dinPaceKmh: dinPaceKmh, effortPaceKmh: effortPaceKmh, reserveVolume: reserveVolume,
        baseSpeedKmh: baseSpeedKmh, reason: reason
    ]
}

Map dynamicWeatherSummary(
    LocalDateTime startDateTime, double dinDurationHours, double terrainDurationHours, double thermalDurationHours,
    double elapsedHours, double breakDurationHours, int breakCount,
    double startTemp, double peakTemp, double peakTempNoBreaks, double maxRadiation,
    double movingWaterLitres, double breakWaterLitres, double staticCarry, boolean weatherMatched
) {
    DateTimeFormatter clockFmt = DateTimeFormatter.ofPattern('HH:mm')
    LocalDateTime finishDateTime = startDateTime.plusSeconds(Math.round(elapsedHours * 3600.0))
    double reserveVolume = 0.5
    double dynamicCarry = Math.round((movingWaterLitres + breakWaterLitres + reserveVolume) * 10.0) / 10.0
    double tempShift = peakTemp - peakTempNoBreaks

    String reason = weatherMatched
        ? String.format(
            Locale.ROOT,
            'Simulated from a %s start using live Open-Meteo hourly forecast data (temperature, direct ' +
            'solar radiation, cloud cover) at this route\'s midpoint location, combined with the matched ' +
            'OpenStreetMap canopy (tunnels, forest, open ridges) to estimate real sun exposure and heat ' +
            'load per segment. %d scheduled break%s (%.0f min total) shift every later segment\'s weather ' +
            'lookup to the delayed wall clock, rather than a single flat assumption for the whole hike.',
            startDateTime.format(clockFmt), breakCount, breakCount == 1 ? '' : 's', breakDurationHours * 60.0
          )
        : String.format(
            Locale.ROOT,
            'No live forecast data was available, so this uses a flat %.0f degC (-t/--temp) with no solar ' +
            'radiation model for the whole hike, starting at %s.',
            startTemp, startDateTime.format(clockFmt)
          )

    [
        startTime: startDateTime.format(clockFmt), finishTime: finishDateTime.format(clockFmt),
        dinDurationHours: dinDurationHours, terrainDurationHours: terrainDurationHours,
        thermalDurationHours: thermalDurationHours, elapsedHours: elapsedHours,
        breakDurationHours: breakDurationHours, breakCount: breakCount,
        startTemp: startTemp, peakTemp: peakTemp, peakTempNoBreaks: peakTempNoBreaks, tempShift: tempShift,
        maxRadiation: maxRadiation, movingWaterLitres: movingWaterLitres, breakWaterLitres: breakWaterLitres,
        reserveVolume: reserveVolume, dynamicCarry: dynamicCarry, staticCarry: staticCarry,
        weatherMatched: weatherMatched, reason: reason
    ]
}

Map classifyDifficulty(double ascentM, double distanceKm, double maxGrade, int clampedGradeCount) {
    double ascentPerKm = distanceKm > 0 ? ascentM / distanceKm : 0.0
    double moderateMaxGrade = 12.0
    double moderateAscentPerKm = 30.0
    double difficultMaxGrade = 20.0
    double difficultAscentPerKm = 60.0

    String tier
    List<String> reasons = []

    if (maxGrade > difficultMaxGrade || ascentPerKm > difficultAscentPerKm) {
        tier = 'Difficult'
        if (maxGrade > difficultMaxGrade) {
            reasons << String.format(Locale.ROOT, 'the steepest smoothed gradient reaches %.1f%%, above the %.0f%% Difficult threshold', maxGrade, difficultMaxGrade)
        }
        if (ascentPerKm > difficultAscentPerKm) {
            reasons << String.format(Locale.ROOT, 'the ascent rate is %.0f m/km, above the %.0f m/km Difficult threshold', ascentPerKm, difficultAscentPerKm)
        }
    } else if (maxGrade > moderateMaxGrade || ascentPerKm > moderateAscentPerKm) {
        tier = 'Moderate'
        if (maxGrade > moderateMaxGrade) {
            reasons << String.format(Locale.ROOT, 'the steepest smoothed gradient reaches %.1f%%, above the %.0f%% Moderate threshold (but at or below the %.0f%% Difficult threshold)', maxGrade, moderateMaxGrade, difficultMaxGrade)
        }
        if (ascentPerKm > moderateAscentPerKm) {
            reasons << String.format(Locale.ROOT, 'the ascent rate is %.0f m/km, above the %.0f m/km Moderate threshold (but at or below the %.0f m/km Difficult threshold)', ascentPerKm, moderateAscentPerKm, difficultAscentPerKm)
        }
    } else {
        tier = 'Easy'
        reasons << String.format(Locale.ROOT, 'the steepest smoothed gradient is only %.1f%%, at or below the %.0f%% Moderate threshold', maxGrade, moderateMaxGrade)
        reasons << String.format(Locale.ROOT, 'the ascent rate is only %.0f m/km, at or below the %.0f m/km Moderate threshold', ascentPerKm, moderateAscentPerKm)
    }

    String reason = "Classified as ${tier} because ${reasons.join(', and ')}."
    if (clampedGradeCount > 0) {
        String plural = clampedGradeCount == 1 ? 'was' : 'were'
        reason += " Note: ${clampedGradeCount} short section${clampedGradeCount == 1 ? '' : 's'} of the route had a gradient reading over 100%, most likely from clustered/duplicated waypoints rather than real terrain, and ${plural} capped at 100% for this calculation."
    }
    [tier: tier, reason: reason, maxGrade: maxGrade, ascentPerKm: ascentPerKm]
}

Map shenandoahDifficulty(double ascentM, double distanceKm) {
    // Shenandoah National Park's hiking-difficulty formula: sqrt(ascent_ft * 2 * distance_mi).
    // It rates overall exertion from total climbing and distance combined, unlike the
    // gradient-based tier above, which only looks at how steep the steepest point is.
    double ascentFt = ascentM / 0.3048
    double distanceMi = distanceKm / 1.609344
    double score = Math.sqrt(ascentFt * 2.0 * distanceMi)

    String tier
    if (score < 50.0) {
        tier = 'Easiest'
    } else if (score < 100.0) {
        tier = 'Moderate'
    } else if (score < 150.0) {
        tier = 'Moderately strenuous'
    } else if (score < 200.0) {
        tier = 'Strenuous'
    } else {
        tier = 'Very strenuous'
    }

    String reason = String.format(
        Locale.ROOT,
        'Shenandoah score is %.0f = sqrt(ascent_ft x 2 x distance_mi), from %.0f ft of ascent over %.1f mi, rating it "%s".',
        score, ascentFt, distanceMi, tier
    )

    [tier: tier, score: score, ascentFt: ascentFt, distanceMi: distanceMi, reason: reason]
}

Map waterIntakeRecommendation(double durationHours, double tempCelsius, double exposureFactor) {
    // Calibrated Zone 2 endurance hydration model for a healthy adult. Duration already
    // reflects this route's vertical effort via the DIN 33466 estimate, so no separate
    // difficulty multiplier is applied here to avoid double-counting exertion.
    double reserveVolume = 0.5

    Closure<Double> hourlyRateForTemp = { double t -> 0.35 + Math.max(0.0, t - 15.0) * 0.02 }
    Closure<Double> carryForTemp = { double t ->
        double rate = hourlyRateForTemp(t) * exposureFactor
        Math.round((durationHours * rate + reserveVolume) * 10.0) / 10.0
    }

    double hourlyRate = hourlyRateForTemp(tempCelsius)
    double activeHourlyRate = hourlyRate * exposureFactor
    double consumptionVolume = durationHours * activeHourlyRate
    double recommendedCarry = Math.round((consumptionVolume + reserveVolume) * 10.0) / 10.0

    List<Double> chartTemps = [10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0]
    List<Map> chartData = chartTemps.collect { t -> [tempC: t, litres: carryForTemp(t)] }

    String reason = String.format(
        Locale.ROOT,
        'Calibrated Zone 2 endurance model: burn rate is %.2f L/h (0.35 L/h baseline at or below 15 degC, ' +
        '+0.02 L/h per degree above that, x%.2f exposure factor), for %.1f hours moving time gives %.1f L ' +
        'expected consumption at %.0f degC, plus a fixed 0.5 L reserve = %.1f L recommended carry. Duration ' +
        'already reflects this route\'s vertical effort via the DIN 33466 estimate, so no separate difficulty ' +
        'multiplier is applied. This is general guidance, not personalised medical advice; individual needs ' +
        'vary with body size, fitness and health.',
        activeHourlyRate, exposureFactor, durationHours, consumptionVolume, tempCelsius, recommendedCarry
    )

    [
        tempCelsius: tempCelsius, exposureFactor: exposureFactor, hourlyRate: hourlyRate,
        activeHourlyRate: activeHourlyRate, consumptionVolume: consumptionVolume,
        reserveVolume: reserveVolume, recommendedCarry: recommendedCarry,
        chartData: chartData, reason: reason
    ]
}

Map trailStrainSummary(double effortDistanceKm, double actualDistanceKm, double totalBrakingIndex, double highStrainDescentKm, Map<String, Double> surfaceDistanceM, double totalDistanceM) {
    double score = compositeStrainScore(effortDistanceKm, totalBrakingIndex)

    List<Map> surfaceBreakdown = totalDistanceM > 0
        ? surfaceDistanceM.collect { surface, distM -> [surface: surface, pct: (distM / totalDistanceM) * 100.0] }.sort { -it.pct }
        : []

    String reason = String.format(
        Locale.ROOT,
        'Composite strain score is %.0f/100, scaled against a reference 20 km / 500 m T1 route on paved ' +
        'ground. This route\'s effort distance is %.2f km (metabolic cost, equivalent flat paved km) versus ' +
        '%.2f km actual, with a braking load index of %.0f from %.2f km of high-strain technical descent ' +
        '(steeper than -15%% on rough or loose surface).',
        score, effortDistanceKm, actualDistanceKm, totalBrakingIndex, highStrainDescentKm
    )

    [
        score: score, effortDistanceKm: effortDistanceKm, actualDistanceKm: actualDistanceKm,
        totalBrakingIndex: totalBrakingIndex, highStrainDescentKm: highStrainDescentKm,
        surfaceBreakdown: surfaceBreakdown, reason: reason
    ]
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

void printSummary(double distanceKm, double ascent, double descent, double durationHours, Map difficultyResult, Map shenandoahResult, Map waterResult, Map trailInfo, Map descentStrain, Map trailStrain, Map durationResult, Map dynamicResult) {
    println '=== Elevation profile summary ==='
    println String.format(Locale.ROOT, 'Total distance : %.2f km', distanceKm)
    println String.format(Locale.ROOT, 'Total ascent   : %.0f m', ascent)
    println String.format(Locale.ROOT, 'Total descent  : %.0f m', descent)
    double deltaMinutes = durationResult.deltaMinutes as double
    String deltaSign = deltaMinutes >= 0 ? '+' : '-'
    println "DIN 33466 duration       : ${formatDuration(durationResult.dinDurationHours as double)}"
    println "Effort-adjusted duration : ${formatDuration(durationResult.effortDurationHours as double)} (delta: ${deltaSign}${Math.round(Math.abs(deltaMinutes)) as int} min)"
    println String.format(Locale.ROOT, 'DIN hydration need       : %.1f L', durationResult.dinConsumption as double)
    println String.format(Locale.ROOT, 'Effort-adjusted hydration: %.1f L', durationResult.effortConsumption as double)
    println "Weather data     : ${dynamicResult.weatherMatched ? 'Live Open-Meteo forecast' : 'No forecast data; using static -t/--temp value'}"
    println "Moving time (DIN 33466)        : ${formatDuration(dynamicResult.dinDurationHours as double)}"
    println "Moving time (terrain adjusted) : ${formatDuration(dynamicResult.terrainDurationHours as double)}"
    println "Moving time (thermally adj.)   : ${formatDuration(dynamicResult.thermalDurationHours as double)}"
    int breakCount = dynamicResult.breakCount as int
    double breakMinutes = (dynamicResult.breakDurationHours as double) * 60.0
    println "Total elapsed (incl. breaks)   : ${formatDuration(dynamicResult.elapsedHours as double)} (${breakCount} break${breakCount == 1 ? '' : 's'}, ${Math.round(breakMinutes) as int} min paused)"
    println "Estimated finish time          : ${dynamicResult.finishTime}"
    println String.format(Locale.ROOT, 'Start temperature              : %.1f degC', dynamicResult.startTemp as double)
    double tempShift = dynamicResult.tempShift as double
    String shiftNote = tempShift > 0.5
        ? String.format(Locale.ROOT, ' (breaks pushed this %.1f degC hotter than a straight-through hike: %.1f degC)', tempShift, dynamicResult.peakTempNoBreaks as double)
        : ''
    println String.format(Locale.ROOT, 'Peak temperature               : %.1f degC%s', dynamicResult.peakTemp as double, shiftNote)
    println String.format(Locale.ROOT, 'Max solar radiation            : %.0f W/m2', dynamicResult.maxRadiation as double)
    println String.format(Locale.ROOT, 'Active moving water            : %.1f L', dynamicResult.movingWaterLitres as double)
    println String.format(Locale.ROOT, 'Break/resting water            : %.1f L', dynamicResult.breakWaterLitres as double)
    println String.format(Locale.ROOT, 'Safety reserve                 : %.1f L', dynamicResult.reserveVolume as double)
    println String.format(Locale.ROOT, 'Recommended total carry        : %.1f L (vs flat static estimate %.1f L)', dynamicResult.dynamicCarry as double, dynamicResult.staticCarry as double)
    println "Reason                         : ${dynamicResult.reason}"
    println "Trail data      : ${trailInfo.matched ? 'Matched to OpenStreetMap (surface/SAC scale available)' : 'Raw GPX (no OSM match; surface/SAC scale unknown)'}"
    println "Difficulty      : ${difficultyResult.tier}"
    println "Reason          : ${difficultyResult.reason}"
    println String.format(Locale.ROOT, 'Effort (Shenandoah): %.0f (%s)', shenandoahResult.score as double, shenandoahResult.tier)
    println "Reason          : ${shenandoahResult.reason}"
    println String.format(Locale.ROOT, 'Forecast temperature : %.0f degC (exposure factor %.2f)', waterResult.tempCelsius as double, waterResult.exposureFactor as double)
    println String.format(Locale.ROOT, 'Calibrated burn rate : %.2f L/h', waterResult.activeHourlyRate as double)
    println String.format(Locale.ROOT, 'Expected consumption : %.1f L', waterResult.consumptionVolume as double)
    println String.format(Locale.ROOT, 'Recommended carry    : %.1f L (includes %.1f L reserve)', waterResult.recommendedCarry as double, waterResult.reserveVolume as double)
    println "Reason          : ${waterResult.reason}"
    println String.format(Locale.ROOT, 'Steep descent (< -15%%): %.2f km', (descentStrain.steepDescentDistanceM as double) / 1000.0)
    println String.format(Locale.ROOT, '  - Smooth (paved)     : %.2f km', (descentStrain.smoothSteepDescentDistanceM as double) / 1000.0)
    println String.format(Locale.ROOT, '  - Rough (trail)      : %.2f km', (descentStrain.roughSteepDescentDistanceM as double) / 1000.0)
    println String.format(Locale.ROOT, 'Effort distance      : %.2f km (actual: %.2f km)', trailStrain.effortDistanceKm as double, trailStrain.actualDistanceKm as double)
    println String.format(Locale.ROOT, 'Braking load index   : %.0f', trailStrain.totalBrakingIndex as double)
    println String.format(Locale.ROOT, 'High-strain descent  : %.2f km (< -15%% grade on rough/loose surface)', trailStrain.highStrainDescentKm as double)
    println String.format(Locale.ROOT, 'Trail strain score   : %.0f/100', trailStrain.score as double)
    println 'Surface breakdown    :'
    (trailStrain.surfaceBreakdown as List<Map>).each { entry ->
        println String.format(Locale.ROOT, '  - %-10s: %.1f%%', entry.surface, entry.pct as double)
    }
}

String gradeColour(double grade) {
    if (grade < -15.0) {
        return '#6A1B9A'
    }
    if (grade < 0.0) {
        return '#4DD0E1'
    }
    if (grade < 6.0) {
        return '#66BB6A'
    }
    if (grade < 12.0) {
        return '#FDD835'
    }
    if (grade < 20.0) {
        return '#FB8C00'
    }
    '#E53935'
}

String fmt(double v) {
    String.format(Locale.ROOT, '%.2f', v)
}

String escapeXml(String s) {
    s.replace('&', '&amp;').replace('"', '&quot;').replace('<', '&lt;').replace('>', '&gt;')
}

String buildLegend(int width, int height, int padding) {
    List<List<String>> entries = [
        ['#6A1B9A', 'Steep/braking descent (< -15%)'],
        ['#4DD0E1', 'Gentle descent (-15% to 0%)'],
        ['#66BB6A', 'Flat / mild (0-6%)'],
        ['#FDD835', 'Moderate climb (6-12%)'],
        ['#FB8C00', 'Steep climb (12-20%)'],
        ['#E53935', 'Severe climb (> 20%)']
    ]
    int x = padding
    int y = height - 14
    StringBuilder sb = new StringBuilder()
    entries.eachWithIndex { entry, idx ->
        int ex = x + idx * 165
        sb << "<rect x=\"${ex}\" y=\"${y - 10}\" width=\"12\" height=\"12\" fill=\"${entry[0]}\" />\n"
        sb << "<text x=\"${ex + 16}\" y=\"${y}\" font-size=\"10\" fill=\"#333\">${entry[1]}</text>\n"
    }
    sb.toString()
}

String buildStrainLegend(int width, int height, int padding) {
    List<List<String>> entries = [
        ['#4DD0E1', 'Low (< 0.8x)'],
        ['#66BB6A', 'Flat-equivalent (0.8-1.3x)'],
        ['#FDD835', 'Moderate (1.3-2.5x)'],
        ['#FB8C00', 'High (2.5-5x)'],
        ['#B71C1C', 'Extreme (>= 5x)']
    ]
    int x = padding
    int y = height - 14
    StringBuilder sb = new StringBuilder()
    entries.eachWithIndex { entry, idx ->
        int ex = x + idx * 195
        sb << "<rect x=\"${ex}\" y=\"${y - 10}\" width=\"12\" height=\"12\" fill=\"${entry[0]}\" />\n"
        sb << "<text x=\"${ex + 16}\" y=\"${y}\" font-size=\"10\" fill=\"#333\">${entry[1]}</text>\n"
    }
    sb.toString()
}

String tempBarColour(double tempC) {
    if (tempC < 15.0) {
        return '#4FC3F7'
    }
    if (tempC < 25.0) {
        return '#66BB6A'
    }
    if (tempC < 32.0) {
        return '#FDD835'
    }
    if (tempC < 38.0) {
        return '#FB8C00'
    }
    '#E53935'
}

String buildWaterChart(List<Map> chartData, double maxScaleLitres) {
    int width = 350
    int height = 140
    int barGap = 8
    int barWidth = ((width - (chartData.size() + 1) * barGap) / chartData.size()) as int

    StringBuilder bars = new StringBuilder()
    chartData.eachWithIndex { entry, idx ->
        double litres = entry.litres as double
        double tempC = entry.tempC as double
        int barHeight = Math.max(4, Math.round((litres / maxScaleLitres) * (height - 34)) as int)
        int x = barGap + idx * (barWidth + barGap)
        int y = height - 22 - barHeight
        String colour = tempBarColour(tempC)

        bars << "<rect id=\"water-bar-${idx}\" class=\"water-bar\" data-temp=\"${fmt(tempC)}\" x=\"${x}\" y=\"${y}\" width=\"${barWidth}\" height=\"${barHeight}\" fill=\"${colour}\" rx=\"2\" />\n"
        bars << "<text id=\"water-bar-label-${idx}\" x=\"${x + barWidth / 2}\" y=\"${y - 5}\" text-anchor=\"middle\" font-size=\"10\" fill=\"#333\">${String.format(Locale.ROOT, '%.1f', litres)}</text>\n"
        bars << "<text x=\"${x + barWidth / 2}\" y=\"${height - 6}\" text-anchor=\"middle\" font-size=\"10\" fill=\"#666\">${Math.round(tempC) as int}&#176;</text>\n"
    }

    """<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
${bars}</svg>"""
}

String buildHtml(List<Map> points, double distanceKm, double ascent, double descent, double durationHours, Map difficultyResult, Map shenandoahResult, Map waterResult, Map trailInfo, Map descentStrain, Map trailStrain, Map durationResult, Map dynamicResult, List<Map> breakEvents) {
    String difficulty = difficultyResult.tier
    String difficultyReason = difficultyResult.reason
    double maxGrade = difficultyResult.maxGrade as double
    double ascentPerKm = difficultyResult.ascentPerKm as double
    String effortTier = shenandoahResult.tier
    String effortReason = shenandoahResult.reason
    double shenandoahScore = shenandoahResult.score as double
    double ascentFt = shenandoahResult.ascentFt as double
    double distanceMi = shenandoahResult.distanceMi as double
    String waterReason = waterResult.reason
    double waterTempCelsius = waterResult.tempCelsius as double
    double waterExposureFactor = waterResult.exposureFactor as double
    double waterActiveHourlyRate = waterResult.activeHourlyRate as double
    double waterConsumptionVolume = waterResult.consumptionVolume as double
    double waterReserveVolume = waterResult.reserveVolume as double
    double waterRecommendedCarry = waterResult.recommendedCarry as double
    List<Map> waterChartData = waterResult.chartData as List<Map>
    // Scale chart bars against the hottest, most exposed case so the bars never overflow
    // when the exposure toggle is switched on in the browser.
    double waterMaxScaleLitres = Math.round((durationHours * (0.35 + Math.max(0.0, 40.0 - 15.0) * 0.02) * 1.1 + waterReserveVolume) * 10.0) / 10.0
    String waterChart = buildWaterChart(waterChartData, waterMaxScaleLitres)
    boolean trailMatched = trailInfo.matched as boolean
    double steepDescentKm = (descentStrain.steepDescentDistanceM as double) / 1000.0
    double smoothSteepDescentKm = (descentStrain.smoothSteepDescentDistanceM as double) / 1000.0
    double roughSteepDescentKm = (descentStrain.roughSteepDescentDistanceM as double) / 1000.0
    double trailStrainScore = trailStrain.score as double
    double effortDistanceKm = trailStrain.effortDistanceKm as double
    double totalBrakingIndex = trailStrain.totalBrakingIndex as double
    double highStrainDescentKm = trailStrain.highStrainDescentKm as double
    String trailStrainReason = trailStrain.reason
    List<Map> surfaceBreakdown = trailStrain.surfaceBreakdown as List<Map>
    double durationDinHours = durationResult.dinDurationHours as double
    double durationEffortHours = durationResult.effortDurationHours as double
    double durationDeltaMinutes = durationResult.deltaMinutes as double
    double durationDinConsumption = durationResult.dinConsumption as double
    double durationEffortConsumption = durationResult.effortConsumption as double
    double durationDinPaceKmh = durationResult.dinPaceKmh as double
    double durationEffortPaceKmh = durationResult.effortPaceKmh as double
    double durationBaseSpeedKmh = durationResult.baseSpeedKmh as double
    double durationReserveVolume = durationResult.reserveVolume as double
    String durationReason = durationResult.reason
    String dynamicStartTime = dynamicResult.startTime
    String dynamicFinishTime = dynamicResult.finishTime
    double dynamicStartTemp = dynamicResult.startTemp as double
    double dynamicPeakTemp = dynamicResult.peakTemp as double
    double dynamicPeakTempNoBreaks = dynamicResult.peakTempNoBreaks as double
    double dynamicMaxRadiation = dynamicResult.maxRadiation as double
    double dynamicCarry = dynamicResult.dynamicCarry as double
    double dynamicStaticCarry = dynamicResult.staticCarry as double
    double dynamicMovingWaterLitres = dynamicResult.movingWaterLitres as double
    double dynamicBreakWaterLitres = dynamicResult.breakWaterLitres as double
    double dynamicReserveVolume = dynamicResult.reserveVolume as double
    double dynamicElapsedHours = dynamicResult.elapsedHours as double
    double dynamicBreakDurationHours = dynamicResult.breakDurationHours as double
    int dynamicBreakCount = dynamicResult.breakCount as int
    String dynamicReason = dynamicResult.reason
    int width = 1100
    int height = 420
    int padding = 50
    int plotWidth = width - 2 * padding
    int plotHeight = height - 2 * padding

    double maxDistance = points[-1].distance
    double minEle = points.collect { it.smoothedEle as double }.min()
    double maxEle = points.collect { it.smoothedEle as double }.max()
    double eleRange = Math.max(1.0, maxEle - minEle)

    Closure<Double> xFor = { double d -> padding + (d / maxDistance) * plotWidth }
    Closure<Double> yFor = { double e -> padding + plotHeight - ((e - minEle) / eleRange) * plotHeight }

    StringBuilder segments = new StringBuilder()
    StringBuilder strainSegments = new StringBuilder()
    StringBuilder hitAreas = new StringBuilder()
    StringBuilder solarBand = new StringBuilder()
    int solarBandHeight = 8
    int solarBandY = padding - 14

    for (int i = 1; i < points.size(); i++) {
        Map p0 = points[i - 1]
        Map p1 = points[i]
        double x0 = xFor(p0.distance as double)
        double x1 = xFor(p1.distance as double)
        double y0 = yFor(p0.smoothedEle as double)
        double y1 = yFor(p1.smoothedEle as double)
        double baseY = padding + plotHeight
        String colour = gradeColour(p1.grade as double)
        double strainIntensity = p1.strainIntensity as double
        String strainCol = strainColour(strainIntensity)

        String polyPoints = "${fmt(x0)},${fmt(baseY)} ${fmt(x0)},${fmt(y0)} ${fmt(x1)},${fmt(y1)} ${fmt(x1)},${fmt(baseY)}"
        segments << "<polygon points=\"${polyPoints}\" fill=\"${colour}\" stroke=\"${colour}\" stroke-width=\"0.5\" />\n"
        strainSegments << "<polygon points=\"${polyPoints}\" fill=\"${strainCol}\" stroke=\"${strainCol}\" stroke-width=\"0.5\" />\n"

        double radiation = (p1.radiation ?: 0.0) as double
        String solarCol = solarBandColour(radiation)
        double solarWidth = Math.max(1.0d, Math.abs(x1 - x0))
        solarBand << "<rect x=\"${fmt(Math.min(x0, x1))}\" y=\"${solarBandY}\" width=\"${fmt(solarWidth)}\" height=\"${solarBandHeight}\" fill=\"${solarCol}\" opacity=\"0.85\" />\n"

        double distKm = (p1.distance as double) / 1000.0
        String surface = (p1.surface ?: 'unknown') as String
        String sacScale = (p1.sacScale ?: 'unknown') as String
        double eta = p1.eta as double
        double strainFactor = p1.strainFactor as double
        String clockTime = (p1.clockTime ?: '--:--') as String
        double ambientTemp = (p1.ambientTemp ?: 0.0) as double
        double segExposure = (p1.exposureFactor ?: 1.0) as double
        double thermalPenaltyPct = (p1.thermalPenaltyPct ?: 0.0) as double
        String sunLabel = (p1.sunLabel ?: 'Unknown') as String
        String tooltip = String.format(
            Locale.ROOT,
            '%.2f km | %.0f m | %.1f%% | surface: %s | SAC: %s | eta: %.2f | strain: %.1fx flat equivalent | ' +
            '%s | %.1f degC | %.0f W/m2 | %.2fx (%s) | pace -%.0f%%',
            distKm, p1.smoothedEle as double, p1.grade as double, surface, sacScale, eta, strainFactor,
            clockTime, ambientTemp, radiation, segExposure, sunLabel, thermalPenaltyPct
        )
        hitAreas << "<rect x=\"${fmt(Math.min(x0, x1))}\" y=\"${padding}\" width=\"${fmt(Math.max(1.0d, Math.abs(x1 - x0)))}\" height=\"${plotHeight}\" fill=\"transparent\" data-tip=\"${escapeXml(tooltip)}\" data-x=\"${fmt(x1)}\" onmousemove=\"showTip(event)\" onmouseleave=\"hideTip()\" />\n"
    }

    // Scheduled rest breaks: a dashed vertical marker plus a small flag, with its own thin
    // hit-rect reusing the same tooltip/crosshair machinery as the main profile.
    StringBuilder breakMarkers = new StringBuilder()
    breakEvents.each { brk ->
        double bx = xFor((brk.distanceKm as double) * 1000.0)
        double topY = padding
        double botY = padding + plotHeight
        String breakTip = String.format(
            Locale.ROOT, 'Break at %s (%d min) | %.1f km',
            brk.clockTime as String, brk.durationMin as int, brk.distanceKm as double
        )
        breakMarkers << "<line x1=\"${fmt(bx)}\" y1=\"${topY}\" x2=\"${fmt(bx)}\" y2=\"${botY}\" stroke=\"#8E24AA\" stroke-width=\"1.5\" stroke-dasharray=\"3,2\" />\n"
        breakMarkers << "<circle cx=\"${fmt(bx)}\" cy=\"${topY - 6}\" r=\"5\" fill=\"#8E24AA\" />\n"
        breakMarkers << "<rect x=\"${fmt(bx - 6)}\" y=\"${topY - 14}\" width=\"12\" height=\"${plotHeight + 14}\" fill=\"transparent\" data-tip=\"${escapeXml(breakTip)}\" data-x=\"${fmt(bx)}\" onmousemove=\"showTip(event)\" onmouseleave=\"hideTip()\" />\n"
    }

    StringBuilder grid = new StringBuilder()
    int gridLines = 4
    for (int g = 0; g <= gridLines; g++) {
        double ele = minEle + (eleRange * g / gridLines)
        double y = yFor(ele)
        grid << "<line x1=\"${padding}\" y1=\"${fmt(y)}\" x2=\"${width - padding}\" y2=\"${fmt(y)}\" stroke=\"#ddd\" stroke-width=\"1\" />\n"
        grid << "<text x=\"${padding - 8}\" y=\"${fmt(y + 4)}\" text-anchor=\"end\" font-size=\"11\" fill=\"#666\">${Math.round(ele)} m</text>\n"
    }

    String legend = buildLegend(width, height, padding)
    String strainLegend = buildStrainLegend(width, height, padding)

    """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8" />
    <title>Elevation profile</title>
    <style>
        body {
            font-family: Arial, Helvetica, sans-serif;
            background: #fafafa;
            color: #222;
            margin: 24px;
        }
        h1 {
            font-size: 20px;
        }
        .summary {
            display: flex;
            gap: 24px;
            margin-bottom: 16px;
            flex-wrap: wrap;
        }
        .summary div {
            background: #fff;
            border: 1px solid #e0e0e0;
            border-radius: 6px;
            padding: 8px 14px;
        }
        .summary span {
            display: block;
            font-size: 12px;
            color: #777;
        }
        .summary strong {
            font-size: 16px;
        }
        .summary strong span {
            font-size: inherit;
            font-weight: inherit;
            color: inherit;
        }
        .trail-data-note {
            font-size: 12px;
            color: #777;
            margin: 6px 0 18px;
        }
        .clickable {
            cursor: pointer;
            text-decoration: underline dotted;
        }
        .mode-toggle {
            margin-bottom: 10px;
            font-size: 13px;
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .mode-btn {
            padding: 5px 12px;
            border: 1px solid #ccc;
            border-radius: 999px;
            background: #fff;
            cursor: pointer;
            font-size: 13px;
        }
        .mode-btn.active {
            background: #333;
            color: #fff;
            border-color: #333;
        }
        .modal-overlay {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(0, 0, 0, 0.5);
            align-items: center;
            justify-content: center;
        }
        .modal-overlay.open {
            display: flex;
        }
        .modal-box {
            background: #fff;
            border-radius: 8px;
            padding: 20px 24px;
            max-width: 420px;
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
        }
        .modal-box h2 {
            margin-top: 0;
            font-size: 18px;
        }
        .modal-box table {
            border-collapse: collapse;
            width: 100%;
            margin-top: 12px;
            font-size: 12px;
        }
        .modal-box th, .modal-box td {
            text-align: left;
            padding: 4px 8px;
            border-bottom: 1px solid #eee;
        }
        .modal-box button {
            margin-top: 16px;
            padding: 6px 14px;
            border: none;
            border-radius: 4px;
            background: #333;
            color: #fff;
            cursor: pointer;
        }
        .water-bar {
            transition: opacity 0.15s ease;
        }
        .water-slider-row {
            margin-top: 14px;
        }
        .water-slider-row input[type="range"] {
            width: 100%;
        }
        .water-readout {
            margin-top: 8px;
            font-size: 14px;
        }
        .water-readout strong {
            font-size: 16px;
        }
        svg {
            background: #fff;
            border: 1px solid #e0e0e0;
            border-radius: 6px;
        }
        #tooltip {
            position: absolute;
            display: none;
            background: rgba(0, 0, 0, 0.8);
            color: #fff;
            padding: 4px 8px;
            border-radius: 4px;
            font-size: 12px;
            pointer-events: none;
        }
        #crosshair {
            display: none;
            stroke: #000;
            stroke-width: 1;
            stroke-dasharray: 4,3;
            pointer-events: none;
        }
    </style>
</head>
<body>
    <h1>Elevation profile</h1>
    <div class="summary">
        <div><span>Distance</span><strong>${String.format(Locale.ROOT, '%.2f km', distanceKm)}</strong></div>
        <div><span>Ascent</span><strong>${String.format(Locale.ROOT, '%.0f m', ascent)}</strong></div>
        <div><span>Descent</span><strong>${String.format(Locale.ROOT, '%.0f m', descent)}</strong></div>
        <div><span>Duration (DIN 33466)</span><strong>${formatDuration(durationDinHours)} (${String.format(Locale.ROOT, '%.1f km/h', durationDinPaceKmh)})</strong></div>
        <div><span>Duration (terrain adjusted)</span><strong class="clickable" onclick="showDurationInfo()"><span id="duration-tile-value">${formatDuration(durationEffortHours)} (${String.format(Locale.ROOT, '%.1f km/h', durationEffortPaceKmh)})</span> &#9432;</strong></div>
        <div><span>Elapsed (door-to-door)</span><strong class="clickable" onclick="showDurationInfo()">${formatDuration(dynamicElapsedHours)} &#9432;</strong></div>
        <div><span>Difficulty</span><strong class="clickable" onclick="showDifficultyInfo()">${difficulty} &#9432;</strong></div>
        <div><span>Effort (Shenandoah)</span><strong class="clickable" onclick="showEffortInfo()">${effortTier} &#9432;</strong></div>
        <div><span>Water (at <span id="water-tile-temp">${Math.round(waterTempCelsius) as int}</span>&deg;C)</span><strong class="clickable" onclick="showWaterInfo()"><span id="water-tile-value">${String.format(Locale.ROOT, '%.1f L', waterRecommendedCarry)}</span> &#9432;</strong></div>
        <div><span>Steep descent</span><strong class="clickable" onclick="showDescentInfo()">${String.format(Locale.ROOT, '%.2f km', steepDescentKm)} &#9432;</strong></div>
        <div><span>Trail strain</span><strong class="clickable" onclick="showTrailStrainInfo()">${String.format(Locale.ROOT, '%.0f/100', trailStrainScore)} &#9432;</strong></div>
    </div>
    <div class="mode-toggle">
        <span>Colour by:</span>
        <button type="button" id="mode-gradient-btn" class="mode-btn active" onclick="setColourMode('gradient')">Gradient</button>
        <button type="button" id="mode-strain-btn" class="mode-btn" onclick="setColourMode('strain')">Strain intensity</button>
    </div>
    <div style="position: relative;">
        <svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
            ${grid}
            <g id="mode-gradient">
                ${segments}
                ${legend}
            </g>
            <g id="mode-strain" style="display: none;">
                ${strainSegments}
                ${strainLegend}
            </g>
            <g id="solar-band">
                ${solarBand}
            </g>
            ${hitAreas}
            <g id="break-markers">
                ${breakMarkers}
            </g>
            <line id="crosshair" x1="0" y1="${padding}" x2="0" y2="${padding + plotHeight}" />
        </svg>
        <div id="tooltip"></div>
    </div>
    <p class="trail-data-note">Solar intensity band along the top of the chart: dark = shade/twilight, amber = partial sun, orange = full sun.</p>
    <p class="trail-data-note">${trailMatched ? 'Trail data: matched to OpenStreetMap (surface/SAC scale available).' : 'Trail data: no OpenStreetMap match (surface/SAC scale unknown).'}</p>
    <p class="trail-data-note">${dynamicResult.weatherMatched ? 'Weather data: live Open-Meteo forecast.' : 'Weather data: no forecast available (flat static temperature assumed).'}</p>
    <p class="trail-data-note">${breakEvents.isEmpty() ? 'No scheduled rest breaks for this run (see --break).' : "Dashed purple markers show ${breakEvents.size()} scheduled rest break(s) - hover for time and duration."}</p>
    <div id="difficulty-modal" class="modal-overlay" onclick="hideDifficultyInfo()">
        <div class="modal-box" onclick="event.stopPropagation()">
            <h2>Why "${difficulty}"?</h2>
            <p>${escapeXml(difficultyReason)}</p>
            <table>
                <tr><th>Metric</th><th>This route</th></tr>
                <tr><td>Steepest smoothed gradient</td><td>${String.format(Locale.ROOT, '%.1f%%', maxGrade)}</td></tr>
                <tr><td>Ascent rate</td><td>${String.format(Locale.ROOT, '%.0f m/km', ascentPerKm)}</td></tr>
                <tr><th colspan="2">Tier thresholds (either condition applies)</th></tr>
                <tr><td>Easy</td><td>&le; 12% gradient and &le; 30 m/km</td></tr>
                <tr><td>Moderate</td><td>&gt; 12% gradient or &gt; 30 m/km</td></tr>
                <tr><td>Difficult</td><td>&gt; 20% gradient or &gt; 60 m/km</td></tr>
            </table>
            <button onclick="hideDifficultyInfo()">Close</button>
        </div>
    </div>
    <div id="effort-modal" class="modal-overlay" onclick="hideEffortInfo()">
        <div class="modal-box" onclick="event.stopPropagation()">
            <h2>Why "${effortTier}"?</h2>
            <p>${escapeXml(effortReason)}</p>
            <p style="font-size: 12px; color: #666;">
                Unlike the Difficulty rating above (based on the steepest single point), this score
                combines the whole route's total ascent and total distance into one number, following
                Shenandoah National Park's hiking-difficulty formula.
            </p>
            <table>
                <tr><th>Metric</th><th>This route</th></tr>
                <tr><td>Score</td><td>${String.format(Locale.ROOT, '%.0f', shenandoahScore)}</td></tr>
                <tr><td>Ascent</td><td>${String.format(Locale.ROOT, '%.0f ft', ascentFt)} (${String.format(Locale.ROOT, '%.0f m', ascent)})</td></tr>
                <tr><td>Distance</td><td>${String.format(Locale.ROOT, '%.1f mi', distanceMi)} (${String.format(Locale.ROOT, '%.2f km', distanceKm)})</td></tr>
                <tr><th colspan="2">Score thresholds</th></tr>
                <tr><td>Easiest</td><td>&lt; 50</td></tr>
                <tr><td>Moderate</td><td>50-100</td></tr>
                <tr><td>Moderately strenuous</td><td>100-150</td></tr>
                <tr><td>Strenuous</td><td>150-200</td></tr>
                <tr><td>Very strenuous</td><td>&gt; 200</td></tr>
            </table>
            <button onclick="hideEffortInfo()">Close</button>
        </div>
    </div>
    <div id="water-modal" class="modal-overlay" onclick="hideWaterInfo()">
        <div class="modal-box" onclick="event.stopPropagation()">
            <h2>Water intake recommendation</h2>
            <p>${escapeXml(waterReason)}</p>
            ${waterChart}
            <div class="water-slider-row">
                <label for="temp-slider">Max forecast temperature (in shade)</label>
                <input type="range" id="temp-slider" min="0" max="45" step="1" value="${Math.round(waterTempCelsius) as int}" oninput="updateWaterSlider()" />
            </div>
            <div class="water-slider-row">
                <label>
                    <input type="checkbox" id="exposure-toggle" ${waterExposureFactor >= 1.05 ? 'checked' : ''} onchange="updateWaterSlider()" />
                    Fully exposed ridge (no shade)
                </label>
            </div>
            <table>
                <tr><th>Metric</th><th>Value</th></tr>
                <tr><td>Forecast temperature assumed</td><td><span id="temp-value">${Math.round(waterTempCelsius) as int}</span>&deg;C</td></tr>
                <tr><td>Calibrated burn rate</td><td><span id="water-rate">${String.format(Locale.ROOT, '%.2f', waterActiveHourlyRate)}</span> L/h</td></tr>
                <tr><td>Expected consumption</td><td><span id="water-consumption">${String.format(Locale.ROOT, '%.1f', waterConsumptionVolume)}</span> L</td></tr>
                <tr><td>Recommended carry (incl. ${String.format(Locale.ROOT, '%.1f', waterReserveVolume)} L reserve)</td><td><strong id="water-value">${String.format(Locale.ROOT, '%.1f', waterRecommendedCarry)}</strong> L</td></tr>
            </table>
            <button onclick="hideWaterInfo()">Close</button>
        </div>
    </div>
    <div id="descent-modal" class="modal-overlay" onclick="hideDescentInfo()">
        <div class="modal-box" onclick="event.stopPropagation()">
            <h2>Steep descent &amp; mechanical strain</h2>
            <p>
                Steep, technical descents load the quads and knees eccentrically (braking
                strain) far more on loose or uneven ground than on a smooth, paved surface at
                the same gradient. This splits every descent steeper than -15% by surface type
                to flag how much of it is likely to feel physically taxing rather than just long.
            </p>
            <table>
                <tr><th>Metric</th><th>This route</th></tr>
                <tr><td>Total steep descent (&lt; -15%)</td><td>${String.format(Locale.ROOT, '%.2f km', steepDescentKm)}</td></tr>
                <tr><td>&nbsp;&nbsp;- Smooth (paved/asphalt)</td><td>${String.format(Locale.ROOT, '%.2f km', smoothSteepDescentKm)}</td></tr>
                <tr><td>&nbsp;&nbsp;- Rough (trail/unknown)</td><td>${String.format(Locale.ROOT, '%.2f km', roughSteepDescentKm)}</td></tr>
            </table>
            <p style="font-size: 12px; color: #666;">
                ${trailMatched
                    ? 'Surface type comes from the nearest OpenStreetMap way within 30 m, fetched via the Overpass API.'
                    : 'No OpenStreetMap match is available for this route, so surface type could not be determined and every steep descent is counted as "rough" by default.'}
            </p>
            <button onclick="hideDescentInfo()">Close</button>
        </div>
    </div>
    <div id="trail-strain-modal" class="modal-overlay" onclick="hideTrailStrainInfo()">
        <div class="modal-box" onclick="event.stopPropagation()">
            <h2>Trail strain score</h2>
            <p>${escapeXml(trailStrainReason)}</p>
            <table>
                <tr><th>Metric</th><th>This route</th></tr>
                <tr><td>Effort distance</td><td>${String.format(Locale.ROOT, '%.2f km', effortDistanceKm)}</td></tr>
                <tr><td>Actual distance</td><td>${String.format(Locale.ROOT, '%.2f km', distanceKm)}</td></tr>
                <tr><td>Braking load index</td><td>${String.format(Locale.ROOT, '%.0f', totalBrakingIndex)}</td></tr>
                <tr><td>High-strain descent</td><td>${String.format(Locale.ROOT, '%.2f km', highStrainDescentKm)}</td></tr>
                <tr><th colspan="2">Surface breakdown (% of distance)</th></tr>
                ${surfaceBreakdown.collect { entry -> "<tr><td>${escapeXml(entry.surface as String)}</td><td>${String.format(Locale.ROOT, '%.1f%%', entry.pct as double)}</td></tr>" }.join('\n                ')}
            </table>
            <p style="font-size: 12px; color: #666;">
                Toggle the profile above to "Strain intensity" to see where the effort/impact is
                concentrated along the route, rather than just the raw gradient.
            </p>
            <button onclick="hideTrailStrainInfo()">Close</button>
        </div>
    </div>
    <div id="duration-modal" class="modal-overlay" onclick="hideDurationInfo()">
        <div class="modal-box" onclick="event.stopPropagation()">
            <h2>Duration models compared</h2>
            <p>${escapeXml(durationReason)}</p>
            <div class="water-slider-row">
                <label for="speed-slider">Base flat walking speed (<span id="speed-value">${String.format(Locale.ROOT, '%.1f', durationBaseSpeedKmh)}</span> km/h)</label>
                <input type="range" id="speed-slider" min="2.5" max="6.5" step="0.1" value="${String.format(Locale.ROOT, '%.1f', durationBaseSpeedKmh)}" oninput="updateDurationSlider()" />
            </div>
            <table>
                <tr><th>Metric</th><th>Standard DIN 33466</th><th>OSM Terrain &amp; Grade Adjusted</th></tr>
                <tr><td>Duration</td><td>${formatDuration(durationDinHours)}</td><td><span id="duration-effort-value">${formatDuration(durationEffortHours)}</span></td></tr>
                <tr><td>Pace</td><td>${String.format(Locale.ROOT, '%.1f km/h', durationDinPaceKmh)}</td><td><span id="duration-effort-pace">${String.format(Locale.ROOT, '%.1f km/h', durationEffortPaceKmh)}</span></td></tr>
                <tr><td>Hydration need (incl. ${String.format(Locale.ROOT, '%.1f', durationReserveVolume)} L reserve)</td><td>${String.format(Locale.ROOT, '%.1f L', durationDinConsumption)}</td><td><span id="duration-effort-consumption">${String.format(Locale.ROOT, '%.1f L', durationEffortConsumption)}</span></td></tr>
            </table>
            <p id="duration-delta-text" style="font-size: 12px; color: #666;">
                ${durationDeltaMinutes >= 0
                    ? "The terrain-adjusted estimate is ${Math.round(durationDeltaMinutes) as int} min longer than DIN 33466."
                    : "The terrain-adjusted estimate is ${Math.round(-durationDeltaMinutes) as int} min shorter than DIN 33466."}
            </p>
            <table>
                <tr><th colspan="2">Simulated weather &amp; solar exposure</th></tr>
                <tr><td>Start time</td><td>${dynamicStartTime}</td></tr>
                <tr><td>Estimated finish time (door-to-door)</td><td>${dynamicFinishTime}</td></tr>
                <tr><td>Elapsed, incl. breaks</td><td>${formatDuration(dynamicElapsedHours)} (${dynamicBreakCount} break${dynamicBreakCount == 1 ? '' : 's'}, ${Math.round(dynamicBreakDurationHours * 60.0) as int} min paused)</td></tr>
                <tr><td>Start / peak temperature</td><td>${String.format(Locale.ROOT, '%.1f / %.1f degC', dynamicStartTemp, dynamicPeakTemp)}${dynamicPeakTemp - dynamicPeakTempNoBreaks > 0.5 ? String.format(Locale.ROOT, ' (%.1f degC hotter than without breaks)', dynamicPeakTemp - dynamicPeakTempNoBreaks) : ''}</td></tr>
                <tr><td>Max solar radiation</td><td>${String.format(Locale.ROOT, '%.0f W/m2', dynamicMaxRadiation)}</td></tr>
                <tr><th colspan="2">Hydration breakdown</th></tr>
                <tr><td>Active moving</td><td>${String.format(Locale.ROOT, '%.1f L', dynamicMovingWaterLitres)}</td></tr>
                <tr><td>Break / resting</td><td>${String.format(Locale.ROOT, '%.1f L', dynamicBreakWaterLitres)}</td></tr>
                <tr><td>Safety reserve</td><td>${String.format(Locale.ROOT, '%.1f L', dynamicReserveVolume)}</td></tr>
                <tr><td><strong>Recommended total carry</strong></td><td><strong>${String.format(Locale.ROOT, '%.1f L', dynamicCarry)}</strong> (vs ${String.format(Locale.ROOT, '%.1f L', dynamicStaticCarry)} flat static)</td></tr>
            </table>
            <p style="font-size: 12px; color: #666;">${escapeXml(dynamicReason)}</p>
            <p style="font-size: 11px; color: #999;">
                Start/finish time and the weather figures reflect the base speed set on the command
                line; the slider above only rescales the terrain-adjusted duration/pace/hydration
                figures, not the simulated clock.
            </p>
            <button onclick="hideDurationInfo()">Close</button>
        </div>
    </div>
    <script>
        var tooltip = document.getElementById('tooltip');
        var crosshair = document.getElementById('crosshair');

        function showTip(evt) {
            var tip = evt.target.getAttribute('data-tip');
            tooltip.textContent = tip;
            tooltip.style.display = 'block';
            tooltip.style.left = (evt.pageX + 12) + 'px';
            tooltip.style.top = (evt.pageY + 12) + 'px';

            var x = evt.target.getAttribute('data-x');
            if (x !== null) {
                crosshair.setAttribute('x1', x);
                crosshair.setAttribute('x2', x);
                crosshair.style.display = 'block';
            }
        }

        function hideTip() {
            tooltip.style.display = 'none';
            crosshair.style.display = 'none';
        }

        var modeGradient = document.getElementById('mode-gradient');
        var modeStrain = document.getElementById('mode-strain');
        var modeGradientBtn = document.getElementById('mode-gradient-btn');
        var modeStrainBtn = document.getElementById('mode-strain-btn');

        function setColourMode(mode) {
            var strainActive = mode === 'strain';
            modeStrain.style.display = strainActive ? 'block' : 'none';
            modeGradient.style.display = strainActive ? 'none' : 'block';
            modeStrainBtn.classList.toggle('active', strainActive);
            modeGradientBtn.classList.toggle('active', !strainActive);
        }

        var difficultyModal = document.getElementById('difficulty-modal');

        function showDifficultyInfo() {
            difficultyModal.classList.add('open');
        }

        function hideDifficultyInfo() {
            difficultyModal.classList.remove('open');
        }

        var effortModal = document.getElementById('effort-modal');

        function showEffortInfo() {
            effortModal.classList.add('open');
        }

        function hideEffortInfo() {
            effortModal.classList.remove('open');
        }

        // Shared state and persistence for the water and duration sliders: changing one
        // keeps the other's dependent figures (and the header tiles) in sync, and both are
        // saved together so a reopened report remembers the last-used settings.
        var currentActiveHourlyRate = ${String.format(Locale.ROOT, '%.4f', waterActiveHourlyRate)};
        var PREFS_KEY = 'elevationProfilerPrefs';

        function loadPrefs() {
            try {
                var raw = localStorage.getItem(PREFS_KEY);
                return raw ? JSON.parse(raw) : {};
            } catch (e) {
                return {};
            }
        }

        function savePrefs() {
            try {
                localStorage.setItem(PREFS_KEY, JSON.stringify({
                    tempC: parseFloat(tempSlider.value),
                    exposureChecked: exposureToggle.checked,
                    speedKmh: parseFloat(speedSlider.value)
                }));
            } catch (e) {
                // localStorage may be unavailable in some contexts; settings simply won't persist.
            }
        }

        function formatDurationJs(hours) {
            var totalMinutes = Math.round(hours * 60);
            var h = Math.floor(totalMinutes / 60);
            var m = totalMinutes % 60;
            return h + 'h ' + (m < 10 ? '0' : '') + m + 'min';
        }

        var waterModal = document.getElementById('water-modal');
        var waterDurationHours = ${String.format(Locale.ROOT, '%.4f', durationHours)};
        var waterReserveVolume = ${String.format(Locale.ROOT, '%.2f', waterReserveVolume)};
        var waterChartTemps = ${waterChartData.collect { it.tempC as double }.collect { String.format(Locale.ROOT, '%.0f', it) }};
        var waterMaxScaleLitres = ${String.format(Locale.ROOT, '%.2f', waterMaxScaleLitres)};
        var waterChartHeight = 140;
        var waterChartBaselinePad = 22;
        var waterChartTopPad = 34;

        function showWaterInfo() {
            waterModal.classList.add('open');
        }

        function hideWaterInfo() {
            waterModal.classList.remove('open');
        }

        // Calibrated Zone 2 endurance model, mirrored from the Groovy calculation so the
        // slider and exposure toggle can recompute live in the browser.
        function hourlyRateForTemp(tempC) {
            return 0.35 + Math.max(0.0, tempC - 15.0) * 0.02;
        }

        function carryForTemp(tempC, exposureFactor) {
            var rate = hourlyRateForTemp(tempC) * exposureFactor;
            return Math.round((waterDurationHours * rate + waterReserveVolume) * 10) / 10;
        }

        var tempSlider = document.getElementById('temp-slider');
        var exposureToggle = document.getElementById('exposure-toggle');
        var tempValueEl = document.getElementById('temp-value');
        var waterRateEl = document.getElementById('water-rate');
        var waterConsumptionEl = document.getElementById('water-consumption');
        var waterValueEl = document.getElementById('water-value');

        var waterTileTempEl = document.getElementById('water-tile-temp');
        var waterTileValueEl = document.getElementById('water-tile-value');

        function updateWaterSlider() {
            var tempC = parseFloat(tempSlider.value);
            var exposureFactor = exposureToggle.checked ? 1.1 : 1.0;
            var activeHourlyRate = hourlyRateForTemp(tempC) * exposureFactor;
            var consumption = waterDurationHours * activeHourlyRate;
            var recommendedCarry = Math.round((consumption + waterReserveVolume) * 10) / 10;

            tempValueEl.textContent = tempC.toFixed(0);
            waterRateEl.textContent = activeHourlyRate.toFixed(2);
            waterConsumptionEl.textContent = consumption.toFixed(1);
            waterValueEl.textContent = recommendedCarry.toFixed(1);
            waterTileTempEl.textContent = tempC.toFixed(0);
            waterTileValueEl.textContent = recommendedCarry.toFixed(1) + ' L';

            for (var i = 0; i < waterChartTemps.length; i++) {
                var chartTemp = parseFloat(waterChartTemps[i]);
                var litres = carryForTemp(chartTemp, exposureFactor);
                var barHeight = Math.max(4, Math.round((litres / waterMaxScaleLitres) * (waterChartHeight - waterChartTopPad)));
                var bar = document.getElementById('water-bar-' + i);
                var label = document.getElementById('water-bar-label-' + i);
                if (bar) {
                    bar.setAttribute('height', barHeight);
                    bar.setAttribute('y', waterChartHeight - waterChartBaselinePad - barHeight);
                    bar.style.opacity = (Math.abs(chartTemp - tempC) <= 3) ? '1' : '0.5';
                }
                if (label) {
                    label.textContent = litres.toFixed(1);
                    label.setAttribute('y', waterChartHeight - waterChartBaselinePad - barHeight - 5);
                }
            }

            currentActiveHourlyRate = activeHourlyRate;
            savePrefs();
            updateDurationSlider();
        }

        var descentModal = document.getElementById('descent-modal');

        function showDescentInfo() {
            descentModal.classList.add('open');
        }

        function hideDescentInfo() {
            descentModal.classList.remove('open');
        }

        var trailStrainModal = document.getElementById('trail-strain-modal');

        function showTrailStrainInfo() {
            trailStrainModal.classList.add('open');
        }

        function hideTrailStrainInfo() {
            trailStrainModal.classList.remove('open');
        }

        var durationModal = document.getElementById('duration-modal');

        function showDurationInfo() {
            durationModal.classList.add('open');
        }

        function hideDurationInfo() {
            durationModal.classList.remove('open');
        }

        // The effort-adjusted duration is exactly inversely proportional to the base speed
        // (every segment's time is distance / (base_speed * slope_factor / (eta * T-factor)),
        // and base_speed factors out of the whole sum), so the slider can rescale the
        // server-computed duration directly without re-integrating every segment in JS.
        var durationBaseSpeedKmh = ${String.format(Locale.ROOT, '%.2f', durationBaseSpeedKmh)};
        var durationBaseEffortHours = ${String.format(Locale.ROOT, '%.4f', durationEffortHours)};
        var durationDinHoursJs = ${String.format(Locale.ROOT, '%.4f', durationDinHours)};
        var durationDistanceKmJs = ${String.format(Locale.ROOT, '%.2f', distanceKm)};
        var durationReserveVolumeJs = ${String.format(Locale.ROOT, '%.2f', durationReserveVolume)};

        var speedSlider = document.getElementById('speed-slider');
        var speedValueEl = document.getElementById('speed-value');
        var durationEffortValueEl = document.getElementById('duration-effort-value');
        var durationEffortPaceEl = document.getElementById('duration-effort-pace');
        var durationEffortConsumptionEl = document.getElementById('duration-effort-consumption');
        var durationDeltaTextEl = document.getElementById('duration-delta-text');
        var durationTileValueEl = document.getElementById('duration-tile-value');

        function updateDurationSlider() {
            var speedKmh = parseFloat(speedSlider.value);
            var effortHours = durationBaseEffortHours * (durationBaseSpeedKmh / speedKmh);
            var effortPaceKmh = effortHours > 0 ? durationDistanceKmJs / effortHours : 0;
            var effortConsumption = effortHours * currentActiveHourlyRate + durationReserveVolumeJs;
            var deltaMinutes = (effortHours - durationDinHoursJs) * 60;

            speedValueEl.textContent = speedKmh.toFixed(1);
            durationEffortValueEl.textContent = formatDurationJs(effortHours);
            durationEffortPaceEl.textContent = effortPaceKmh.toFixed(1) + ' km/h';
            durationEffortConsumptionEl.textContent = effortConsumption.toFixed(1) + ' L';
            durationDeltaTextEl.textContent = deltaMinutes >= 0
                ? 'The terrain-adjusted estimate is ' + Math.round(deltaMinutes) + ' min longer than DIN 33466.'
                : 'The terrain-adjusted estimate is ' + Math.round(-deltaMinutes) + ' min shorter than DIN 33466.';
            durationTileValueEl.textContent = formatDurationJs(effortHours) + ' (' + effortPaceKmh.toFixed(1) + ' km/h)';

            savePrefs();
        }

        // Restore any previously saved slider settings before the first render, then
        // refresh every dependent value (water tile, duration tile, both modals) once so
        // everything on screen is consistent with the restored state from the start.
        var savedPrefs = loadPrefs();
        if (typeof savedPrefs.tempC === 'number') {
            tempSlider.value = savedPrefs.tempC;
        }
        if (typeof savedPrefs.exposureChecked === 'boolean') {
            exposureToggle.checked = savedPrefs.exposureChecked;
        }
        if (typeof savedPrefs.speedKmh === 'number') {
            speedSlider.value = savedPrefs.speedKmh;
        }
        updateWaterSlider();

        document.addEventListener('keydown', function (evt) {
            if (evt.key === 'Escape') {
                hideDifficultyInfo();
                hideEffortInfo();
                hideWaterInfo();
                hideDescentInfo();
                hideTrailStrainInfo();
                hideDurationInfo();
            }
        });
    </script>
</body>
</html>
"""
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

if (!options.gpxFile.exists()) {
    System.err.println("Input file not found: ${options.gpxFile}")
    System.exit(1)
}

// -e/--exposure defaults to 1.0, which is indistinguishable from a genuinely computed
// dynamic exposure of 1.0 - so explicit presence on the command line (not just the value)
// is what triggers treating it as a constant override for the dynamic solar model.
boolean exposureExplicit = cmd.getParseResult().matchedOptions().any { it.names().contains('-e') }

LocalTime startLocalTime
try {
    startLocalTime = LocalTime.parse(options.startTime, DateTimeFormatter.ofPattern('HH:mm'))
} catch (Exception ex) {
    System.err.println("Invalid --start-time '${options.startTime}', expected HH:mm; using 07:00.")
    startLocalTime = LocalTime.of(7, 0)
}

LocalDate today = LocalDate.now()
LocalDate startLocalDate = today
if (options.date) {
    try {
        startLocalDate = LocalDate.parse(options.date, DateTimeFormatter.ofPattern('yyyy-MM-dd'))
    } catch (Exception ex) {
        System.err.println("Invalid --date '${options.date}', expected yyyy-MM-dd; using today.")
        startLocalDate = today
    }
}

// Past dates are served by the Archive API and future dates by the Forecast API (which
// only extends 16 days ahead) - only dates further out than that can't be simulated.
boolean isPastDate = startLocalDate.isBefore(today)
boolean dateSupported = isPastDate || !startLocalDate.isAfter(today.plusDays(16))
if (!dateSupported) {
    System.err.println("--date ${startLocalDate} is more than 16 days ahead; Open-Meteo's forecast doesn't reach that far, so weather simulation is disabled for this run (using static -t/--temp instead).")
}

LocalDateTime startDateTime = LocalDateTime.of(startLocalDate, startLocalTime)

Map breakSpec
try {
    breakSpec = parseBreakSpec(options.breakSpec)
} catch (Exception ex) {
    System.err.println("Invalid --break '${options.breakSpec}' (${ex.message}); using 60:5.")
    breakSpec = [intervalMin: 60, durationMin: 5]
}
int breakIntervalMin = breakSpec.intervalMin as int
int breakDurationMin = breakSpec.durationMin as int
boolean breaksEnabled = breakIntervalMin > 0 && breakDurationMin > 0
double breakIntervalHours = breakIntervalMin / 60.0
double breakDurationHoursEach = breakDurationMin / 60.0

List<Map> points = parseGpx(options.gpxFile)
if (points.size() < 2) {
    System.err.println('GPX file must contain at least two track points.')
    System.exit(1)
}

// Map data is kept out of the GPX file's own directory (and out of git - see .gitignore)
// since it's large, regeneratable, third-party-derived cache data rather than source data.
File mapsDir = new File(options.gpxFile.absoluteFile.parentFile, 'maps')
mapsDir.mkdirs()
File cacheFile = new File(mapsDir, options.gpxFile.name.replaceFirst(/(?i)\.gpx$/, '') + '.osm.json')
Map osmData = null
String matchSource = 'none'

if (!options.noCache && cacheFile.exists()) {
    osmData = new JsonSlurper().parse(cacheFile) as Map
    matchSource = 'cache'
    println "Loaded OpenStreetMap data from local cache: maps/${cacheFile.name}"
} else {
    try {
        Map bbox = computeBoundingBox(points)
        String rawJson = fetchOverpassRaw(bbox)
        cacheFile.text = rawJson
        osmData = new JsonSlurper().parseText(rawJson) as Map
        matchSource = 'api'
        println 'Fetched and cached OpenStreetMap data from Overpass API'
    } catch (Exception ex) {
        System.err.println("Overpass API request failed (${ex.message}); continuing without surface/SAC data.")
    }
}

List<Map> osmWays = parseOsmWays(osmData)
Map trailInfo = [matched: !osmWays.isEmpty(), source: matchSource]
double surfaceSnapThresholdM = 30.0

String weatherCacheBaseName = options.gpxFile.name.replaceFirst(/(?i)\.gpx$/, '')
// Only historic (Archive API) data is ever cached to disk: a past date's weather is fixed
// and permanently reusable, one file per date since each is a genuinely different, independent
// dataset - without that, querying a second past date would overwrite the first date's cache,
// forcing a re-fetch if you ever went back to it. The Forecast API is deliberately never
// cached, since a forecast is provisional and can change between two runs on the same day
// (or as the target date gets closer) - caching it risks silently acting on a stale forecast.
File weatherCacheFile = new File(mapsDir, "${weatherCacheBaseName}.weather.${startLocalDate}.json")
Map weatherData = null

if (!dateSupported) {
    // Already warned above; no point querying (or trusting a stale cache against) a date
    // Open-Meteo can't actually cover.
} else if (isPastDate) {
    Map cachedData = (!options.noCache && weatherCacheFile.exists()) ? new JsonSlurper().parse(weatherCacheFile) as Map : null
    Map cachedTimeline = cachedData ? parseWeatherTimeline(cachedData) : null

    // Belt-and-braces: the per-date filename already keeps historic caches from colliding,
    // but only trust it if it actually covers the currently requested date.
    if (cachedTimeline && timelineCoversDate(cachedTimeline, startLocalDate)) {
        weatherData = cachedData
        println "Loaded weather data from local cache: maps/${weatherCacheFile.name}"
    } else {
        try {
            Map centroid = computeCentroid(points)
            String rawJson = fetchWeatherArchiveRaw(centroid.lat as double, centroid.lon as double, startLocalDate)
            weatherCacheFile.text = rawJson
            weatherData = new JsonSlurper().parseText(rawJson) as Map
            println 'Fetched and cached weather data from Open-Meteo (Archive API)'
        } catch (Exception ex) {
            System.err.println("Open-Meteo request failed (${ex.message}); using static -t/--temp value with no solar radiation model.")
        }
    }
} else {
    try {
        Map centroid = computeCentroid(points)
        String rawJson = fetchWeatherForecastRaw(centroid.lat as double, centroid.lon as double)
        weatherData = new JsonSlurper().parseText(rawJson) as Map
        println 'Fetched live weather data from Open-Meteo (Forecast API, not cached)'
    } catch (Exception ex) {
        System.err.println("Open-Meteo request failed (${ex.message}); using static -t/--temp value with no solar radiation model.")
    }
}

Map weatherTimeline = parseWeatherTimeline(weatherData)
boolean weatherMatched = weatherTimeline != null

List<Double> smoothedEle = movingAverage(points.collect { it.ele as double }, options.window)
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

for (int i = 0; i < points.size(); i++) {
    Map wayInfo = nearestWayInfo(points[i].lat as double, points[i].lon as double, osmWays, surfaceSnapThresholdM)
    points[i].surface = wayInfo.surface
    points[i].sacScale = wayInfo.sacScale
    points[i].tracktype = wayInfo.tracktype
    points[i].highway = wayInfo.highway
    points[i].tunnel = wayInfo.tunnel
    points[i].covered = wayInfo.covered
    points[i].natural = wayInfo.natural
    points[i].landuse = wayInfo.landuse
    points[i].eta = terrainFactorForSurface(wayInfo.surface as String)
    points[i].speedEta = speedTerrainFactorForSurface(wayInfo.surface as String)
    points[i].tFactor = technicalFactorForSacScale(wayInfo.sacScale as String)
}

Set<String> smoothSurfaces = ['paved', 'asphalt'] as Set

double totalAscent = 0.0
double totalDescent = 0.0
double maxGrade = 0.0
double minGradeBaselineM = 10.0
double maxPlausibleGrade = 100.0
int clampedGradeCount = 0
double steepDescentDistanceM = 0.0
double smoothSteepDescentDistanceM = 0.0
double roughSteepDescentDistanceM = 0.0
double totalMetabolicCostM = 0.0
double totalBrakingIndex = 0.0
double highStrainDescentDistanceM = 0.0
double totalTerrainDurationHours = 0.0
double totalEffortDurationHours = 0.0
double totalDynamicWaterLitres = 0.0
double totalBreakDurationHours = 0.0
double totalBreakWaterLitres = 0.0
double movingTimeSinceLastBreakHours = 0.0
int breakCount = 0
List<Map> breakEvents = []
double wallClockElapsedHours = 0.0
double startTempEncountered = options.tempCelsius
double peakTempEncountered = options.tempCelsius
double peakTempWithoutBreaksEncountered = options.tempCelsius
double maxRadiationEncountered = 0.0
Map<String, Double> surfaceDistanceM = [:].withDefault { 0.0 }
points[0].grade = 0.0
points[0].strainFactor = 1.0
points[0].strainIntensity = 1.0
points[0].clockTime = startDateTime.format(DateTimeFormatter.ofPattern('HH:mm'))
points[0].ambientTemp = options.tempCelsius
points[0].radiation = 0.0
points[0].exposureFactor = 1.0
points[0].thermalPenaltyPct = 0.0
points[0].sunLabel = 'Shade/twilight'
int gradeRef = 0

if (weatherMatched) {
    Map startWeather = weatherAtTime(weatherTimeline, startDateTime)
    if (startWeather) {
        startTempEncountered = startWeather.temp as double
        peakTempEncountered = startTempEncountered
        peakTempWithoutBreaksEncountered = startTempEncountered
    }
}
for (int i = 1; i < points.size(); i++) {
    double deltaEle = (points[i].smoothedEle as double) - (points[i - 1].smoothedEle as double)
    if (deltaEle > 0) {
        totalAscent += deltaEle
    } else {
        totalDescent += -deltaEle
    }

    // Consecutive GPS fixes can sit only centimetres apart, so grade is measured
    // over at least minGradeBaselineM to avoid dividing by a near-zero distance.
    while (gradeRef < i - 1 && (points[i].distance as double) - (points[gradeRef + 1].distance as double) >= minGradeBaselineM) {
        gradeRef++
    }
    double baselineDist = (points[i].distance as double) - (points[gradeRef].distance as double)
    double baselineEle = (points[i].smoothedEle as double) - (points[gradeRef].smoothedEle as double)
    double grade = baselineDist > 0 ? (baselineEle / baselineDist) * 100.0 : 0.0

    // A route with clustered/duplicated waypoints can still yield a physically
    // implausible gradient; cap it rather than let bad source data misrepresent the route.
    if (Math.abs(grade) > maxPlausibleGrade) {
        clampedGradeCount++
        grade = Math.signum(grade) * maxPlausibleGrade
    }
    points[i].grade = grade
    maxGrade = Math.max(maxGrade, Math.abs(grade))

    // Mechanical descent strain: a steep, rough (unpaved/unknown surface) descent loads
    // the quads and knees eccentrically far more than the same gradient on a paved path.
    double segDist = (points[i].distance as double) - (points[i - 1].distance as double)
    String surface = (points[i].surface ?: 'unknown') as String
    if (grade < -15.0) {
        steepDescentDistanceM += segDist
        if (smoothSurfaces.contains(surface.toLowerCase())) {
            smoothSteepDescentDistanceM += segDist
        } else {
            roughSteepDescentDistanceM += segDist
        }
    }
    surfaceDistanceM[surface] = (surfaceDistanceM[surface] ?: 0.0) + segDist

    // Multi-factor trail strain model: metabolic cost (Minetti) and eccentric braking
    // strain, each scaled by the terrain factor (eta) and technical factor (T-factor) at
    // this point. Grade is stored as a percentage elsewhere, so it is converted to a
    // fraction here to match the model's expected units.
    double eta = points[i].eta as double
    double tFactor = points[i].tFactor as double
    double gradeFraction = grade / 100.0

    double gradeMult = minettiCostMultiplier(gradeFraction)
    double metabolicCost = segDist * gradeMult * eta * tFactor
    totalMetabolicCostM += metabolicCost

    double brakingIntensitySq = 0.0
    if (gradeFraction < -0.10) {
        double brakingIntensity = Math.abs(gradeFraction) / 0.10
        brakingIntensitySq = brakingIntensity * brakingIntensity
        totalBrakingIndex += segDist * brakingIntensitySq * eta * tFactor
    }

    if (gradeFraction < -0.15 && eta >= 1.25) {
        highStrainDescentDistanceM += segDist
    }

    points[i].strainFactor = gradeMult * eta * tFactor
    points[i].strainIntensity = eta * tFactor * (gradeMult + brakingIntensitySq)

    // Effort-adjusted duration: integrate a per-segment speed (the calibrated slope-response
    // curve, scaled by the calibrated speed-model terrain factor and the shared technical
    // factor) rather than DIN 33466's fixed rates. Uses speedEta, not the strain model's eta -
    // see speedTerrainFactorForSurface's comment for why the two are kept separate.
    double speedEta = points[i].speedEta as double
    double slopeFactor = slopeSpeedFactor(gradeFraction)
    double vSegEffort = options.speedKmh * slopeFactor / (speedEta * tFactor)
    double segDistKm = segDist / 1000.0

    // Dynamic solar exposure and thermal pace degradation: look up the forecast at the
    // break-delayed wall clock this segment is actually reached at (i.e. simulated,
    // pace- and break-dependent), not a single flat assumption for the whole hike.
    LocalDateTime segClock = startDateTime.plusSeconds(Math.round(wallClockElapsedHours * 3600.0))
    Map segWeather = weatherMatched ? weatherAtTime(weatherTimeline, segClock) : null
    double segTemp = segWeather ? segWeather.temp as double : options.tempCelsius
    double segRadiation = segWeather ? segWeather.radiation as double : 0.0

    // Shadow clock ignoring break delays, purely to report how much the scheduled breaks
    // shifted the peak temperature encountered - no other effect on the simulation.
    LocalDateTime noBreakClock = startDateTime.plusSeconds(Math.round(totalEffortDurationHours * 3600.0))
    Map noBreakWeather = weatherMatched ? weatherAtTime(weatherTimeline, noBreakClock) : null
    double noBreakTemp = noBreakWeather ? noBreakWeather.temp as double : options.tempCelsius
    peakTempWithoutBreaksEncountered = Math.max(peakTempWithoutBreaksEncountered, noBreakTemp)

    peakTempEncountered = Math.max(peakTempEncountered, segTemp)
    maxRadiationEncountered = Math.max(maxRadiationEncountered, segRadiation)

    double baseSunFactor = baseSunFactorForRadiation(segRadiation)
    String canopyClass = canopyClassFor(points[i])
    double dynamicExposure = effectiveExposureFor(canopyClass, baseSunFactor)
    double segExposure = exposureExplicit ? options.exposureFactor : dynamicExposure

    double effectiveHeat = segTemp + (segRadiation / 1000.0) * 2.0
    double thermalFactor = Math.max(0.65, 1.0 - Math.max(0.0, effectiveHeat - 15.0) * 0.008)
    double vSegThermal = vSegEffort * thermalFactor
    double tSegHours = segDistKm / vSegThermal

    totalTerrainDurationHours += segDistKm / vSegEffort
    totalEffortDurationHours += tSegHours
    wallClockElapsedHours += tSegHours
    movingTimeSinceLastBreakHours += tSegHours

    double segHourlyRate = (0.35 + Math.max(0.0, segTemp - 15.0) * 0.02) * segExposure
    totalDynamicWaterLitres += tSegHours * segHourlyRate

    points[i].clockTime = segClock.format(DateTimeFormatter.ofPattern('HH:mm'))
    points[i].ambientTemp = segTemp
    points[i].radiation = segRadiation
    points[i].exposureFactor = segExposure
    points[i].thermalPenaltyPct = (1.0 - thermalFactor) * 100.0
    points[i].sunLabel = sunLabelFor(canopyClass, segRadiation)

    // Scheduled resting breaks: triggered by pure moving time (not wall clock), so the
    // cadence is "every N minutes of walking" and doesn't drift from counting break time
    // towards itself. A while loop handles the rare case of a single long segment crossing
    // more than one break threshold.
    while (breaksEnabled && movingTimeSinceLastBreakHours >= breakIntervalHours) {
        LocalDateTime breakClock = startDateTime.plusSeconds(Math.round(wallClockElapsedHours * 3600.0))
        Map breakWeather = weatherMatched ? weatherAtTime(weatherTimeline, breakClock) : null
        double breakTemp = breakWeather ? breakWeather.temp as double : options.tempCelsius

        double restingHourlyRate = (0.15 + Math.max(0.0, breakTemp - 15.0) * 0.015) * segExposure
        double pauseWaterLitres = breakDurationHoursEach * restingHourlyRate
        totalBreakWaterLitres += pauseWaterLitres

        breakEvents << [
            distanceKm: (points[i].distance as double) / 1000.0,
            clockTime: breakClock.format(DateTimeFormatter.ofPattern('HH:mm')),
            durationMin: breakDurationMin
        ]

        wallClockElapsedHours += breakDurationHoursEach
        totalBreakDurationHours += breakDurationHoursEach
        movingTimeSinceLastBreakHours -= breakIntervalHours
        breakCount++
    }
}

Map descentStrain = [
    steepDescentDistanceM: steepDescentDistanceM,
    smoothSteepDescentDistanceM: smoothSteepDescentDistanceM,
    roughSteepDescentDistanceM: roughSteepDescentDistanceM
]

double totalDistanceKm = cumulative / 1000.0
double durationHours = din33466Duration(totalDistanceKm, totalAscent, totalDescent)
Map difficultyResult = classifyDifficulty(totalAscent, totalDistanceKm, maxGrade, clampedGradeCount)
Map shenandoahResult = shenandoahDifficulty(totalAscent, totalDistanceKm)
Map waterResult = waterIntakeRecommendation(durationHours, options.tempCelsius, options.exposureFactor)
Map trailStrain = trailStrainSummary(totalMetabolicCostM / 1000.0, totalDistanceKm, totalBrakingIndex, highStrainDescentDistanceM / 1000.0, surfaceDistanceM, cumulative)
Map durationResult = durationComparison(durationHours, totalEffortDurationHours, totalDistanceKm, waterResult.activeHourlyRate as double, options.speedKmh)
Map dynamicResult = dynamicWeatherSummary(
    startDateTime, durationHours, totalTerrainDurationHours, totalEffortDurationHours,
    wallClockElapsedHours, totalBreakDurationHours, breakCount,
    startTempEncountered, peakTempEncountered, peakTempWithoutBreaksEncountered, maxRadiationEncountered,
    totalDynamicWaterLitres, totalBreakWaterLitres, waterResult.recommendedCarry as double, weatherMatched
)

printSummary(totalDistanceKm, totalAscent, totalDescent, durationHours, difficultyResult, shenandoahResult, waterResult, trailInfo, descentStrain, trailStrain, durationResult, dynamicResult)

File output = options.outputPath ? new File(options.outputPath) : new File(options.gpxFile.absoluteFile.parentFile, options.gpxFile.name.replaceFirst(/(?i)\.gpx$/, '') + '-profile.html')
output.text = buildHtml(points, totalDistanceKm, totalAscent, totalDescent, durationHours, difficultyResult, shenandoahResult, waterResult, trailInfo, descentStrain, trailStrain, durationResult, dynamicResult, breakEvents)
println "Elevation profile written to: ${output.absolutePath}"
