package de.norm.events.transit

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** The station import's admin route (#2724), under `/api/admin` like every admin endpoint, so no Ingress path reaches it. */
@RestController
@RequestMapping("/api/admin/transit-stops")
@Tag(name = "Admin: Transit stops", description = "The U-Bahn, S-Bahn and tram stations from VBB's GTFS feed")
class TransitStopController(
    private val service: TransitStopImportService,
    @Qualifier("ioDispatcher") ioDispatcher: CoroutineDispatcher
) : DisposableBean {
    /** Outlives the request, so an import is not cancelled when the caller's port-forward times out. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Starts one import now instead of at the next quarter. Returns at once; the import logs its result. */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Import the stations from VBB's GTFS feed once, in the background")
    fun runImport() {
        scope.launch { service.runOnce() }
    }

    override fun destroy() = scope.cancel()
}
