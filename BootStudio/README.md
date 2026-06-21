# BootStudio

BootStudio is an Android application that allows users to choose between using Shizuku or root access for executing commands. This application is built using Jetpack Compose for the UI and follows modern Android development practices.

## Features

- User-friendly interface to select between Shizuku and root access.
- Utilizes Kotlin coroutines for asynchronous command execution.
- Displays results or errors from command execution directly in the UI.

## Project Structure

```
BootStudio
├── app
│   ├── src
│   │   └── main
│   │       ├── AndroidManifest.xml
│   │       ├── java
│   │       │   ├── utils
│   │       │   │   └── CommandExecutor.kt
│   │       │   └── com
│   │       │       └── bootstudio
│   │       │           ├── MainActivity.kt
│   │       │           └── ui
│   │       │               ├── theme
│   │       │               │   └── BootStudioTheme.kt
│   │       │               └── screens
│   │       │                   └── HomeScreen.kt
│   │       └── res
│   │           ├── layout
│   │           │   └── activity_main.xml
│   │           └── values
│   │               └── strings.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle
│   └── wrapper
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
└── README.md
```

## Setup Instructions

1. Clone the repository:
   ```
   git clone <repository-url>
   ```

2. Open the project in Android Studio.

3. Sync the project with Gradle files.

4. Run the application on an Android device or emulator.

## Usage

Upon launching the application, users will be presented with options to choose between Shizuku or root access. The selected option will determine how commands are executed within the app.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.