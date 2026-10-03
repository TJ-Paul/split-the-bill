package com.example.friendsandrestaurants

import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.example.friendsandrestaurants.data.Order
import com.google.android.material.textfield.TextInputLayout
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object PriceCalculator {

    fun cleanExpression(expression: String): String {
        var str = expression.trim()
        if (str.lowercase().endsWith("tk")) {
            str = str.substring(0, str.length - 2).trim()
        }
        return str
    }

    /**
     * Evaluates a math expression string containing numbers and +, -, *, /.
     * Returns Result.success(Double) if valid, or Result.failure(Exception) if invalid.
     */
    fun evaluate(expression: String): Result<Double> {
        val trimmed = cleanExpression(expression)
        if (trimmed.isEmpty()) {
            return Result.success(0.0)
        }

        // Check for disallowed characters
        for (ch in trimmed) {
            if (!ch.isDigit() && ch != '.' && ch != '+' && ch != '-' && ch != '*' && ch != '/' && !ch.isWhitespace()) {
                return Result.failure(IllegalArgumentException("Contains invalid character: '$ch'"))
            }
        }

        return try {
            val tokens = tokenize(trimmed)
            val result = parseExpression(tokens)
            if (result.isNaN() || result.isInfinite()) {
                Result.failure(ArithmeticException("Invalid numerical result"))
            } else {
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if the string contains any characters other than digits, decimal point, basic math operators, or whitespace.
     */
    fun hasInvalidChars(expression: String): Boolean {
        val trimmed = cleanExpression(expression)
        for (ch in trimmed) {
            if (!ch.isDigit() && ch != '.' && ch != '+' && ch != '-' && ch != '*' && ch != '/' && !ch.isWhitespace()) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if expression contains any basic math operator (+, -, *, /)
     */
    fun hasOperators(expression: String): Boolean {
        val trimmed = cleanExpression(expression)
        return trimmed.any { it == '+' || it == '-' || it == '*' || it == '/' }
    }

    /**
     * Formats calculated double to string: e.g. 665.0 -> "665", 665.5 -> "665.5"
     */
    fun formatResult(value: Double): String {
        return if (value % 1.0 == 0.0) {
            String.format(java.util.Locale.US, "%.0f", value)
        } else {
            val formatted = String.format(java.util.Locale.US, "%.2f", value)
            if (formatted.contains(".")) {
                formatted.dropLastWhile { it == '0' }.dropLastWhile { it == '.' }
            } else {
                formatted
            }
        }
    }

    fun formatPriceWithUnit(value: Double): String {
        if (value == 0.0) return ""
        return "${formatResult(value)} tk"
    }

    /**
     * Display formatting with thousands separators: 1211788.0 -> "1,211,788", 333.333 -> "333.33".
     * Not for editable fields (the commas would not parse back).
     */
    fun formatMoney(value: Double): String {
        val rounded = Order.roundToCents(value)
        val format = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
        return format.format(rounded)
    }

    fun formatMoneyWithUnit(value: Double): String = "${formatMoney(value)} tk"

    /** Text to show in a price field for a stored value: blank for zero, otherwise the plain number. */
    fun fieldText(value: Double): String = if (Order.isZero(value)) "" else formatResult(value)

    private sealed class Token {
        data class Number(val value: Double) : Token()
        data class Operator(val op: Char) : Token()
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var lastWasOperatorOrStart = true

        while (i < expr.length) {
            val c = expr[i]
            if (c.isWhitespace()) {
                i++
                continue
            }

            if (c == '+' || c == '-' || c == '*' || c == '/') {
                val isUnary = (c == '+' || c == '-') && lastWasOperatorOrStart
                if (isUnary) {
                    val isAtStart = tokens.isEmpty()
                    val isAfterMulDiv = tokens.isNotEmpty() && tokens.last().let { it is Token.Operator && (it.op == '*' || it.op == '/') } && c == '-'
                    if (!isAtStart && !isAfterMulDiv) {
                        throw IllegalArgumentException("Consecutive operators not allowed")
                    }
                    val start = i
                    i++
                    while (i < expr.length && expr[i].isWhitespace()) i++
                    if (i >= expr.length || (!expr[i].isDigit() && expr[i] != '.')) {
                        throw IllegalArgumentException("Expected number after unary '$c'")
                    }
                    var hasDot = false
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        if (expr[i] == '.') {
                            if (hasDot) throw IllegalArgumentException("Multiple decimal points")
                            hasDot = true
                        }
                        i++
                    }
                    val numStr = expr.substring(start, i).replace(" ", "")
                    val num = numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: $numStr")
                    tokens.add(Token.Number(num))
                    lastWasOperatorOrStart = false
                } else {
                    tokens.add(Token.Operator(c))
                    lastWasOperatorOrStart = true
                    i++
                }
            } else if (c.isDigit() || c == '.') {
                val start = i
                var hasDot = false
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                    if (expr[i] == '.') {
                        if (hasDot) throw IllegalArgumentException("Multiple decimal points")
                        hasDot = true
                    }
                    i++
                }
                val numStr = expr.substring(start, i)
                val num = numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: $numStr")
                tokens.add(Token.Number(num))
                lastWasOperatorOrStart = false
            } else {
                throw IllegalArgumentException("Unexpected character '$c'")
            }
        }
        return tokens
    }

    private fun parseExpression(tokens: List<Token>): Double {
        if (tokens.isEmpty()) throw IllegalArgumentException("Empty tokens")

        val afterMulDiv = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is Token.Operator && (token.op == '*' || token.op == '/')) {
                if (afterMulDiv.isEmpty() || afterMulDiv.last() !is Token.Number) {
                    throw IllegalArgumentException("Invalid operator placement for '${token.op}'")
                }
                if (i + 1 >= tokens.size || tokens[i + 1] !is Token.Number) {
                    throw IllegalArgumentException("Expected number after '${token.op}'")
                }
                val prevNum = (afterMulDiv.removeAt(afterMulDiv.size - 1) as Token.Number).value
                val nextNum = (tokens[i + 1] as Token.Number).value
                val res = if (token.op == '*') {
                    prevNum * nextNum
                } else {
                    if (nextNum == 0.0) throw ArithmeticException("Division by zero")
                    prevNum / nextNum
                }
                afterMulDiv.add(Token.Number(res))
                i += 2
            } else {
                afterMulDiv.add(token)
                i++
            }
        }

        if (afterMulDiv.isEmpty()) throw IllegalArgumentException("No tokens")
        if (afterMulDiv[0] !is Token.Number) throw IllegalArgumentException("Expression must start with number")

        var result = (afterMulDiv[0] as Token.Number).value
        var j = 1
        while (j < afterMulDiv.size) {
            val opToken = afterMulDiv[j]
            if (opToken !is Token.Operator || j + 1 >= afterMulDiv.size || afterMulDiv[j + 1] !is Token.Number) {
                throw IllegalArgumentException("Invalid operator sequence")
            }
            val nextNum = (afterMulDiv[j + 1] as Token.Number).value
            when (opToken.op) {
                '+' -> result += nextNum
                '-' -> result -= nextNum
                else -> throw IllegalArgumentException("Unexpected operator ${opToken.op}")
            }
            j += 2
        }

        return result
    }

    /**
     * Decides which expression to remember for a field. A typed expression ("100+50") is kept as-is.
     * When the field shows a plain number equal to the previously stored expression's result
     * (e.g. "150" after formatting), the stored expression is preserved instead of being dropped.
     */
    fun resolveRawExpression(text: String, value: Double, existingRaw: String?): String? {
        if (hasOperators(text)) return cleanExpression(text)
        if (!existingRaw.isNullOrBlank()) {
            val previous = evaluate(existingRaw).getOrNull()
            if (previous != null && Order.isZero(previous - value)) return existingRaw
        }
        return null
    }

    /**
     * Attaches calculator behaviour to a price field.
     *
     * [onValueChanged] receives the evaluated value and the expression to remember. It fires live
     * while typing (`committed = false`, only for valid input) and again when the field loses focus
     * or the keyboard action is pressed (`committed = true`), at which point the text is replaced by
     * the formatted result. When focus returns, the original expression is shown again for editing.
     */
    fun setupPriceField(
        editText: EditText,
        textInputLayout: TextInputLayout? = null,
        getRawExpression: () -> String? = { null },
        onValueChanged: (value: Double, rawExpression: String?, committed: Boolean) -> Unit
    ) {
        var isFormatting = false
        val errorText = editText.context.getString(R.string.invalid_calculation)

        fun setError(msg: String?) {
            if (textInputLayout != null) {
                if (textInputLayout.error != msg) textInputLayout.error = msg
                if (msg == null) textInputLayout.isErrorEnabled = false
            } else {
                editText.error = msg
            }
        }

        fun setTextSilently(text: String) {
            isFormatting = true
            try {
                editText.setText(text)
                if (editText.hasFocus()) editText.setSelection(text.length)
            } finally {
                isFormatting = false
            }
        }

        fun commit() {
            val currentText = editText.text.toString()
            if (currentText.isBlank()) {
                setError(null)
                onValueChanged(0.0, null, true)
                return
            }
            evaluate(currentText).onSuccess { value ->
                setError(null)
                val raw = resolveRawExpression(currentText, value, getRawExpression())
                val formatted = fieldText(value)
                if (currentText != formatted) setTextSilently(formatted)
                onValueChanged(value, raw, true)
            }.onFailure {
                setError(errorText)
            }
        }

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val currentText = editText.text.toString()
                val raw = getRawExpression()
                // Only swap in the stored expression when the field shows a plain (formatted) number,
                // never over something the user is in the middle of typing.
                if (!raw.isNullOrBlank() && currentText != raw &&
                    !hasOperators(currentText) && evaluate(currentText).isSuccess
                ) {
                    setTextSilently(raw)
                }
            } else if (editText.isAttachedToWindow) {
                commit()
            }
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                commit()
            }
            // "Done" finishes editing: drop focus so the list can settle (the keyboard closes by default).
            if (actionId == EditorInfo.IME_ACTION_DONE) editText.clearFocusAndHideKeyboard()
            false
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                val text = s?.toString() ?: ""

                if (hasInvalidChars(text)) {
                    setError(errorText)
                    return
                }
                setError(null)
                if (text.isBlank()) {
                    onValueChanged(0.0, null, false)
                    return
                }
                evaluate(text).onSuccess { value ->
                    onValueChanged(value, resolveRawExpression(text, value, getRawExpression()), false)
                }
            }
        })
    }
}
