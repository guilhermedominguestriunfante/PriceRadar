package com.dedonervoso.core.stage

/**
 * Star times of the stages that end on their objective: the graded time (play time plus
 * 2 s per STOP error) that earns 2 and 3 stars. 1 star is completing the objective at all;
 * SURVIVAL grades its errors instead (everyone gets through the last STOP at the same moment).
 *
 * Calibrated with the simulated players (core/src/test/.../balance/StageTimesReport): 2 stars at
 * an average player's median time, 3 stars at a skilled player's. Stages past the table and the
 * daily challenge use the per-type fractions of the time limit measured on its last blocks.
 * Re-run the report after changing gameplay numbers and paste its output here.
 */
object StageTimes {
    class Times(val star2Ms: Long, val star3Ms: Long)

    private val NONE = Times(0L, 0L)

    /** Tenths of a second for stages 1..60 (StageTimesReport's STAR2 / STAR3 lines). */
    private val STAR2 = intArrayOf(
        220, 90, 435, 130, 405, 460, 445, 320, 445, 415, 235, 435, 110, 405, 445, 435, 505, 395, 305, 455,
        435, 470, 395, 435, 205, 480, 450, 450, 430, 410, 475, 510, 450, 440, 465, 465, 215, 440, 460, 410,
        450, 465, 475, 495, 470, 440, 480, 465, 235, 410, 260, 570, 465, 445, 500, 495, 475, 480, 445, 420,
    )
    private val STAR3 = intArrayOf(
        165, 70, 320, 95, 300, 455, 310, 240, 320, 280, 200, 315, 75, 290, 440, 330, 365, 325, 255, 310,
        315, 345, 330, 430, 120, 350, 330, 330, 355, 305, 345, 380, 310, 355, 460, 325, 125, 370, 325, 295,
        370, 325, 340, 360, 330, 355, 475, 325, 135, 300, 175, 495, 345, 325, 365, 345, 365, 475, 315, 315,
    )

    fun of(n: Int, type: StageType): Times = when {
        type == StageType.SURVIVAL -> NONE
        n in 1..STAR2.size -> Times(STAR2[n - 1] * 100L, STAR3[n - 1] * 100L)
        else -> forType(type)
    }

    /** Stages past the table: fractions of the 60 s limit by objective family (stages 41–60). */
    fun forType(type: StageType): Times {
        val (f2, f3) = when (type) {
            StageType.SCORE -> 0.775 to 0.542
            StageType.BOSS -> 0.700 to 0.525
            StageType.SPEED -> 0.833 to 0.608
            StageType.PRECISION -> 0.792 to 0.608
            StageType.COMBO -> 0.783 to 0.575
            StageType.PERFECT -> 0.433 to 0.292
            StageType.FRENZY -> 0.950 to 0.825
            StageType.SURVIVAL -> return NONE
        }
        return Times(roundHalfSecond(f2 * LIMIT_MS), roundHalfSecond(f3 * LIMIT_MS))
    }

    private fun roundHalfSecond(ms: Double): Long = Math.round(ms / 500.0) * 500L

    private const val LIMIT_MS = 60_000.0
}
