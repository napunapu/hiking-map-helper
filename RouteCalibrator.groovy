#!/usr/bin/env groovy
@Grab('info.picocli:picocli:4.7.5')
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import groovy.xml.XmlSlurper
import groovy.json.JsonSlurper
import java.time.Instant

@Command(
    name = 'RouteCalibrator',
    description = 'Compares a planned GPX route against a recorded real-world GPX track and suggests calibrated model coefficients.',
    mixinStandardHelpOptions = true,
    version = '1.0'
)
class Options {

    @Parameters(index = '0', description = 'Planned route GPX file')
    File plannedGpx

    @Parameters(index = '1', description = 'Recorded (actual) GPX file, with per-point <time>')
    File recordedGpx

    @Option(names = ['-c', '--osm-cache'], description = 'Path to a cached Overpass OSM JSON response for the planned route (default: auto-detected as maps/<plannedBaseName>.osm.json next to the planned GPX, or a sibling <plannedBaseName>.osm.json)')
    String osmCachePath

    @Option(names = ['-s', '--speed'], description = 'Baseline flat walking speed in km/h used by the terrain-adjusted prediction model, matching ElevationProfiler.groovy (default: 4.0)')
    double speedKmh = 4.0

    @Option(names = ['-t', '--temp'], description = 'Constant ambient temperature in degC for the optional thermal-penalty check - a simplification, since this diagnostic does not re-fetch per-point historic weather (default: 20.0)')
    double tempCelsius = 20.0

    @Option(names = ['--break'], description = 'Modelled rest-break cadence as interval:duration in minutes, compared against the actual observed pauses (default: 60:5)')
    String breakSpec = '60:5'
}

// ----- shared model functions, mirrored from ElevationProfiler.groovy for consistency -----

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

String inferSurfaceFromHighway(String highway) {
    Set<String> paved = ['residential', 'primary', 'secondary', 'tertiary', 'unclassified', 'living_street', 'service', 'trunk', 'motorway'] as Set
    (highway && paved.contains(highway)) ? 'paved' : 'unknown'
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
        return [surface: 'unknown', sacScale: 'none']
    }

    String highway = (bestTags.highway ?: 'unknown') as String
    String surface = bestTags.surface ? (bestTags.surface as String) : inferSurfaceFromHighway(highway)
    String sacScale = bestTags.sac_scale ? (bestTags.sac_scale as String) : 'none'
    [surface: surface, sacScale: sacScale]
}

