# Goals Android App

Native Android app for the Goals tracker. Built with Kotlin + Jetpack Compose.
Connects to the same FastAPI + MongoDB backend as the web app.

## Open in Android Studio

1. Open Android Studio
2. **File → Open** → select this `android/` folder
3. Wait for Gradle sync to finish (first time downloads ~500MB of dependencies)
4. Connect your phone via USB (enable USB debugging)
5. Click **Run ▶**

## Server URL Configuration

The server URL is set at build time in `app/build.gradle.kts`:

```kotlin
buildTypes {
    dev {
        buildConfigField("String", "SERVER_URL", "\"http://192.168.1.x:2200/\"")
    }
    prod {
        buildConfigField("String", "SERVER_URL", "\"http://your-server:2200/\"")
    }
}
```

Update the URL for your environment before building. The emulator default is `http://10.0.2.2:2200/`.

## Features

- **Today tab** — Week strip, progress bar, toggle slots per goal
- **Goals tab** — Add/edit/delete goals, drag to reorder
- **Home screen widget** — Shows today's progress + tap to toggle slots without opening the app
- **Real-time sync** — SSE connection mirrors changes from web instantly
- **Resume sync** — Reloads data when app returns to foreground; SSE is stopped when backgrounded

## Widget

Long-press your home screen → Widgets → Goals Today

Sizes: 4×2 (default), resizable up to 4×3 or down to 4×1.
Tapping a circle directly toggles that slot — no need to open the app.

## Architecture

```
data/api/       Retrofit + OkHttp REST client
data/sse/       SSE streaming client
data/models/    Kotlin data classes (mirrors backend Pydantic models)
repository/     GoalsRepository — wraps API calls in ApiResult
viewmodel/      AppViewModel — single source of truth for all UI state
ui/theme/       Colors + Typography (Syne + DM Mono fonts, same palette as web)
ui/components/  ToggleButton, WeekStrip, WeekSummary, GoalRow, Toast
ui/today/       TodayScreen
ui/goals/       GoalsScreen + GoalFormSheet (ModalBottomSheet)
widget/         AppWidgetProvider + RemoteViewsFactory + WidgetActionService
```
