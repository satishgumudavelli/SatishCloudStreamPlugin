version = 1

cloudstream {
    description = "Movies4u - Bollywood, Hollywood, South Indian, Web Series & TV Shows"
    authors = listOf("Satish Gumudavelli")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "hi"

    iconUrl = "https://movies4u.ag/favicon.ico"
}

dependencies {
    // Whole-season releases are shipped as a single deflate-compressed zip of all episodes.
    // This tiny embedded server lets a per-episode ExtractorLink point at a local url that
    // range-fetches just that episode's compressed bytes from the remote zip and inflates them
    // on the fly for the player, instead of requiring the whole archive to be downloaded first.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
