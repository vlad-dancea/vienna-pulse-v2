/// Brings Wiener Linien's live departures from their
/// [realtime API](https://www.data.gv.at/datasets/cfba4373-a654-3e0b-80f8-348738169f95?locale=de)
/// to the departure board.
///
/// The dataset's fair-use rules ask clients to leave at least 15 seconds between requests and to
/// ask only about the stops someone needs, or Wiener Linien block the client's address. So
/// [LiveDepartures] sends at most one request per round for every station the board has open, and
/// none while no station is open. [Platforms] knows which platforms to ask about, [MonitorClient]
/// asks, and [Matching] pairs the answers with the timetable's departures, since they carry no trip
/// id. [DepartureRecorder] keeps every answer for statistics of how punctual the lines are.
@NullMarked
package com.vladdancea.viennapulse.live;

import org.jspecify.annotations.NullMarked;
