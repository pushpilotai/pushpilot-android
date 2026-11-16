# Releasing the Android SDK

A version on Maven Central can never be changed or deleted. Every step marked **Owner** needs the owner's explicit yes for that version.

## One-time setup (owner)

1. **Maven Central namespace.** Sign in at <https://central.sonatype.com>, open *Namespaces*, add `ai.pushpilot` and add the verification key it shows as a DNS TXT record on `pushpilot.ai`. Click *Verify* once the record is live.
2. **Signing key.** `brew install gnupg`, then `gpg --full-generate-key` (RSA 4096, the release email, a passphrase), then publish the public key: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`. Keep a backup of the key outside this machine.
3. **Gradle properties.** In `~/.gradle/gradle.properties` (never in this repo):

   ```properties
   signing.gnupg.executable=gpg
   signing.gnupg.keyName=<KEYID>
   ```

   gpg asks for the passphrase when signing.

## Each release

1. Set the version in `sdk/build.gradle.kts`, the install snippet in `README.md` and the heading in `CHANGELOG.md` (replace "unreleased" with the date).
2. `./gradlew clean build` passes.
3. Rehearse: `./gradlew :sdk:publishToMavenLocal`, install it into a fresh app with `mavenLocal()`, register a device against a local PushPilot server and receive a push with an image and buttons; tap it and see the click counted.
4. `./gradlew :sdk:centralBundle`. It fails if any file is unsigned. Check the zip holds only `ai/pushpilot/sdk/<version>/` files (aar, pom, module, sources, javadoc, with `.asc` and checksums) and no `google-services.json`, keys or `local.properties`:

   ```bash
   unzip -l sdk/build/central/pushpilot-android-sdk-*-central-bundle.zip
   ```

5. Commit, merge to `main`, tag `<version>` (for example `2.0.0`, no `v`).
6. **Owner:** push `main` and the tag to `github.com/pushpilotai/pushpilot-android`.
7. **Owner:** at <https://central.sonatype.com> → *Publish Component*, upload the zip. Wait for *Validated*, review the files, then click *Publish*.
8. Within about 30 minutes it appears at `https://repo1.maven.org/maven2/ai/pushpilot/sdk/<version>/`. Install it from Maven Central into a fresh app (no `mavenLocal()`) and repeat the push check.
9. Flutter and React Native depend on `ai.pushpilot:sdk:<version>`; release them only after this step.