double din33466Duration(double distanceKm, double ascentM, double descentM) {
    double horizontalHours = distanceKm / 4.0
    double verticalHours = (ascentM / 400.0) + (descentM / 800.0)
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

// ----- planned-route model (mirrors ElevationProfiler.groovy's terrain+thermal model) -----

// Builds the planned route's own per-point distance, smoothed elevation, windowed-baseline
// grade (matching ElevationProfiler's 10 m minimum baseline, to avoid GPS-cluster spikes),
// OSM-derived surface/SAC/eta/T-factor, and the model's predicted per-segment speed/time.
Map buildPlannedModel(List<Map> points, List<Map> osmWays, double speedKmh, double tempCelsius) {
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
    for (int i = 0; i < points.size(); i++) {
        Map wayInfo = nearestWayInfo(points[i].lat as double, points[i].lon as double, osmWays, surfaceSnapThresholdM)
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

    for (int i = 1; i < points.size(); i++) {
        double deltaEle = (points[i].smoothedEle as double) - (points[i - 1].smoothedEle as double)
        if (deltaEle > 0) {
            totalAscent += deltaEle
        } else {
            totalDescent += -deltaEle
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

    double cumulative = 0.0
    points[0].distance = 0.0
    for (int i = 1; i < points.size(); i++) {
        double d = haversine(points[i - 1].lat as double, points[i - 1].lon as double, points[i].lat as double, points[i].lon as double)
        cumulative += d
        points[i].distance = cumulative
    }

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
if (!options.recordedGpx.exists()) {
    System.err.println("Recorded GPX file not found: ${options.recordedGpx}")
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
println "Recorded track : ${options.recordedGpx}"

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
Map plannedModel = buildPlannedModel(plannedPoints, osmWays, options.speedKmh, options.tempCelsius)

List<Map> recordedPoints = parseRecordedGpx(options.recordedGpx)
if (recordedPoints.size() < 2) {
    System.err.println('Recorded GPX file must contain at least two timestamped track points.')
    System.exit(1)
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

println ''
println '--- Recommended calibration adjustments ---'
double globalActualSpeed = avgSpeedKmh((recordedModel.totalDistanceKm as double) * 1000.0, (recordedModel.totalMovingTimeHours as double) * 3600.0)
double globalPredictedSpeed = avgSpeedKmh((plannedModel.totalDistanceKm as double) * 1000.0, (plannedModel.predictedTotalHours as double) * 3600.0)
double baseSpeedRatio = (flatActual > 0 && flatPredicted > 0) ? flatActual / flatPredicted : (globalPredictedSpeed > 0 ? globalActualSpeed / globalPredictedSpeed : 1.0)
double suggestedBaseSpeed = options.speedKmh * baseSpeedRatio
println String.format(Locale.ROOT, 'Suggested baseline flat speed (-s): %.2f km/h (currently %.2f km/h)', suggestedBaseSpeed, options.speedKmh)

// Eta is revised against the ALREADY-corrected baseline speed (not the original -s), since
// the two adjustments are applied together in the verification pass below - correcting eta
// against the original base speed would double-count the same gap that suggestedBaseSpeed
// has already closed.
double baseSpeedCorrectionRatio = suggestedBaseSpeed / options.speedKmh
println 'Suggested revised terrain multipliers (eta), by surface bracket:'
SURFACE_BRACKET_ORDER.each { bracket ->
    double distM = surfaceActualDistM[bracket] ?: 0.0
    if (distM <= 0) {
        return
    }
    double actual = avgSpeedKmh(distM, surfaceActualTimeS[bracket] ?: 0.0)
    double predicted = avgSpeedKmh(distM, surfacePredictedTimeS[bracket] ?: 0.0) * baseSpeedCorrectionRatio
    double currentEta = (surfaceEtaDistM[bracket] ?: 0.0) > 0 ? (surfaceEtaSum[bracket] as double) / (surfaceEtaDistM[bracket] as double) : 1.0
    double revisedEta = (actual > 0 && predicted > 0) ? currentEta * (predicted / actual) : currentEta
    println String.format(Locale.ROOT, '  - %-14s current eta ~%.2f -> suggested ~%.2f', bracket, currentEta, revisedEta)
}

double severeDescentRatio = (severeDescentActual > 0 && severeDescentPredicted > 0) ? severeDescentPredicted / severeDescentActual : 1.0
double moderateDescentActual = avgSpeedKmh(gradientActualDistM['Moderate descent (-15% to -5%)'] ?: 0.0, gradientActualTimeS['Moderate descent (-15% to -5%)'] ?: 0.0)
double moderateDescentPredicted = avgSpeedKmh(gradientActualDistM['Moderate descent (-15% to -5%)'] ?: 0.0, gradientPredictedTimeS['Moderate descent (-15% to -5%)'] ?: 0.0)
double moderateDescentRatio = (moderateDescentActual > 0 && moderateDescentPredicted > 0) ? moderateDescentPredicted / moderateDescentActual : 1.0
println String.format(Locale.ROOT, 'Suggested descent braking damping factor: severe descents x%.2f, moderate descents x%.2f (multiply the current Tobler-derived slope factor by this on negative grades to correct over/under-braking)', 1.0 / severeDescentRatio, 1.0 / moderateDescentRatio)

// Verification pass: re-apply the suggested base speed, per-surface-bracket eta correction,
// and descent braking damping to every planned segment, and report how close the
// recalculated total comes to actual.
double calibratedTotalHours = 0.0
for (int i = 1; i < plannedPoints.size(); i++) {
    Map pp = plannedPoints[i]
    Map prevPp = plannedPoints[i - 1]
    double segDistKm = ((pp.distance as double) - (prevPp.distance as double)) / 1000.0
    double grade = pp.grade as double
    double slopeFactor = slopeSpeedFactor(grade / 100.0)
    if (grade < -15.0) {
        slopeFactor *= (1.0 / severeDescentRatio)
    } else if (grade < -5.0) {
        slopeFactor *= (1.0 / moderateDescentRatio)
    }
    String bracket = surfaceBracketFor(pp.surface as String)
    double distM = surfaceActualDistM[bracket] ?: 0.0
    double actual = avgSpeedKmh(distM, surfaceActualTimeS[bracket] ?: 0.0)
    double predicted = avgSpeedKmh(distM, surfacePredictedTimeS[bracket] ?: 0.0) * baseSpeedCorrectionRatio
    double currentEta = (surfaceEtaDistM[bracket] ?: 0.0) > 0 ? (surfaceEtaSum[bracket] as double) / (surfaceEtaDistM[bracket] as double) : (pp.eta as double)
    double revisedEta = (actual > 0 && predicted > 0) ? currentEta * (predicted / actual) : (pp.eta as double)
    double tFactor = pp.tFactor as double
    double vSeg = suggestedBaseSpeed * slopeFactor / (revisedEta * tFactor)
    calibratedTotalHours += vSeg > 0 ? segDistKm / vSeg : 0.0
}
double calibratedDeltaPct = (recordedModel.totalMovingTimeHours as double) > 0
    ? ((calibratedTotalHours - (recordedModel.totalMovingTimeHours as double)) / (recordedModel.totalMovingTimeHours as double)) * 100.0
    : 0.0
println ''
println String.format(Locale.ROOT, 'With these adjustments, recalculated moving time is %s (%+.1f%% vs actual, was %+.1f%% before calibration).', formatDuration(calibratedTotalHours), calibratedDeltaPct, movingDeltaPct)
println (Math.abs(calibratedDeltaPct) <= 5.0
    ? 'Within the target +/-5% band.'
    : 'Still outside the target +/-5% band - consider further manual tuning of the brackets above.')
