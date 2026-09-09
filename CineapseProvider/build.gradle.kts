// Use an integer for version numbers
version = 3

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Cineapse - Movies & TV Shows streaming guide"
    authors = listOf("Satish Gumudavelli")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "en"

    iconUrl = "https://www.cineapse.net/icon.png"
}
