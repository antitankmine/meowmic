# MeowMic Android Transmitter

MeowMic is a high-performance, low-latency Android application that streams live microphone audio over UDP to a receiver (e.g., a Windows WPF application). It is designed for minimal delay and high reliability.

# STILL A WORK IN PROGRESS!!!
Future features are incoming, not so sure when but i will update this repository when i can.
Encryption is coming soon!!!

## Features

*   **Low Latency Audio**: 10ms frame sizes at 48kHz for sub-20ms target latency.
*   **Hardware Selection**: Ability to choose between built-in microphones, USB audio interfaces, and wired headsets.
*   **Background Streaming**: Uses an Android Foreground Service and WakeLocks to ensure audio continues even when the screen is off or the device is locked.
*   **UDP Protocol**: Fast, "fire-and-forget" network transmission.
*   **Diagnostics**: Built-in menu to monitor sent packets and test connectivity via pings from the receiver.
*   **Dark Theme**: Sleek Material 3 dark interface.
*   **Multiple Device IDs**: Support for up to 8 unique phone IDs for multi-mic setups.

## Technical Specifications

*   **Language**: Kotlin
*   **UI Framework**: Jetpack Compose
*   **Audio API**: `AudioRecord` (16-bit PCM, Mono, 48000Hz)
*   **Networking**: UDP Datagrams
*   **Packet Structure**: `[PhoneID (1B)] + [SequenceNumber (8B)] + [PCM Audio Data]`

## Installation & Setup

1.  **Build**: Clone the repository and build the project in Android Studio.
2.  **Permissions**: The app requires `RECORD_AUDIO`, `INTERNET`, `POST_NOTIFICATIONS`, and `WAKE_LOCK` permissions.
3.  **Receiver**: Point the app to the IP address and UDP port of your receiver application.
4.  **Diagnostic**: Use the "Info" icon to verify packet flow and test the "Ping/Pong" response.

## Development

This project uses modern Android development practices, including:
*   State management via `MutableStateFlow`.
*   MVVM architecture with `ViewModel`.
*   Foreground Service with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`.

## License

MIT License - feel free to use and modify for your own projects!
