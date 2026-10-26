package com.vladdancea.viennapulse.live;

import de.siegmar.fastcsv.reader.CsvReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/// The platforms of every station in Wiener Linien's realtime API, from their list of platforms
/// (`wienerlinien-ogd-haltepunkte.csv`), and the codes that signs show for some of them, from their
/// list of platform codes (`wienerlinien-ogd-steige.csv`), kept in memory.
@Component
@Slf4j
public class Platforms {
    // The merged stop ids of Vienna's stops, like `at:49:1320`, whose number is the station's.
    private static final Pattern VIENNA_STOP = Pattern.compile("at:49:(\\d{1,6})");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,9}");
    // Wiener Linien number their stations, which they call DIVA numbers, from 60200000.
    private static final int FIRST_STATION = 60_200_000;
    private static final Duration MAX_AGE = Duration.ofDays(1);

    private final LiveProperties properties;
    private final RestClient client;
    private final Clock clock;

    // Replaced whole by each load and read by requests and rounds on other threads.
    private volatile @Nullable Loaded loaded;

    public Platforms(LiveProperties properties, RestClient.Builder clientBuilder, Clock clock) {
        this.properties = properties;
        this.client = clientBuilder.build();
        this.clock = clock;
    }

    /// The number of the station of the stop with the merged id `stopId`, like `at:49:1320`, or
    /// `null` for a stop outside Vienna, whose id starts with `at:43:`. The list has the stations of
    /// 1,755 of the 1,798 stops on the map: the 35 outside Vienna and 8 others, such as Valentingasse,
    /// are missing.
    static @Nullable Integer station(String stopId) {
        var match = VIENNA_STOP.matcher(stopId);
        return match.matches() ? FIRST_STATION + Integer.parseInt(match.group(1)) : null;
    }

    /// The numbers of the station's platforms, empty for an unknown station or while there is no list.
    List<Integer> of(int station) {
        var current = loaded;
        return current == null ? List.of() : current.platforms().getOrDefault(station, List.of());
    }

    /// The station of the platform with the number `platform`, or `null` if the list lacks it.
    @Nullable
    Integer stationOf(int platform) {
        var current = loaded;
        return current == null ? null : current.stations().get(platform);
    }

    /// The code that signs show for the platform with the number `platform`, such as `1` for a U-Bahn
    /// platform or `C` for one of the tram stops at a large station, or `null` for a platform without
    /// one.
    public @Nullable String code(int platform) {
        var current = loaded;
        return current == null ? null : current.codes().get(platform);
    }

    /// Loads the lists when they are missing or a day old. It runs at startup and then every hour, so a
    /// failed load is tried again within the hour, while the last lists, if any, stay in use.
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    void refresh() {
        var current = loaded;
        if (current == null
                || current.codes().isEmpty()
                || !clock.instant().isBefore(current.at().plus(MAX_AGE))) {
            load();
        }
    }

    /// Downloads the lists and replaces the ones in memory, or keeps them if that fails. The live times
    /// need only the platforms, so they load even when the codes fail to.
    public void load() {
        var current = loaded;
        try {
            var platforms = download(properties.platformsUrl());
            loaded = parse(platforms, codes(current), clock.instant());
            log.info(
                    "Loaded the platforms of {} stations and the codes of {} platforms",
                    loaded.platforms().size(),
                    loaded.codes().size());
        } catch (RuntimeException exception) {
            log.warn("Could not load the platforms from {}", properties.platformsUrl(), exception);
        }
    }

    // The codes from the list, or the last ones, if any, when it fails to load.
    private Map<Integer, String> codes(@Nullable Loaded current) {
        try {
            return parseCodes(download(properties.platformCodesUrl()));
        } catch (RuntimeException exception) {
            log.warn("Could not load the platform codes from {}", properties.platformCodesUrl(), exception);
            return current == null ? Map.of() : current.codes();
        }
    }

    private String download(URI list) {
        var csv = client.get().uri(list).retrieve().body(byte[].class);
        if (csv == null) {
            throw new IllegalStateException("The list at " + list + " is empty");
        }
        return new String(csv, StandardCharsets.UTF_8);
    }

    // Each row is a platform, `StopID;DIVA;StopText;...`, with the number of its station. Platforms out
    // of use, such as a bus stop for a diversion, have no station.
    private static Loaded parse(String csv, Map<Integer, String> codes, Instant at) {
        var platforms = new HashMap<Integer, List<Integer>>();
        var stations = new HashMap<Integer, Integer>();
        for (var row :
                CsvReader.builder().fieldSeparator(';').detectBomHeader(true).ofNamedCsvRecord(csv)) {
            var platform = row.getField("StopID").strip();
            var station = row.getField("DIVA").strip();
            if (NUMBER.matcher(platform).matches() && NUMBER.matcher(station).matches()) {
                platforms
                        .computeIfAbsent(Integer.parseInt(station), any -> new ArrayList<>())
                        .add(Integer.parseInt(platform));
                stations.put(Integer.parseInt(platform), Integer.parseInt(station));
            }
        }
        if (platforms.isEmpty()) {
            throw new IllegalStateException("The list of platforms has no rows");
        }
        platforms.replaceAll((station, numbers) -> List.copyOf(numbers));
        return new Loaded(Map.copyOf(platforms), Map.copyOf(stations), codes, at);
    }

    // Each row is a platform with a code, `StopID;Platform`, such as `4112;2`. The list has only the
    // platforms with a code: those of the U-Bahn and the stops at large stations, which signs tell
    // apart by letters.
    private static Map<Integer, String> parseCodes(String csv) {
        var codes = new HashMap<Integer, String>();
        for (var row :
                CsvReader.builder().fieldSeparator(';').detectBomHeader(true).ofNamedCsvRecord(csv)) {
            var platform = row.getField("StopID").strip();
            var code = row.getField("Platform").strip();
            if (NUMBER.matcher(platform).matches() && !code.isEmpty()) {
                codes.put(Integer.parseInt(platform), code);
            }
        }
        if (codes.isEmpty()) {
            throw new IllegalStateException("The list of platform codes has no rows");
        }
        return Map.copyOf(codes);
    }

    // The platforms of each station, the station of each platform, the code of each platform that has
    // one, and when the list of platforms was loaded.
    private record Loaded(
            Map<Integer, List<Integer>> platforms,
            Map<Integer, Integer> stations,
            Map<Integer, String> codes,
            Instant at) {}
}
