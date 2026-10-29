package dev.coyotecage.pose

/**
 * MediaPipe BlazePose 33 点的标准骨架连线。
 * 索引顺序与 [PoseFrame.points] 一致，直接拿来做可视化和调试。
 */
object PoseSkeleton {

    val CONNECTIONS: Array<IntArray> = arrayOf(
        // 面部
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 7),
        intArrayOf(0, 4), intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 8),
        intArrayOf(9, 10),
        // 肩 / 手臂
        intArrayOf(11, 12),
        intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(15, 17),
        intArrayOf(15, 19), intArrayOf(15, 21), intArrayOf(17, 19),
        intArrayOf(12, 14), intArrayOf(14, 16), intArrayOf(16, 18),
        intArrayOf(16, 20), intArrayOf(16, 22), intArrayOf(18, 20),
        // 躯干
        intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
        // 腿
        intArrayOf(23, 25), intArrayOf(24, 26), intArrayOf(25, 27), intArrayOf(26, 28),
        intArrayOf(27, 29), intArrayOf(28, 30), intArrayOf(29, 31), intArrayOf(30, 32),
        intArrayOf(27, 31), intArrayOf(28, 32),
    )
}
