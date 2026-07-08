package com.fartech.ftapp2.commonsKt

import org.jline.jansi.Ansi
import org.jline.jansi.AnsiConsole

object AnsiColor {
    const val BLACK = "\u001b[30m"
    const val RED = "\u001b[31m"
    const val GREEN = "\u001b[32m"
    const val YELLOW = "\u001b[33m"
    const val BLUE = "\u001b[34m"
    const val MAGENTA = "\u001b[35m"
    const val CYAN = "\u001b[36m"
    const val WHITE = "\u001b[37m"

    const val BLACK_B = "\u001b[40m"
    const val RED_B = "\u001b[41m"
    const val GREEN_B = "\u001b[42m"
    const val YELLOW_B = "\u001b[43m"
    const val BLUE_B = "\u001b[44m"
    const val MAGENTA_B = "\u001b[45m"
    const val CYAN_B = "\u001b[46m"
    const val WHITE_B = "\u001b[47m"

    const val RESET = "\u001b[0m"
    const val BOLD = "\u001b[1m"
    const val UNDERLINED = "\u001b[4m"
    const val BLINK = "\u001b[5m"
    const val REVERSED = "\u001b[7m"
    const val INVISIBLE = "\u001b[8m"
}

private fun quietConsole(): Boolean =
    System.getProperty("braidrun.quietConsole").equals("true", ignoreCase = true)

fun printlnColor(color: String, vararg args: Any?) {
    if (quietConsole()) return
    AnsiConsole.systemInstall()
    val ansi = Ansi.ansi().a(color)
    args.forEach { ansi.a(it?.toString() ?: "null").a(' ') }
    ansi.reset()
    println(ansi)
}

fun printlnPlain(vararg args: Any?) {
    if (quietConsole()) return
    args.forEach {
        print(it?.toString() ?: "null")
        print(' ')
    }
    println()
}

fun printlnSuccess(vararg args: Any?) {
    printlnColor(AnsiColor.GREEN, *args)
}

fun printlnError(vararg args: Any?) {
    printlnColor(AnsiColor.RED, *args)
}

fun printlnWarning(vararg args: Any?) {
    printlnColor(AnsiColor.YELLOW, *args)
}

fun printlnInfo(vararg args: Any?) {
    printlnColor(AnsiColor.BLUE, *args)
}

fun printlnDebug(vararg args: Any?) {
    printlnColor(AnsiColor.CYAN, *args)
}
