// Use an integer for version numbers
version = 1

cloudstream {
    description = "AlmostOTT - Movies, TV Shows, Anime & Dramas"
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
        "Anime",
    )
    language = "en"

    iconUrl = "https://almostott.aethoflix.world/favicon.ico"
}
