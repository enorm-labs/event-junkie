package de.norm.events

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EventsBffApplication

@Suppress("SpreadOperator") // runApplication is vararg-only; this is Spring Boot's own entry point.
fun main(args: Array<String>) {
    runApplication<EventsBffApplication>(*args)
}
