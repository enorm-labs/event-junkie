package de.norm.events.mail

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for outbound mail (ADR-054): one SMTP connection to Scaleway TEM, used by whatever
 * has to reach a visitor's inbox. Depends on nothing, so any module may send through it.
 */
@ApplicationModule(allowedDependencies = [])
class MailModule
