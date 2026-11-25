package de.norm.events.mail

/**
 * Mail properties that keep the client from resolving the machine's own name, for `EHLO` and for the `Message-ID`. Both lookups
 * run on an open connection, and GreenMail closes one that is silent for 30 s, so a slow resolver failed the send (#5535).
 */
val NO_HOSTNAME_LOOKUP = mapOf("mail.smtp.localhost" to "localhost", "mail.from" to "noreply@example.org")
