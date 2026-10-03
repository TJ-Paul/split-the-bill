# Split the Bill 🍔👫

Split the Bill is a sleek, efficient Android application designed to simplify the process of splitting bills and tracking orders when dining out with friends. No more scratching your head over who owes what – this app handles the math for you! A completely vibe coded app over multiple cups of tea.

<br>

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

<br>

## ✨ Features

*   **📲 Share with Friends (QR code)**: Tap the QR icon and everyone at the table can add their own order from their phone — no app needed. They join your Wi‑Fi or your hotspot, scan the code, and get a simple page where they type their name, items and prices (sums like `120+80` or `900÷3` work there too). Everyone sees the whole bill as a receipt and can **copy** someone's order to start from. Guests can only change the orders they added themselves; you see every change live and can still edit anything.
*   **👥 Effortless Friend Management**: Add friends one at a time (with "Add & next" for fast entry), pick from **Recent** friends (with search, select-all, and anyone already on the bill marked "In list"), or **Bulk** add a list of names. Duplicates are skipped automatically.
*   **🍕 Order Details**: Track each friend's items, prices, and how much they've paid. Add as many items per person as you need.
*   **🧮 Built-in Calculator**: Type `120+80`, `40*2` or `900/3` straight into any price field — it opens a number pad, shows the result, and remembers your expression when you tap back in.
*   **➕ Add Item to Many**: Give the same item to several friends at once, or use **Split total** to divide one shared item (e.g. a pizza) equally between them.
*   **🔢 Smart Sorting**: Lists are organized by payment status — **DUE** at the top, then **REFUND**, then **Settled** — and alphabetically within each group. Each card has a colored edge so you can scan who owes what.
*   **💰 Automated Calculations**: Live "Due" / "Refund" amounts for each person (decimals included — no more rounding errors), plus a running total bar at the bottom of the screen.
*   **✅ One-tap "Paid"**: Mark someone as paid in full; if their bill changes later, the app un-marks them so nobody is accidentally let off.
*   **↩️ Undo**: Removing a friend or clearing the bill can be undone from the snackbar.
*   **📊 Receipt**: A readable summary (totals, per-person breakdown) or the classic fixed-width table. **Share** it to WhatsApp/Messenger or copy it in one tap.
*   **📜 History & Logs**: Save receipts with timestamps and restaurant names, browse them with dates and totals, and share or delete old ones.
*   **🎨 Material 3 Design**: A clean, warm interface with readable colors (red for debts, green for refunds) and proper keyboard handling.
*   **💾 Persistent Storage**: Every change is saved instantly, so you never lose a session if the app closes.

<br>

## 🚀 How It Works

1.  **Enter Restaurant Name**: Start by typing where you're eating.
2.  **Add Friends**: Use **Add** for one person with their order, **Recent** for regulars, or **Bulk** to list everyone at the table.
3.  **Fill in Details**: Enter what each person ordered, the price, and what they paid — right on their card. Use **Add item** for shared dishes.
4.  **Check Receipt**: Tap **Receipt** in the bottom bar to see the full breakdown and session totals.
5.  **Save or Share**: Tap **Save** to keep a copy in History, or **Share** to send it to the group.

<br>

### Letting friends add their own orders

1.  Turn on your **hotspot** (or make sure everyone is on the **same Wi‑Fi**).
2.  Tap the **QR icon** at the top and let friends scan the code with their camera.
3.  Their orders appear on your phone instantly, marked with a small phone icon. Tap **Stop sharing** (in the app or the notification) when you're done.

If the page won't open on a friend's phone, ask them to turn off mobile data, or use your hotspot — some restaurant Wi‑Fi blocks phones from seeing each other.

<br>

## 🛠 Tech Stack

*   **Language**: Kotlin
*   **Architecture**: MVVM (ViewModel, LiveData)
*   **UI Components**: Material Design 3, RecyclerView, ViewBinding
*   **Navigation**: Jetpack Navigation Component
*   **Storage**: SharedPreferences with JSON serialization
*   **Sharing**: A small built-in web server (foreground service) on the local network, a single offline web page, and ZXing for the QR code

<br>

---

## 👨‍💻 About the Developer

<h3 align="left">Turjjo Paul</h3>
<p align="left">
Computer Science & Engineering Student at <b>Bangladesh University of Engineering and Technology (BUET)</b>
</p>

- 🛠 Expertise in **Kotlin, Java, JavaScript, C, C++, and Python**.
- 🧪 Experienced with **iGraphics, JavaFX**, PERN stack, and expanding into **Data Science** (NumPy, Pandas).
- 📧 Contact: [tjpaul770@gmail.com](mailto:tjpaul770@gmail.com)

<br>

<h3 align="left">Connect with me:</h3>
<p align="left">
<a href="https://linkedin.com/in/turjjo-paul" target="_blank">
    <img align="center" src="https://cdn.jsdelivr.net/gh/devicons/devicon/icons/linkedin/linkedin-original.svg" alt="linkedin" height="40" width="40"/>
</a>
&nbsp;
<a href="https://fb.com/turjjo.paul" target="_blank">
    <img align="center" src="https://cdn.jsdelivr.net/gh/devicons/devicon/icons/facebook/facebook-original.svg" alt="facebook" height="40" width="40"/>
</a>
&nbsp;
<a href="https://instagram.com/turjjo_paul" target="_blank">
    <img align="center" src="https://cdn-icons-png.flaticon.com/512/2111/2111463.png" alt="instagram" height="40" width="40"/>
</a>
</p>

<br>

---
