package dev.amatv.integration

enum class LyricTextSize(val scale: Float) {
    Standard(1.25f), Large(1.55f), ExtraLarge(1.85f);

    fun next(): LyricTextSize = entries[(ordinal + 1) % entries.size]
}
