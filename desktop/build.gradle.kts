plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.openjfx:javafx-base:21.0.6:win")
    implementation("org.openjfx:javafx-graphics:21.0.6:win")
    implementation("org.openjfx:javafx-controls:21.0.6:win")
    implementation("org.openjfx:javafx-media:21.0.6:win")
    implementation("org.openjfx:javafx-web:21.0.6:win")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.json:json:20250517")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.api-client:google-api-client:2.8.0")
    implementation("com.google.http-client:google-http-client-gson:1.47.0")
    testImplementation("junit:junit:4.13.2")
}

application { mainClass.set("com.tradingpnl.desktop.TradingJournalDesktopKt") }

tasks.processResources {
    from("../app/src/main/assets") { include("index.html") }
    from("../font")
    from("../docs") { include("app-icon-v25-master.png") }
}

tasks.register<Copy>("stageWindowsOAuth") {
    description = "Copies a local Desktop OAuth JSON into the untracked runtime secrets folder."
    val downloaded = providers.gradleProperty("oauthJson")
    onlyIf { downloaded.isPresent }
    from(downloaded)
    into(layout.projectDirectory.dir(".secrets"))
    rename { "client_secret.json" }
}
