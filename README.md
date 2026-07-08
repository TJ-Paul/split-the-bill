# FriendsAndRestaurants 🍔👫

FriendsAndRestaurants is a sleek, efficient Android application designed to simplify the process of splitting bills and tracking orders when dining out with friends. No more scratching your head over who owes what – this app handles the math for you!

## 📲 APK Download

You can download the latest stable version of the app directly or build it yourself from source.

### 📥 Direct Download (Recommended)
1.  **Download**: Click the link below to download the APK file directly.
    [**Download split_the_bill.apk**](https://github.com/TJ-Paul/split-the-bill/releases/download/v1.0.0/split_the_bill.apk)
2.  **Install**: Open the downloaded `.apk` file on your Android device to install. 
    *(Note: You may need to allow "Install from Unknown Sources" in your device settings).*

### 🛠 Build from Source
1.  **Build the Project**: In Android Studio, go to `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
2.  **Locate the File**: Once the build finishes, click **'locate'** in the notification or find it manually at `app/build/outputs/apk/debug/app-debug.apk`.
3.  **Install**: Transfer this file to your device and install.

## ✨ Features

*   **👥 Effortless Friend Management**: Add friends individually or in bulk. The app remembers frequently used names to make the process even faster.
*   **🍕 Order Details**: Track specific food items, their prices, and how much each friend has already paid.
*   **💰 Automated Calculations**: Instant calculation of "DUE" or "REFUND" amounts for each person.
*   **📊 Session Summary**: View an overall summary of the total bill, total paid, and the net status (Overall Due or Refund).
*   **📜 History & Logs**: Save your dining sessions with timestamps and restaurant names. Review past outings anytime in the History section.
*   **🎨 Material 3 Design**: A modern, clean interface with intuitive navigation and helpful color-coding (Red for debts, Green for refunds).
*   **💾 Persistent Storage**: Your data is automatically saved, so you never lose track of a session if the app closes.

## 🚀 How It Works

1.  **Enter Restaurant Name**: Start by typing where you're eating.
2.  **Add Friends**: Use "Add Friend" for details, "Quick Add" for regulars, or "Bulk Add" to quickly list everyone at the table.
3.  **Fill in Details**: Enter what each person ordered, the price, and their payment.
4.  **Check Receipt**: Tap the Floating Action Button (FAB) to see the full breakdown and session totals.
5.  **Save Log**: Click "Save Copy" on the receipt screen to keep a record in your history.

## 🛠 Tech Stack

*   **Language**: Kotlin
*   **Architecture**: MVVM (ViewModel, LiveData)
*   **UI Components**: Material Design 3, RecyclerView, ViewBinding
*   **Navigation**: Jetpack Navigation Component
*   **Storage**: SharedPreferences with JSON serialization

---
*Developed with ❤️ for hungry friends everywhere.*
