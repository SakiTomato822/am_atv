package dev.amatv.integration

enum class LyricTextSize(val scale: Float) {
    Standard(1f), Large(1.15f), ExtraLarge(1.3f);

    fun next(): LyricTextSize = entries[(ordinal + 1) % entries.size]
}
