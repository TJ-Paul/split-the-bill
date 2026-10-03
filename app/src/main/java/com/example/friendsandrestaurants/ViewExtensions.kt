package com.example.friendsandrestaurants

import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * Commits and leaves the field being edited inside this view (if any) and closes the keyboard.
 * The keyboard is hidden first: once focus has moved away, the system no longer treats the field
 * as the keyboard's target and would leave an empty keyboard on screen.
 */
fun View.clearFocusAndHideKeyboard() {
    val focused = findFocus() ?: return
    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
    focused.clearFocus()
}
