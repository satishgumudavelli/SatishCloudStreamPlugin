// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "FrameMovie - Movies, TV Shows, Short Drama & Trending streaming"
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
        "TvSeries",
        "AsianDrama"
    )
    language = "en"

    iconUrl = "https://www.framemovie.online/favicon.ico"
}
