/// Imports the Wiener Linien timetable, published as a
/// [GTFS feed](https://gtfs.org/documentation/schedule/reference/): a ZIP of CSV files describing
/// every agency, route, stop and trip.
///
/// [ImportJob] runs the import every morning in two stages:
///
/// 1. [com.vladdancea.viennapulse.importer.zip] downloads the feed and checks that every file we
///    need is there.
/// 2. [com.vladdancea.viennapulse.importer.ingestor] parses each file into records, with one
///    subpackage per GTFS file. Currently reads `routes.txt` and `stops.txt`.
///
/// Storing the records is not implemented yet.
@NullMarked
package com.vladdancea.viennapulse.importer;

import org.jspecify.annotations.NullMarked;
