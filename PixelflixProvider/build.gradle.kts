// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Pixelflix - Movies & TV Shows streaming"
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

    iconUrl = "https://pixelflix.cc/favicon.ico"
}
