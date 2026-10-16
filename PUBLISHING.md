# Publishing

Releases are published to Maven Central from GitHub Releases.

1. Set a non-SNAPSHOT version in the parent POM and the three module POM
   `<parent>` entries (for example `0.1.0`).
2. Push a matching tag such as `v0.1.0` and publish a non-prerelease GitHub
   Release for it.
3. `.github/workflows/release.yml` validates the tag, runs the CI build, then
   deploys one signed Central bundle with all three modules
   (`mvn -P release deploy`).

The repository needs the `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
`GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` secrets for the verified
`io.github.ghosthack` Central namespace.

Builds use the Maven Wrapper (`./mvnw`), which pins Maven 3.9.16. Do not
switch the release to the runner's `mvn`: under Maven 3.10 the
central-publishing plugin (0.11.0) stages resolver files such as
`_remote.repositories` and `maven-metadata-local.xml` into the bundle, and
Central rejects it with "Bundle has content that does NOT have a .pom file".

To check a release build locally without signing:

```sh
./mvnw -P release -Dgpg.skip=true verify
```

To inspect the signed bundle CI would upload, run the Release workflow
manually (optionally with a throwaway `version`). It builds against an
unreachable Central URL with placeholder credentials and attaches
`central-bundle.zip` as a workflow artifact; nothing is published.
