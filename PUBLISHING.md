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

To check a release build locally without signing:

```sh
mvn -P release -Dgpg.skip=true verify
```
