package com.starlight.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import kotlin.math.floor

/**
 * Created by Kevin Andrews on 30/12/2025.
 */

enum class StepSize(val value: Float) {
    HALF(0.5f),
    ONE(1.0f),
}

internal val DefaultStarColor = Color(0xFFFFC02E)
internal val DefaultInactiveStarColor = Color.Gray

@Composable
fun StarRatingBar(
    value: Float,
    modifier: Modifier = Modifier,
    numOfSteps: Int = 5,
    stepSize: StepSize = StepSize.HALF,
    spaceBetween: Dp = 2.dp,
    ratingContent: @Composable RatingBarDefaults.() -> Unit = {
        RatingContent()
    },
    inactiveContent: @Composable RatingBarDefaults.() -> Unit = {
        InactiveContent()
    },
) {
    RatingBarBasic(
        value = value,
        modifier = modifier,
        numOfSteps = numOfSteps,
        stepSize = stepSize,
        spaceBetween = spaceBetween,
        ratingContent = ratingContent,
        inactiveContent = inactiveContent,
    )
}

@Composable
private fun RatingBarBasic(
    value: Float,
    modifier: Modifier = Modifier,
    childModifier: (index: Int) -> Modifier = { _ -> Modifier },
    numOfSteps: Int = 5,
    stepSize: StepSize = StepSize.HALF,
    spaceBetween: Dp = 2.dp,
    ratingContent: @Composable RatingBarDefaults.() -> Unit = {
        RatingContent()
    },
    inactiveContent: @Composable RatingBarDefaults.() -> Unit = {
        InactiveContent()
    },
) {
    fun Float.modifyValue(stepSize: StepSize): Float = when (stepSize) {
        StepSize.HALF -> this
        StepSize.ONE -> floor(this)
    }

    val modifiedValue = value.modifyValue(stepSize)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spaceBetween),
    ) {
        repeat(numOfSteps) { index ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .then(childModifier(index)),
            ) {
                with(RatingBarDefaults) {
                    inactiveContent()
                }

                if (index < modifiedValue) {
                    Box(
                        modifier = Modifier
                            .run {
                                if (index == floor(modifiedValue).toInt()) {
                                    clip(HalfCutoutShape())
                                } else {
                                    this
                                }
                            },
                    ) {
                        with(RatingBarDefaults) {
                            ratingContent()
                        }
                    }
                }
            }
        }
    }
}

object RatingBarDefaults {

    @Composable
    fun RatingContent(modifier: Modifier = Modifier, color: Color = DefaultStarColor) {
        Icon(
            tint = color,
            imageVector = Icons.Default.Star,
            modifier = modifier
                .width(16.dp)
                .height(16.dp),
            contentDescription = "Rated Star",
        )
    }

    @Composable
    fun InactiveContent(modifier: Modifier = Modifier, color: Color = DefaultInactiveStarColor) {
        Icon(
            tint = color,
            imageVector = Icons.Default.Star,
            modifier = modifier
                .width(16.dp)
                .height(16.dp),
            contentDescription = "Unrated Star",
        )
    }
}

private class HalfCutoutShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(Rect(Offset.Zero, Size((size.width / 2), size.height)))
}