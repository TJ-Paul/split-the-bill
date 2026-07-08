# FriendsAndRestaurants 🍔👫

FriendsAndRestaurants is a sleek, efficient Android application designed to simplify the process of splitting bills and tracking orders when dining out with friends. No more scratching your head over who owes what – this app handles the math for you!

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

## 📲 APK Download

You can find the latest build of the app in the `build` outputs folder.

### How to get the APK:
1.  **Build the Project**: In Android Studio, go to `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
2.  **Locate the File**: Once the build finishes, a notification will appear. Click **'locate'** or use the link below:
    [**Download app-debug.apk**](app/build/outputs/apk/debug/app-debug.apk)
3.  **Install**: Transfer this `.apk` file to your Android device and open it to install the app.

---
*Developed with ❤️ for hungry friends everywhere.*
