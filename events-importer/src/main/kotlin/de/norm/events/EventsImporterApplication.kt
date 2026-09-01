package de.norm.events

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class EventsImporterApplication

fun main(args: Array<String>) {
    runApplication<EventsImporterApplication>(*args)
}
