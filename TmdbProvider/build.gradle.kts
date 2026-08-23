// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "TMDB - Movies & TV Shows streaming guide"
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

    iconUrl = "https://www.themoviedb.org/assets/2/apple-touch-icon-57ed4b3b0450fd5e9a0c20f34e814b82adaa1085c79bdde2f00ca3ea3ecc7b8b.png"
}
