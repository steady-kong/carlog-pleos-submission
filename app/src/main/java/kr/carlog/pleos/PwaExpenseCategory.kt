package kr.carlog.pleos

/** PWA v1 stores stable codes; the current automotive app stores these Korean labels. */
val pwaExpenseCategories: List<Pair<String, String>> = listOf(
    "insurance" to "보험", "tax" to "세금", "parking" to "주차", "toll" to "통행료",
    "wash" to "세차", "fine" to "과태료", "accessory" to "용품", "etc" to "기타",
)

/** Unknown values must be resolved by a future converter instead of silently becoming 'etc'. */
fun pwaExpenseCode(appCategory: String): String? = pwaExpenseCategories.firstOrNull { it.second == appCategory }?.first
fun appExpenseCategory(pwaCode: String): String? = pwaExpenseCategories.firstOrNull { it.first == pwaCode }?.second
