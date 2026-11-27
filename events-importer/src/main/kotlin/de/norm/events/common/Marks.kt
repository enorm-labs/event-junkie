package de.norm.events.common

import java.text.Normalizer

private val MARKS = Regex("\\p{M}+")

/**
 * This text in [form] with every combining mark removed: `Maël` is `Mael`. NFKD also folds
 * compatibility forms, so `ﬁ` is `fi` only under it.
 */
fun String.withoutMarks(form: Normalizer.Form = Normalizer.Form.NFD): String = Normalizer.normalize(this, form).replace(MARKS, "")
