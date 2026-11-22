package ai.kira.mobile.domain.model

data class InternetSearchSource(
    val number: Int,
    val title: String,
    val url: String,
    val excerpt: String,
    val extractedContent: String,
    val score: Double
)

data class InternetSearchResponse(
    val query: String,
    val answer: String,
    val results: List<InternetSearchSource>,
    val provider: String = "Tavily"
)
