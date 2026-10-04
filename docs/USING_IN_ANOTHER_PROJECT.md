# Use LieluGit Updater in another project (with Claude Code)

## Fastest way: no token, no setup (copy-paste into Claude Code)

Every release publishes a **public** Maven repository as a file
(`lielugit-updater-<version>-maven.zip`). Downloading it needs no account and no token.
Paste this in Claude Code, opened in the app project (change `1.0.0` to pick another version):

```text
Ajoute la bibliothèque lielugit-updater 1.0.0 à cette app Android, SANS jeton ni compte :

1. Télécharge le dépôt Maven public de la release et décompresse-le dans le projet :
   mkdir -p libs/lielugit-maven && curl -fsSL -o /tmp/lielugit.zip https://github.com/notsogeek87/lielugit-updater/releases/download/v1.0.0/lielugit-updater-1.0.0-maven.zip && unzip -oq /tmp/lielugit.zip -d libs/lielugit-maven
2. Dans settings.gradle.kts, dans dependencyResolutionManagement { repositories { ... } }, ajoute :
   maven { url = uri("$rootDir/libs/lielugit-maven"); content { includeGroup("com.lielu") } }
3. Dans app/build.gradle.kts, ajoute : implementation("com.lielu:lielugit-updater:1.0.0")
4. Garde libs/lielugit-maven dans git (vérifie qu'il n'est pas dans .gitignore).
5. Branche la vérification de mise à jour avec UpdateManager(context, UpdateConfig(githubOwner = "...", githubRepository = "...")) en utilisant le dépôt GitHub de CETTE app (pas celui de la bibliothèque), avec ma propre interface. Documentation : https://github.com/notsogeek87/lielugit-updater/blob/main/README.md
6. Vérifie que le projet compile.
```

To upgrade later, run the same command with the new version number (the old folder can be deleted).

The rest of this page describes the alternative using GitHub Packages, which needs a token.

---

Three steps: **(1) create a token once, (2) store it once, (3) paste a prompt into Claude Code in the
app you want to update.** Steps 1 and 2 are never repeated for the next apps.

## 1. Create the token (once)

GitHub Packages requires authentication to *download* a package, even a public one.

1. GitHub → *Settings* → *Developer settings* → *Personal access tokens* → **Tokens (classic)** →
   *Generate new token (classic)*.
2. Name: `lielugit-updater-read`. Expiration: your choice (note it: when it expires, create a new one).
3. Scope: tick **only `read:packages`**. Nothing else.
4. Copy the token (`ghp_…`). GitHub shows it only once.

Never paste the token in a chat, an issue, a commit or a file of a repository.

## 2. Store the token (once)

Pick where you run Claude Code:

### Claude Code on the web / cloud sessions
Environment menu (session title bar) → *Edit* → add two **environment variables**:

| Name | Value |
|---|---|
| `GPR_USER` | `notsogeek87` |
| `GPR_TOKEN` | your `ghp_…` token |

Every new session of that environment has them. Also check the environment's **network access**:
if it is *Custom*, allow these hosts (keep the default package-manager list):
`maven.pkg.github.com`, `pkg-containers.githubusercontent.com` and, for any Android build,
`dl.google.com` (Google Maven and the Android SDK are served from there).

### Claude Code on your computer (CLI / desktop)
Add to `~/.gradle/gradle.properties` (global to your machine, outside any repository):

```properties
gpr.user=notsogeek87
gpr.token=ghp_YOUR_TOKEN
```

(or export `GPR_USER` and `GPR_TOKEN` in your shell profile).

## 3. In the app project: paste this prompt into Claude Code

> Add the library **lielugit-updater 1.0.0** to this Android app, following
> https://github.com/notsogeek87/lielugit-updater/blob/main/docs/USING_IN_ANOTHER_PROJECT.md (section "What to add").
> The GitHub token is in the environment variables `GPR_USER` / `GPR_TOKEN` (or in `~/.gradle/gradle.properties`):
> never write it in the repository. Then wire an update check that uses the GitHub repository of THIS app
> (`githubOwner`/`githubRepository`), with my own UI. Make sure the project still builds.

Claude Code will read this file and do the rest. Replace `1.0.0` by the version you want: **each app chooses
its own version**.

## What to add (reference for you or Claude Code)

**`settings.gradle.kts`**

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/notsogeek87/lielugit-updater")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GPR_USER")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GPR_TOKEN")
            }
        }
    }
}
```

**`app/build.gradle.kts`**

```kotlin
dependencies {
    implementation("com.lielu:lielugit-updater:1.0.0")
}
```

**Code**

```kotlin
val updater = UpdateManager(
    context = context,
    config = UpdateConfig(githubOwner = "OWNER_OF_THE_APP", githubRepository = "REPO_OF_THE_APP")
)
val update = updater.checkForUpdate()          // null = up to date
if (update != null) {
    val apk = updater.downloadUpdate(update)
    if (updater.canInstallPackages()) updater.installUpdate(apk)
    else updater.openInstallPermissionSettings()
}
```

`githubOwner`/`githubRepository` designate the repository **of the app** that publishes the APK in its
GitHub Releases, not this library's repository.

## Checklist for the app

- [ ] Its GitHub Releases contain a `.apk` and a tag like `v1.2.3` (see the main README).
- [ ] The release APK is signed **with the same key** as the installed app, same `applicationId`, higher `versionCode`.
- [ ] The installed `versionName` is a version such as `1.2.3`.
- [ ] The app is distributed outside Google Play (the library adds `REQUEST_INSTALL_PACKAGES`).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `401 Unauthorized` or `Could not GET … maven.pkg.github.com` | Token missing, expired, or without `read:packages`; `GPR_USER`/`GPR_TOKEN` not visible to Gradle. |
| `Could not resolve com.lielu:lielugit-updater:1.0.0` | That version does not exist yet (check the repository's *Releases* page), or the repository block is missing in `settings.gradle.kts`. |
| Host denied / `403 CONNECT` in a cloud session | Add the hosts listed in step 2 to the environment's allowed domains. |
| Library repository is private | The token's account needs access to it. |
