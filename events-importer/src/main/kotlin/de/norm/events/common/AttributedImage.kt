package de.norm.events.common

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/** Longest image credit a request may carry. */
const val IMAGE_ATTRIBUTION_MAX_LENGTH = 500

/** Longest image licence identifier a request may carry. */
const val IMAGE_LICENCE_ID_MAX_LENGTH = 40

/**
 * A request that can carry an image, and must credit it.
 *
 * Implemented by the venue, artist and promoter request bodies. Their `image_url` columns are
 * entered by hand rather than scraped, so no per-source licence covers them and the credit is the
 * only record of who the image belongs to (#1275).
 */
interface AttributableImage {
    val imageUrl: String?
    val imageAttribution: String?
    val imageLicenceId: String?
    val imageSourceUrl: String?
}

/**
 * Rejects an image URL that arrives without its credit.
 *
 * A CC BY or CC BY-SA file cannot be displayed without naming the author, the licence and the page
 * the file came from, so a request carrying [AttributableImage.imageUrl] and none of them describes
 * a row the site may not render. `PD` is a licence identifier like any other: a public-domain image
 * says so rather than leaving the field empty, because an empty field cannot be told apart from one
 * nobody filled in.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [AttributedImageValidator::class])
annotation class AttributedImage(
    val message: String = "An image URL needs an attribution, a licence identifier and a source URL",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

/** Reports one violation per missing field, so the response names all of them at once. */
class AttributedImageValidator : ConstraintValidator<AttributedImage, AttributableImage> {
    override fun isValid(
        value: AttributableImage?,
        context: ConstraintValidatorContext
    ): Boolean {
        val missing = value?.missingCreditFields().orEmpty()
        if (missing.isNotEmpty()) {
            context.disableDefaultConstraintViolation()
            missing.forEach { field ->
                context
                    .buildConstraintViolationWithTemplate("$field is required when imageUrl is set")
                    .addPropertyNode(field)
                    .addConstraintViolation()
            }
        }
        return missing.isEmpty()
    }

    /** Empty for a request with no image, which owes no credit. */
    private fun AttributableImage.missingCreditFields(): List<String> =
        if (imageUrl.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                "imageAttribution" to imageAttribution,
                "imageLicenceId" to imageLicenceId,
                "imageSourceUrl" to imageSourceUrl
            ).filter { it.second.isNullOrBlank() }.map { it.first }
        }
}
