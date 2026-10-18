package net.darkrelay.microdeck

/**
 * Torch brightness range for one camera, from FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
 * and FLASH_INFO_STRENGTH_DEFAULT_LEVEL. Levels run 1..[max]; a max of 1 (or a
 * device that doesn't report one) means plain on/off torch, no slider.
 * No Android types here so the clamp and fallback logic can be unit tested.
 */
data class TorchLevels(val max: Int, val default: Int) {

    /** True when the torch has more than one brightness level. */
    val adjustable: Boolean get() = max > 1

    /** Any level, pulled into 1..max. */
    fun clamp(level: Int): Int = level.coerceIn(1, max)

    /** Slider position (0..max-1) for a level. */
    fun toProgress(level: Int): Int = clamp(level) - 1

    /** Level for a slider position. */
    fun fromProgress(progress: Int): Int = clamp(progress + 1)

    companion object {
        /** On/off only: what devices without strength control get. */
        val ON_OFF = TorchLevels(1, 1)

        /**
         * Build from the raw characteristics. Null or nonsense values fall back
         * to on/off; a missing or out-of-range default becomes the middle level.
         */
        fun from(maxLevel: Int?, defaultLevel: Int?): TorchLevels {
            val max = maxLevel ?: return ON_OFF
            if (max <= 1) return ON_OFF
            val def = defaultLevel?.takeIf { it in 1..max } ?: ((max + 1) / 2)
            return TorchLevels(max, def)
        }
    }
}
