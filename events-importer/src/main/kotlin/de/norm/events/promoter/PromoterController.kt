package de.norm.events.promoter

import de.norm.events.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Admin REST controller for managing promoters.
 */
@RestController
@RequestMapping("/api/admin/promoters")
@Tag(name = "Admin: Promoters", description = "Admin CRUD endpoints for managing promoters")
class PromoterController(
    private val promoterService: PromoterService
) {
    @GetMapping
    @Operation(
        summary =
            "List all promoters with pagination; `reviewed=false` lists the rows nobody looked at yet, " +
                "and `name` narrows to the names that contain it, ignoring case"
    )
    suspend fun findAll(
        @ParameterObject
        @PageableDefault(size = 20, sort = ["name"]) pageable: Pageable,
        @Parameter(description = "`false` lists the promoters nobody reviewed yet, `true` the reviewed ones. Omit it to list all.")
        @RequestParam(required = false) reviewed: Boolean?,
        @Parameter(description = "Part of the name, any letter case. Omit it to list all.", example = "concerts")
        @RequestParam(required = false) name: String?
    ): PageResponse<PromoterResponse> = promoterService.findAll(pageable, reviewed, name)

    @GetMapping("/{id}")
    @Operation(summary = "Get a single promoter by ID")
    suspend fun findById(
        @Parameter(description = "Database ID of the promoter.", example = "1")
        @PathVariable id: Long
    ): PromoterResponse = promoterService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new promoter")
    suspend fun create(
        @Valid @RequestBody request: PromoterRequest
    ): PromoterResponse = promoterService.create(request)

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing promoter")
    suspend fun update(
        @Parameter(description = "Database ID of the promoter.", example = "1")
        @PathVariable id: Long,
        @Valid @RequestBody request: PromoterRequest
    ): PromoterResponse = promoterService.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a promoter by ID")
    suspend fun delete(
        @Parameter(description = "Database ID of the promoter.", example = "1")
        @PathVariable id: Long
    ) {
        promoterService.delete(id)
    }
}
