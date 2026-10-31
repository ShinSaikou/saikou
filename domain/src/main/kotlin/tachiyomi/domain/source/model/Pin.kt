// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/source/model/Pin.kt (modified: yes)
package tachiyomi.domain.source.model

sealed class Pin(val code: Int) {
    data object Unpinned : Pin(0b00)
    data object Pinned : Pin(0b01)
    data object Actual : Pin(0b10)
}

inline fun Pins(builder: Pins.PinsBuilder.() -> Unit = {}): Pins {
    return Pins.PinsBuilder().apply(builder).flags()
}

fun Pins(vararg pins: Pin) = Pins {
    pins.forEach { +it }
}

data class Pins(val code: Int = Pin.Unpinned.code) {

    operator fun contains(pin: Pin): Boolean = pin.code and code == pin.code

    operator fun plus(pin: Pin): Pins = Pins(code or pin.code)

    operator fun minus(pin: Pin): Pins = Pins(code xor pin.code)

    companion object {
        val unpinned = Pins(Pin.Unpinned)

        val pinned = Pins(Pin.Pinned, Pin.Actual)
    }

    class PinsBuilder(var code: Int = 0) {
        operator fun Pin.unaryPlus() {
            this@PinsBuilder.code = code or this@PinsBuilder.code
        }

        /** Modified: upstream sets the bit here too, so a builder could never unset one. */
        operator fun Pin.unaryMinus() {
            this@PinsBuilder.code = this@PinsBuilder.code and this.code.inv()
        }

        fun flags(): Pins = Pins(code)
    }
}
