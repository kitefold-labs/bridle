package com.kitefoldlabs.bridle.feature.settings

/** A third-party component bundled in the app, with the licence it is used under. */
data class ThirdPartyLicence(
    val component: String,
    val licence: String,
    val url: String,
)

/**
 * Third-party components shipped in the release build (see `gradle :app:dependencies
 * --configuration releaseRuntimeClasspath`). Their licence and notice files are also merged into
 * the APK's `META-INF`. `checkLicense` keeps the dependency set Apache-2.0-compatible; update this
 * list when a runtime dependency is added.
 */
object Licences {
    const val SOURCE_URL: String = "https://github.com/nkoenig/terminal"
    const val PRIVACY_URL: String = "$SOURCE_URL/blob/main/PRIVACY.md"

    private const val APACHE = "Apache License 2.0"
    private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

    val all: List<ThirdPartyLicence> =
        listOf(
            ThirdPartyLicence("Android Jetpack (AndroidX), Jetpack Compose and Material icons", APACHE, APACHE_URL),
            ThirdPartyLicence("Kotlin standard library", APACHE, APACHE_URL),
            ThirdPartyLicence("kotlinx.coroutines, kotlinx.serialization, atomicfu", APACHE, APACHE_URL),
            ThirdPartyLicence("Dagger and Hilt", APACHE, APACHE_URL),
            ThirdPartyLicence("sshj", APACHE, "https://github.com/hierynomus/sshj"),
            ThirdPartyLicence("ASN-One", APACHE, "https://github.com/hierynomus/asn-one"),
            ThirdPartyLicence(
                "Bouncy Castle",
                "Bouncy Castle Licence (MIT)",
                "https://www.bouncycastle.org/licence.html",
            ),
            ThirdPartyLicence("Tink", APACHE, "https://github.com/tink-crypto/tink-java"),
            ThirdPartyLicence("termlib (ConnectBot)", APACHE, "https://github.com/connectbot/termlib"),
            ThirdPartyLicence("Gson", APACHE, "https://github.com/google/gson"),
            ThirdPartyLicence("Okio", APACHE, "https://github.com/square/okio"),
            ThirdPartyLicence("Guava ListenableFuture", APACHE, "https://github.com/google/guava"),
            ThirdPartyLicence("Error Prone annotations", APACHE, "https://github.com/google/error-prone"),
            ThirdPartyLicence("JSR 305 annotations", APACHE, "https://github.com/amaembo/jsr-305"),
            ThirdPartyLicence("JetBrains annotations", APACHE, "https://github.com/JetBrains/java-annotations"),
            ThirdPartyLicence("JSpecify annotations", APACHE, "https://jspecify.dev"),
            ThirdPartyLicence("javax.inject and Jakarta Inject", APACHE, "https://github.com/jakartaee/inject"),
            ThirdPartyLicence("SLF4J API", "MIT License", "https://www.slf4j.org/license.html"),
        )
}
