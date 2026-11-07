# ADR-057: The BFF reads the DWD MOSMIX forecast for open-air events

## Status

**Accepted (2026-11-07). The BFF reads the DWD MOSMIX_L forecast of station 10384 (Berlin-Tempelhof) every six hours and holds it in memory. An event at an
open-air venue shows the weather, the temperature and the rain probability of its start hour. This applies from 1 May to 30 September, for a start in
the next seven days.**

Decided in [#2726](https://github.com/enorm-labs/event-junkie/issues/2726), option 2.

## Context

An open-air night depends on the weather, and a visitor who plans one checks a weather app. The page can show the forecast beside the start time. The
forecast must come from somewhere, and that source is a new outbound dependency.

The constraints:

- **No visitor data may leave.** A weather widget in the browser sends the visitor's IP address to a third party (AGENTS.md § Privacy & GDPR). So the
  server must fetch the forecast, and the request must name nothing about a visitor.
- **No new processor.** The forecast is public data. A request for it is not processing on our behalf, so it needs no Art. 28 contract.
- **No new store.** The forecast is valid for hours. A table for it adds a migration and a cleanup for data that nobody reads a day later.
- **The BFF already reaches the internet on 443.** `allow-object-storage` and `allow-push` in `templates/networkpolicy.yaml` open it on both clusters.

## Candidate options

1. **No weather.** The visitor checks a weather app.
2. **DWD open data, read by the BFF.** The Deutscher Wetterdienst publishes MOSMIX as open data, with a credit as its only condition.
3. **Bright Sky** (`api.brightsky.dev`), a JSON wrapper around the same DWD data. It is easier to parse, but it adds a third party between us and the DWD.
4. **A weather widget in the browser.** Rejected in advance, because it sends the visitor's IP address to a third party.

## Decision

**Option 2.**

- `WeatherForecasts` in the BFF module `weather` reads `MOSMIX_L_LATEST_10384.kmz` every six hours (`app.weather.refresh`). Every replica reads it itself.
- `MosmixForecast` reads three elements of the KML: `TTT` (temperature), `ww` (significant weather) and `R101` (probability of more than 0.1 mm of rain in
  the hour). The XML parser resolves no DTD and no external entity. It reads at most 8 MB of the archive.
- The response field `weather` on the event summary and the event detail holds a condition, a temperature in whole degrees Celsius and a rain probability.
  The condition is one of seven classes, reduced from the WMO code.
- The field is null unless all of these are true:
    - the venue has the type `open-air`
    - the event date is from 1 May to 30 September
    - the start hour, or else the doors hour, is from the current hour to seven days ahead
    - the forecast held was issued less than 24 hours ago, and it covers that hour
- The temperature is the one at the start of the hour. The condition and the rain probability are those of the step that ends the hour, because MOSMIX
  states both for the hour before a step.
- A failed read logs a `WARN` with the exception and keeps the forecast already held. After 24 hours without a good read, no event shows a forecast.
- `/legal/notices` credits the source in both languages: "Data: Deutscher Wetterdienst" and "Datenbasis: Deutscher Wetterdienst".

**The reason that settled it** is that the DWD is the source of the data itself. No third party sits between us and the DWD, and the request carries
nothing about a visitor.

## Consequences

- The BFF has one more outbound call. It sends a GET for a fixed URL with the project's User-Agent. The DWD receives the address of the cluster's node, and
  nothing else.
- The BFF egress on 443 comes from `allow-object-storage` and `allow-push`. If both of these stop being rendered, the read fails, logs a `WARN`, and the
  page shows no forecast. A cluster without internet access, such as a k3d rehearsal without egress, behaves the same way.
- The tag is on the venue, not on the event. An indoor night at an open-air venue in summer shows the forecast too. This is accepted.
- The page shows the forecast for one station. Berlin is small enough that the start-hour forecast of Tempelhof is a fair guide for every venue.
- The page cache (`app.api.cache.ttl-seconds`) can hold a response up to one minute after a new forecast. This is accepted.

## References

- [#2726](https://github.com/enorm-labs/event-junkie/issues/2726), the decision issue
- [DWD open data](https://opendata.dwd.de/weather/local_forecasts/mos/), the MOSMIX files
- [DWD MOSMIX element list](https://opendata.dwd.de/weather/lib/MetElementDefinition.xml)
- `docs/LEGAL.md` §7, the source paragraph
- `docs/security/THREAT_MODEL.md` § Outbound connections
