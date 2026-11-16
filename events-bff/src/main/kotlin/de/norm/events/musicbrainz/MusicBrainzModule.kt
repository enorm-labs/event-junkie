package de.norm.events.musicbrainz

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the MusicBrainz artist search (#5214): the picker of a watch asks it, from the server, never from the
 * browser. It depends on `common` only. The account module asks it.
 */
@ApplicationModule(allowedDependencies = ["common"])
class MusicBrainzModule
