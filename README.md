# palazikASCII 📟

A high-performance Android camera app that converts live camera feeds into real-time ASCII art. Built with modern Android architecture, combining the declarative UI of **Jetpack Compose**, the advanced hardware access of **CameraX**, and a custom **C++ (JNI)** engine for blazing-fast image processing.

## ✨ Features

* **Real-Time ASCII Engine:** Processes raw YUV camera frames in C++ via JNI to calculate luma (brightness) and map it to an ASCII character ramp at ~30 FPS without dropping frames.
* **Smart Multi-Lens Support:** Uses a custom `LensDetector` reading deep `CameraCharacteristics` (aperture, focal length, and hardware levels) to correctly identify and cycle between Main, Ultrawide, Telephoto, and Front cameras—bypassing "fake" OEM auxiliary depth sensors.
* **Dynamic Grid Scaling:** Uses Compose Math to dynamically calculate the perfect monospace font size so the ASCII grid stretches beautifully from edge-to-edge on any device, from standard phones to high-res flagships.
* **Retro CRT Aesthetic:** Features a custom UI with animated CRT scanlines, a glowing terminal-green color palette, and a live blinking terminal cursor.
* **Modern Stack:** Built entirely in Kotlin, using `StateFlow` for state management, Jetpack Compose for UI, and CMake for the native layer.

## 🛠️ Tech Stack

* **UI:** Jetpack Compose, Material Design 3
* **Camera Pipeline:** CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`)
* **Native Processing:** C++, JNI (Java Native Interface), CMake
* **State Management:** Coroutines, `StateFlow`, `ViewModel`
* **Build System:** Gradle (Kotlin DSL)

## 🧠 Under the Hood

The app relies on a carefully tuned pipeline to achieve real-time rendering:
1. **Frame Capture:** CameraX's `ImageAnalysis` grabs high-resolution YUV frames directly from the active camera sensor.
2. **JNI Bridge:** The raw byte array is passed asynchronously to the C++ layer.
3. **Hardware Rotation Matrix:** The C++ engine detects the physical orientation of the camera sensor (which is usually sideways inside the phone) and dynamically rotates the pixel coordinates.
4. **Luma Mapping:** The image is divided into a 64x128 grid. The C++ engine averages the brightness of the pixels within each cell and maps it to a dense character ramp (` .:-=+*#%@`).
5. **Recomposition:** The resulting string is passed back to Kotlin and rendered natively in Compose, sized perfectly using `BoxWithConstraints`.

## 🚀 How to Build

1. Clone the repository:
   ```bash
   git clone [https://github.com/palazik/palazikASCII.git](https://github.com/palazik/palazikASCII.git)
2. Run GitHub Actions Workflow
3. Download .zip from artifacts and install
