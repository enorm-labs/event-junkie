package de.norm.events.weather

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the weather forecast (ADR-057): the DWD MOSMIX forecast for Berlin, read by the BFF and held in memory.
 * It depends on `common` only. The event module asks it for the forecast of a start hour.
 */
@ApplicationModule(allowedDependencies = ["common"])
class WeatherModule
