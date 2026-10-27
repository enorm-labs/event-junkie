package de.norm.events.push

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for Web Push (ADR-055): the payload encryption, the VAPID signature and the request to a browser's
 * push service. Knows nothing of accounts, so any module may send through it, as with `mail`.
 */
@ApplicationModule(allowedDependencies = [])
class PushModule
