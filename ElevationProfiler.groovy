#!/usr/bin/env groovy
@Grab('info.picocli:picocli:4.7.5')
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import groovy.xml.XmlSlurper

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

String formatDuration(double hours) {
    int totalMinutes = Math.round(hours * 60.0) as int
    int h = totalMinutes.intdiv(60)
    int m = totalMinutes % 60
    String.format(Locale.ROOT, '%dh %02dmin', h, m)
}

void printSummary(double distanceKm, double ascent, double descent, double durationHours, Map difficultyResult, Map shenandoahResult) {
    println '=== Elevation profile summary ==='
    println String.format(Locale.ROOT, 'Total distance : %.2f km', distanceKm)
    println String.format(Locale.ROOT, 'Total ascent   : %.0f m', ascent)
    println String.format(Locale.ROOT, 'Total descent  : %.0f m', descent)
    println "Estimated time  : ${formatDuration(durationHours)} (DIN 33466)"
    println "Difficulty      : ${difficultyResult.tier}"
    println "Reason          : ${difficultyResult.reason}"
    println String.format(Locale.ROOT, 'Effort (Shenandoah): %.0f (%s)', shenandoahResult.score as double, shenandoahResult.tier)
    println "Reason          : ${shenandoahResult.reason}"
}

String gradeColour(double grade) {
    if (grade < 0.0) {
        return '#4FC3F7'
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
        ['#4FC3F7', 'Downhill (< 0%)'],
        ['#66BB6A', 'Flat / gentle (0-6%)'],
        ['#FDD835', 'Moderate (6-12%)'],
        ['#FB8C00', 'Steep (12-20%)'],
        ['#E53935', 'Very steep (> 20%)']
    ]
    int x = padding
    int y = height - 14
    StringBuilder sb = new StringBuilder()
    entries.eachWithIndex { entry, idx ->
        int ex = x + idx * 170
        sb << "<rect x=\"${ex}\" y=\"${y - 10}\" width=\"12\" height=\"12\" fill=\"${entry[0]}\" />\n"
        sb << "<text x=\"${ex + 16}\" y=\"${y}\" font-size=\"11\" fill=\"#333\">${entry[1]}</text>\n"
    }
    sb.toString()
}

String buildHtml(List<Map> points, double distanceKm, double ascent, double descent, double durationHours, Map difficultyResult, Map shenandoahResult) {
    String difficulty = difficultyResult.tier
    String difficultyReason = difficultyResult.reason
    double maxGrade = difficultyResult.maxGrade as double
    double ascentPerKm = difficultyResult.ascentPerKm as double
    String effortTier = shenandoahResult.tier
    String effortReason = shenandoahResult.reason
    double shenandoahScore = shenandoahResult.score as double
    double ascentFt = shenandoahResult.ascentFt as double
    double distanceMi = shenandoahResult.distanceMi as double
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
    StringBuilder hitAreas = new StringBuilder()

    for (int i = 1; i < points.size(); i++) {
        Map p0 = points[i - 1]
        Map p1 = points[i]
        double x0 = xFor(p0.distance as double)
        double x1 = xFor(p1.distance as double)
        double y0 = yFor(p0.smoothedEle as double)
        double y1 = yFor(p1.smoothedEle as double)
        double baseY = padding + plotHeight
        String colour = gradeColour(p1.grade as double)

        segments << "<polygon points=\"${fmt(x0)},${fmt(baseY)} ${fmt(x0)},${fmt(y0)} ${fmt(x1)},${fmt(y1)} ${fmt(x1)},${fmt(baseY)}\" fill=\"${colour}\" stroke=\"${colour}\" stroke-width=\"0.5\" />\n"

        double distKm = (p1.distance as double) / 1000.0
        String tooltip = String.format(Locale.ROOT, '%.2f km | %.0f m | %.1f%%', distKm, p1.smoothedEle as double, p1.grade as double)
        hitAreas << "<rect x=\"${fmt(Math.min(x0, x1))}\" y=\"${padding}\" width=\"${fmt(Math.max(1.0d, Math.abs(x1 - x0)))}\" height=\"${plotHeight}\" fill=\"transparent\" data-tip=\"${escapeXml(tooltip)}\" onmousemove=\"showTip(event)\" onmouseleave=\"hideTip()\" />\n"
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
        .clickable {
            cursor: pointer;
            text-decoration: underline dotted;
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
    </style>
</head>
<body>
    <h1>Elevation profile</h1>
    <div class="summary">
        <div><span>Distance</span><strong>${String.format(Locale.ROOT, '%.2f km', distanceKm)}</strong></div>
        <div><span>Ascent</span><strong>${String.format(Locale.ROOT, '%.0f m', ascent)}</strong></div>
        <div><span>Descent</span><strong>${String.format(Locale.ROOT, '%.0f m', descent)}</strong></div>
        <div><span>Estimated time</span><strong>${formatDuration(durationHours)}</strong></div>
        <div><span>Difficulty</span><strong class="clickable" onclick="showDifficultyInfo()">${difficulty} &#9432;</strong></div>
        <div><span>Effort (Shenandoah)</span><strong class="clickable" onclick="showEffortInfo()">${effortTier} &#9432;</strong></div>
    </div>
    <div style="position: relative;">
        <svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
            ${grid}
            ${segments}
            ${hitAreas}
            ${legend}
        </svg>
        <div id="tooltip"></div>
    </div>
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
    <script>
        var tooltip = document.getElementById('tooltip');

        function showTip(evt) {
            var tip = evt.target.getAttribute('data-tip');
            tooltip.textContent = tip;
            tooltip.style.display = 'block';
            tooltip.style.left = (evt.pageX + 12) + 'px';
            tooltip.style.top = (evt.pageY + 12) + 'px';
        }

        function hideTip() {
            tooltip.style.display = 'none';
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

        document.addEventListener('keydown', function (evt) {
            if (evt.key === 'Escape') {
                hideDifficultyInfo();
                hideEffortInfo();
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

List<Map> points = parseGpx(options.gpxFile)
if (points.size() < 2) {
    System.err.println('GPX file must contain at least two track points.')
    System.exit(1)
}

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

double totalAscent = 0.0
double totalDescent = 0.0
double maxGrade = 0.0
double minGradeBaselineM = 10.0
double maxPlausibleGrade = 100.0
int clampedGradeCount = 0
points[0].grade = 0.0
int gradeRef = 0
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
}

double totalDistanceKm = cumulative / 1000.0
double durationHours = din33466Duration(totalDistanceKm, totalAscent, totalDescent)
Map difficultyResult = classifyDifficulty(totalAscent, totalDistanceKm, maxGrade, clampedGradeCount)
Map shenandoahResult = shenandoahDifficulty(totalAscent, totalDistanceKm)

printSummary(totalDistanceKm, totalAscent, totalDescent, durationHours, difficultyResult, shenandoahResult)

File output = options.outputPath ? new File(options.outputPath) : new File(options.gpxFile.absoluteFile.parentFile, options.gpxFile.name.replaceFirst(/(?i)\.gpx$/, '') + '-profile.html')
output.text = buildHtml(points, totalDistanceKm, totalAscent, totalDescent, durationHours, difficultyResult, shenandoahResult)
println "Elevation profile written to: ${output.absolutePath}"
