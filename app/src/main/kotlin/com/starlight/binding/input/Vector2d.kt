package com.starlight.binding.input

import kotlin.math.pow
import kotlin.math.sqrt

// Helper class representing a vector in 2D space
@Suppress("unused")
class Vector2d {
    private var x = 0f
    private var y = 0f
    var magnitude = 0.0
        private set

    init {
        initialize(0f, 0f)
    }

    fun initialize(x: Float, y: Float) {
        this.x = x
        this.y = y
        magnitude = sqrt(x.toDouble().pow(2.0) + y.toDouble().pow(2.0))
    }

    fun getNormalized(vector: Vector2d) {
        vector.initialize((x / magnitude).toFloat(), (y / magnitude).toFloat())
    }

    fun scalarMultiply(factor: Double) {
        initialize((x * factor).toFloat(), (y * factor).toFloat())
    }

    fun setX(x: Float) {
        initialize(x, y)
    }

    fun setY(y: Float) {
        initialize(x, y)
    }

    fun getX(): Float {
        return x
    }

    fun getY(): Float {
        return y
    }

    companion object {
        val ZERO = Vector2d()
    }
}