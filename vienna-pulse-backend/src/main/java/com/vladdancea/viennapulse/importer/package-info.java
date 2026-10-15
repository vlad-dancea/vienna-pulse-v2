/// Imports the Wiener Linien timetable, published as a
/// [GTFS feed](https://gtfs.org/documentation/schedule/reference/): a ZIP of CSV files describing
/// every agency, route, stop and trip.
///
/// [ImportJob] runs the import every morning in three steps, one package each:
///
/// 1. [com.vladdancea.viennapulse.importer.download] downloads the feed and checks that every
///    file we need is there.
/// 2. [com.vladdancea.viennapulse.importer.read] parses each file into records, with one
///    subpackage per GTFS file. Currently reads `routes.txt` and `stops.txt`.
/// 3. [com.vladdancea.viennapulse.importer.store] replaces the timetable in PostgreSQL with the
///    records, all at once.
///
/// The steps do not know each other: only [ImportJob] connects them.
@NullMarked
package com.vladdancea.viennapulse.importer;

import org.jspecify.annotations.NullMarked;
