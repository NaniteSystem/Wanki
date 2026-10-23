package app.wanki.texts

/**
 * Frequent words with more than one usual reading. The analyser gives each token one reading (日本 → ニッポン);
 * the others listed here are accepted as well. Kept small on purpose: rarer words are fixed in «Чтения…».
 */
object JapaneseReadings {
    private val table = mapOf(
        "日本" to listOf("にほん", "にっぽん"),
        "日本人" to listOf("にほんじん", "にっぽんじん"),
        "今日" to listOf("きょう", "こんにち"),
        "明日" to listOf("あした", "あす", "みょうにち"),
        "昨日" to listOf("きのう", "さくじつ"),
        "一日" to listOf("いちにち", "ついたち"),
        "何" to listOf("なに", "なん"),
        "私" to listOf("わたし", "わたくし"),
        "方" to listOf("かた", "ほう"),
    )

    fun alternatives(surface: String): List<String> = table[surface].orEmpty()
}
