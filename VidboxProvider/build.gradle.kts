// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Vidbox - Movies & TV Shows streaming guide"
    authors = listOf("Satish Gumudavelli")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "en"

    iconUrl = "https://vidbox.vc/logo.png"
}

dependencies {
    // Pure-JVM WASM runtime (no JNI) - the Max server's stream list is decrypted by a
    // ChaCha20 WASM module the site recompiles every ~5min, so there's no static key to
    // hardcode; this runs the module itself exactly like the site's own JS does.
    implementation("com.dylibso.chicory:runtime:1.7.5")
}
