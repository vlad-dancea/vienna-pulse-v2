package com.vladdancea.viennapulse.live;

import com.fasterxml.jackson.annotation.JsonProperty;

/// Whether a stop's departures have live times: `pending` until a round has fetched its station,
/// `live` once one has, so a departure without a live time has no live data, and `unavailable`
/// when there is none, because the stop has no station in the realtime API or Wiener Linien failed
/// or blocked the backend.
public enum LiveStatus {
    @JsonProperty("pending")
    PENDING,
    @JsonProperty("live")
    LIVE,
    @JsonProperty("unavailable")
    UNAVAILABLE
}
