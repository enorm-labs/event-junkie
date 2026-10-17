package de.norm.events.festival

import de.norm.events.common.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Admin REST controller for festivals (#3272). The public site reads them through the BFF. */
@RestController
@RequestMapping("/api/admin/festivals")
@Tag(name = "Admin: Festivals", description = "Admin CRUD endpoints for festivals, which the home page announces")
class FestivalController(
    private val festivalService: FestivalService
) {
    @GetMapping
    @Operation(summary = "List festivals with pagination, newest first by default")
    suspend fun findAll(
        @ParameterObject
        @PageableDefault(size = 20, sort = ["firstDay"], direction = Sort.Direction.DESC) pageable: Pageable
    ): PageResponse<FestivalResponse> = festivalService.findAll(pageable)

    @GetMapping("/{id}")
    @Operation(summary = "Get a single festival by ID")
    suspend fun findById(
        @Parameter(description = "Database ID of the festival.", example = "1")
        @PathVariable id: Long
    ): FestivalResponse = festivalService.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a festival")
    suspend fun create(
        @Valid @RequestBody request: FestivalRequest
    ): FestivalResponse = festivalService.create(request)

    @PutMapping("/{id}")
    @Operation(summary = "Replace a festival")
    suspend fun update(
        @Parameter(description = "Database ID of the festival.", example = "1")
        @PathVariable id: Long,
        @Valid @RequestBody request: FestivalRequest
    ): FestivalResponse = festivalService.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a festival by ID")
    suspend fun delete(
        @Parameter(description = "Database ID of the festival.", example = "1")
        @PathVariable id: Long
    ) {
        festivalService.delete(id)
    }
}
