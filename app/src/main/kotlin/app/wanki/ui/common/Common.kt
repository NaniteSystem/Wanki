package app.wanki.ui.common

val Languages = listOf(
    "ja" to "Японский", "zh" to "Китайский", "ko" to "Корейский", "ar" to "Арабский",
    "en" to "Английский", "ru" to "Русский",
)

/** For notebooks: any language, detected automatically, or a fixed one. */
val NotebookLanguages = listOf("auto" to "Любой (автоопределение)") + Languages

/** Translation targets offered in the UI. */
val TranslateTargets = listOf("ru" to "русский", "en" to "английский", "ja" to "японский", "zh" to "китайский", "ko" to "корейский", "ar" to "арабский", "de" to "немецкий", "fr" to "французский", "es" to "испанский")

fun languageName(code: String) = NotebookLanguages.firstOrNull { it.first == code }?.second ?: code
