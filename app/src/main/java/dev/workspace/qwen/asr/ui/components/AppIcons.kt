package dev.workspace.qwen.asr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Crisp 24dp Material-style vector icons bundled directly to avoid icon-pack bloat
 * and guarantee offline compilation.
 */
internal object AppIcons {
    private val tint = SolidColor(Color.Black)

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    val Subtitles: ImageVector by lazy {
        icon("Subtitles") {
            path(fill = tint) {
                moveTo(20f, 4f); horizontalLineTo(4f); curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                verticalLineTo(18f); curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f); horizontalLineTo(20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f); verticalLineTo(6f)
                curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f); close()
                moveTo(20f, 18f); horizontalLineTo(4f); verticalLineTo(6f); horizontalLineTo(20f); verticalLineTo(18f); close()
                moveTo(6f, 10f); horizontalLineTo(8f); verticalLineTo(12f); horizontalLineTo(6f); close()
                moveTo(6f, 14f); horizontalLineTo(14f); verticalLineTo(16f); horizontalLineTo(6f); close()
                moveTo(16f, 14f); horizontalLineTo(18f); verticalLineTo(16f); horizontalLineTo(16f); close()
                moveTo(10f, 10f); horizontalLineTo(18f); verticalLineTo(12f); horizontalLineTo(10f); close()
            }
        }
    }

    val Server: ImageVector by lazy {
        icon("Server") {
            path(fill = tint) {
                moveTo(20f, 3f); horizontalLineTo(4f); curveTo(2.9f, 3f, 2f, 3.9f, 2f, 5f)
                verticalLineTo(9f); curveTo(2f, 10.1f, 2.9f, 11f, 4f, 11f); horizontalLineTo(20f)
                curveTo(21.1f, 11f, 22f, 10.1f, 22f, 9f); verticalLineTo(5f)
                curveTo(22f, 3.9f, 21.1f, 3f, 20f, 3f); close()
                moveTo(20f, 9f); horizontalLineTo(4f); verticalLineTo(5f); horizontalLineTo(20f); verticalLineTo(9f); close()
                moveTo(6f, 6f); horizontalLineTo(8f); verticalLineTo(8f); horizontalLineTo(6f); close()
                moveTo(20f, 13f); horizontalLineTo(4f); curveTo(2.9f, 13f, 2f, 13.9f, 2f, 15f)
                verticalLineTo(19f); curveTo(2f, 20.1f, 2.9f, 21f, 4f, 21f); horizontalLineTo(20f)
                curveTo(21.1f, 21f, 22f, 20.1f, 22f, 19f); verticalLineTo(15f)
                curveTo(22f, 13.9f, 21.1f, 13f, 20f, 13f); close()
                moveTo(20f, 19f); horizontalLineTo(4f); verticalLineTo(15f); horizontalLineTo(20f); verticalLineTo(19f); close()
                moveTo(6f, 16f); horizontalLineTo(8f); verticalLineTo(18f); horizontalLineTo(6f); close()
            }
        }
    }

    val Terminal: ImageVector by lazy {
        icon("Terminal") {
            path(fill = tint) {
                moveTo(20f, 4f); horizontalLineTo(4f); curveTo(2.89f, 4f, 2f, 4.9f, 2f, 6f)
                verticalLineTo(18f); curveTo(2f, 19.1f, 2.89f, 20f, 4f, 20f); horizontalLineTo(20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f); verticalLineTo(6f)
                curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f); close()
                moveTo(20f, 18f); horizontalLineTo(4f); verticalLineTo(8f); horizontalLineTo(20f); verticalLineTo(18f); close()
                moveTo(18f, 17f); horizontalLineTo(12f); verticalLineTo(15f); horizontalLineTo(18f); verticalLineTo(17f); close()
                moveTo(7.5f, 17f); lineTo(6.09f, 15.59f); lineTo(8.67f, 13f); lineTo(6.08f, 10.41f)
                lineTo(7.5f, 9f); lineTo(11.5f, 13f); lineTo(7.5f, 17f); close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            path(fill = tint) {
                moveTo(19.14f, 12.94f)
                curveTo(19.18f, 12.64f, 19.2f, 12.33f, 19.2f, 12f)
                curveTo(19.2f, 11.68f, 19.18f, 11.36f, 19.13f, 11.06f)
                lineTo(21.16f, 9.48f)
                curveTo(21.34f, 9.34f, 21.39f, 9.07f, 21.28f, 8.87f)
                lineTo(19.36f, 5.55f)
                curveTo(19.24f, 5.33f, 18.99f, 5.26f, 18.77f, 5.33f)
                lineTo(16.38f, 6.29f)
                curveTo(15.88f, 5.91f, 15.35f, 5.59f, 14.76f, 5.35f)
                lineTo(14.4f, 2.81f)
                curveTo(14.36f, 2.57f, 14.16f, 2.4f, 13.92f, 2.4f)
                horizontalLineTo(10.08f)
                curveTo(9.84f, 2.4f, 9.65f, 2.57f, 9.61f, 2.81f)
                lineTo(9.25f, 5.35f)
                curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                lineTo(5.24f, 5.33f)
                curveTo(5.02f, 5.25f, 4.77f, 5.33f, 4.65f, 5.55f)
                lineTo(2.74f, 8.87f)
                curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
                lineTo(4.89f, 11.06f)
                curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
                curveTo(4.8f, 12.31f, 4.82f, 12.64f, 4.87f, 12.94f)
                lineTo(2.84f, 14.52f)
                curveTo(2.66f, 14.66f, 2.61f, 14.93f, 2.72f, 15.13f)
                lineTo(4.64f, 18.45f)
                curveTo(4.76f, 18.67f, 5.01f, 18.74f, 5.23f, 18.67f)
                lineTo(7.62f, 17.71f)
                curveTo(8.12f, 18.09f, 8.65f, 18.41f, 9.24f, 18.65f)
                lineTo(9.6f, 21.19f)
                curveTo(9.65f, 21.43f, 9.84f, 21.6f, 10.08f, 21.6f)
                horizontalLineTo(13.92f)
                curveTo(14.16f, 21.6f, 14.36f, 21.43f, 14.39f, 21.19f)
                lineTo(14.75f, 18.65f)
                curveTo(15.34f, 18.41f, 15.88f, 18.09f, 16.37f, 17.71f)
                lineTo(18.76f, 18.67f)
                curveTo(18.98f, 18.75f, 19.23f, 18.67f, 19.35f, 18.45f)
                lineTo(21.27f, 15.13f)
                curveTo(21.39f, 14.91f, 21.34f, 14.66f, 21.15f, 14.52f)
                lineTo(19.14f, 12.94f); close()
                moveTo(12f, 15.6f)
                curveTo(10.02f, 15.6f, 8.4f, 13.98f, 8.4f, 12f)
                curveTo(8.4f, 10.02f, 10.02f, 8.4f, 12f, 8.4f)
                curveTo(13.98f, 8.4f, 15.6f, 10.02f, 15.6f, 12f)
                curveTo(15.6f, 13.98f, 13.98f, 15.6f, 12f, 15.6f); close()
            }
        }
    }

    val Mic: ImageVector by lazy {
        icon("Mic") {
            path(fill = tint) {
                moveTo(12f, 14f); curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f); curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f); verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f); close()
                moveTo(17f, 11f); curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
                curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f); horizontalLineTo(5f)
                curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f); verticalLineTo(21f)
                horizontalLineTo(13f); verticalLineTo(17.92f)
                curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f); horizontalLineTo(17f); close()
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        icon("VolumeUp") {
            path(fill = tint) {
                moveTo(3f, 9f); verticalLineTo(15f); horizontalLineTo(7f); lineTo(12f, 20f)
                verticalLineTo(4f); lineTo(7f, 9f); horizontalLineTo(3f); close()
                moveTo(16.5f, 12f); curveTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f)
                verticalLineTo(16.02f); curveTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f); close()
                moveTo(14f, 3.23f); verticalLineTo(5.29f); curveTo(16.89f, 6.15f, 19f, 8.83f, 19f, 12f)
                curveTo(19f, 15.17f, 16.89f, 17.85f, 14f, 18.71f); verticalLineTo(20.77f)
                curveTo(18.01f, 19.86f, 21f, 16.28f, 21f, 12f); curveTo(21f, 7.72f, 18.01f, 4.14f, 14f, 3.23f); close()
            }
        }
    }

    val Play: ImageVector by lazy {
        icon("Play") {
            path(fill = tint) {
                moveTo(8f, 5f); verticalLineTo(19f); lineTo(19f, 12f); close()
            }
        }
    }

    val Stop: ImageVector by lazy {
        icon("Stop") {
            path(fill = tint) {
                moveTo(6f, 6f); horizontalLineTo(18f); verticalLineTo(18f); horizontalLineTo(6f); close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            path(fill = tint) {
                moveTo(6f, 19f); horizontalLineTo(10f); verticalLineTo(5f); horizontalLineTo(6f); verticalLineTo(19f); close()
                moveTo(14f, 5f); verticalLineTo(19f); horizontalLineTo(18f); verticalLineTo(5f); horizontalLineTo(14f); close()
            }
        }
    }

    val Overlay: ImageVector by lazy {
        icon("Overlay") {
            path(fill = tint) {
                moveTo(19f, 11f); horizontalLineTo(11f); verticalLineTo(17f); horizontalLineTo(19f); verticalLineTo(11f); close()
                moveTo(21f, 3f); horizontalLineTo(3f); curveTo(1.9f, 3f, 1f, 3.9f, 1f, 5f)
                verticalLineTo(19f); curveTo(1f, 20.1f, 1.9f, 21f, 3f, 21f); horizontalLineTo(21f)
                curveTo(22.1f, 21f, 23f, 20.1f, 23f, 19f); verticalLineTo(5f)
                curveTo(23f, 3.9f, 22.1f, 3f, 21f, 3f); close()
                moveTo(21f, 19f); horizontalLineTo(3f); verticalLineTo(5f); horizontalLineTo(21f); verticalLineTo(19f); close()
            }
        }
    }

    val Download: ImageVector by lazy {
        icon("Download") {
            path(fill = tint) {
                moveTo(5f, 20f); horizontalLineTo(19f); verticalLineTo(18f); horizontalLineTo(5f); verticalLineTo(20f); close()
                moveTo(19f, 9f); horizontalLineTo(15f); verticalLineTo(3f); horizontalLineTo(9f)
                verticalLineTo(9f); horizontalLineTo(5f); lineTo(12f, 16f); lineTo(19f, 9f); close()
            }
        }
    }

    val Upload: ImageVector by lazy {
        icon("Upload") {
            path(fill = tint) {
                moveTo(5f, 20f); horizontalLineTo(19f); verticalLineTo(18f); horizontalLineTo(5f); verticalLineTo(20f); close()
                moveTo(9f, 16f); horizontalLineTo(15f); verticalLineTo(10f); horizontalLineTo(19f)
                lineTo(12f, 3f); lineTo(5f, 10f); horizontalLineTo(9f); verticalLineTo(16f); close()
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            path(fill = tint) {
                moveTo(16f, 1f); horizontalLineTo(4f); curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
                verticalLineTo(17f); horizontalLineTo(4f); verticalLineTo(3f); horizontalLineTo(16f); verticalLineTo(1f); close()
                moveTo(19f, 5f); horizontalLineTo(8f); curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
                verticalLineTo(21f); curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f); horizontalLineTo(19f)
                curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f); verticalLineTo(7f)
                curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f); close()
                moveTo(19f, 21f); horizontalLineTo(8f); verticalLineTo(7f); horizontalLineTo(19f); verticalLineTo(21f); close()
            }
        }
    }

    val Key: ImageVector by lazy {
        icon("Key") {
            path(fill = tint) {
                moveTo(12.65f, 10f); curveTo(11.83f, 7.67f, 9.61f, 6f, 7f, 6f)
                curveTo(3.69f, 6f, 1f, 8.69f, 1f, 12f); curveTo(1f, 15.31f, 3.69f, 18f, 7f, 18f)
                curveTo(9.61f, 18f, 11.83f, 16.33f, 12.65f, 14f); horizontalLineTo(17f)
                verticalLineTo(18f); horizontalLineTo(21f); verticalLineTo(14f); horizontalLineTo(23f)
                verticalLineTo(10f); horizontalLineTo(12.65f); close()
                moveTo(7f, 14f); curveTo(5.9f, 14f, 5f, 13.1f, 5f, 12f)
                curveTo(5f, 10.9f, 5.9f, 10f, 7f, 10f); curveTo(8.1f, 10f, 9f, 10.9f, 9f, 12f)
                curveTo(9f, 13.1f, 8.1f, 14f, 7f, 14f); close()
            }
        }
    }

    val Code: ImageVector by lazy {
        icon("Code") {
            path(fill = tint) {
                moveTo(9.4f, 16.6f); lineTo(4.8f, 12f); lineTo(9.4f, 7.4f); lineTo(8f, 6f)
                lineTo(2f, 12f); lineTo(8f, 18f); lineTo(9.4f, 16.6f); close()
                moveTo(14.6f, 16.6f); lineTo(19.2f, 12f); lineTo(14.6f, 7.4f); lineTo(16f, 6f)
                lineTo(22f, 12f); lineTo(16f, 18f); lineTo(14.6f, 16.6f); close()
            }
        }
    }

    val CheckCircle: ImageVector by lazy {
        icon("CheckCircle") {
            path(fill = tint) {
                moveTo(12f, 2f); curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f); curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f); close()
                moveTo(10f, 17f); lineTo(5f, 12f); lineTo(6.41f, 10.59f)
                lineTo(10f, 14.17f); lineTo(17.59f, 6.58f); lineTo(19f, 8f); lineTo(10f, 17f); close()
            }
        }
    }

    val Warning: ImageVector by lazy {
        icon("Warning") {
            path(fill = tint) {
                moveTo(1f, 21f); horizontalLineTo(23f); lineTo(12f, 2f); lineTo(1f, 21f); close()
                moveTo(13f, 18f); horizontalLineTo(11f); verticalLineTo(16f); horizontalLineTo(13f); verticalLineTo(18f); close()
                moveTo(13f, 14f); horizontalLineTo(11f); verticalLineTo(10f); horizontalLineTo(13f); verticalLineTo(14f); close()
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            path(fill = tint) {
                moveTo(15.5f, 14f); horizontalLineTo(14.71f); lineTo(14.43f, 13.73f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f); curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
                curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f); curveTo(11.11f, 16f, 12.59f, 15.41f, 13.73f, 14.43f)
                lineTo(14f, 14.71f); verticalLineTo(15.5f); lineTo(19f, 20.49f); lineTo(20.49f, 19f); lineTo(15.5f, 14f); close()
                moveTo(9.5f, 14f); curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f); curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
                curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f); close()
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            path(fill = tint) {
                moveTo(19f, 6.41f); lineTo(17.59f, 5f); lineTo(12f, 10.59f); lineTo(6.41f, 5f)
                lineTo(5f, 6.41f); lineTo(10.59f, 12f); lineTo(5f, 17.59f); lineTo(6.41f, 19f)
                lineTo(12f, 13.41f); lineTo(17.59f, 19f); lineTo(19f, 17.59f); lineTo(13.41f, 12f); close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            path(fill = tint) {
                moveTo(10f, 6f); lineTo(8.59f, 7.41f); lineTo(13.17f, 12f); lineTo(8.59f, 16.59f)
                lineTo(10f, 18f); lineTo(16f, 12f); close()
            }
        }
    }

    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack") {
            path(fill = tint) {
                moveTo(20f, 11f); horizontalLineTo(7.83f); lineTo(13.42f, 5.41f); lineTo(12f, 4f)
                lineTo(4f, 12f); lineTo(12f, 20f); lineTo(13.41f, 18.59f); lineTo(7.83f, 13f)
                horizontalLineTo(20f); verticalLineTo(11f); close()
            }
        }
    }

    val ArrowDownward: ImageVector by lazy {
        icon("ArrowDownward") {
            path(fill = tint) {
                moveTo(20f, 12f); lineTo(18.59f, 10.59f); lineTo(13f, 16.17f); verticalLineTo(4f)
                horizontalLineTo(11f); verticalLineTo(16.17f); lineTo(5.42f, 10.58f); lineTo(4f, 12f)
                lineTo(12f, 20f); lineTo(20f, 12f); close()
            }
        }
    }

    val Delete: ImageVector by lazy {
        icon("Delete") {
            path(fill = tint) {
                moveTo(6f, 19f); curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f); horizontalLineTo(16f)
                curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f); verticalLineTo(7f); horizontalLineTo(6f); verticalLineTo(19f); close()
                moveTo(19f, 4f); horizontalLineTo(15.5f); lineTo(14.5f, 3f); horizontalLineTo(9.5f)
                lineTo(8.5f, 4f); horizontalLineTo(5f); verticalLineTo(6f); horizontalLineTo(19f); verticalLineTo(4f); close()
            }
        }
    }

    val Tune: ImageVector by lazy {
        icon("Tune") {
            path(fill = tint) {
                moveTo(3f, 17f); verticalLineTo(19f); horizontalLineTo(9f); verticalLineTo(17f); horizontalLineTo(3f); close()
                moveTo(3f, 5f); verticalLineTo(7f); horizontalLineTo(13f); verticalLineTo(5f); horizontalLineTo(3f); close()
                moveTo(13f, 21f); verticalLineTo(19f); horizontalLineTo(21f); verticalLineTo(17f); horizontalLineTo(13f)
                verticalLineTo(15f); horizontalLineTo(11f); verticalLineTo(21f); horizontalLineTo(13f); close()
                moveTo(7f, 9f); verticalLineTo(11f); horizontalLineTo(3f); verticalLineTo(13f); horizontalLineTo(7f)
                verticalLineTo(15f); horizontalLineTo(9f); verticalLineTo(9f); horizontalLineTo(7f); close()
                moveTo(21f, 13f); verticalLineTo(11f); horizontalLineTo(11f); verticalLineTo(13f); horizontalLineTo(21f); close()
                moveTo(15f, 9f); horizontalLineTo(17f); verticalLineTo(7f); horizontalLineTo(21f); verticalLineTo(5f)
                horizontalLineTo(17f); verticalLineTo(3f); horizontalLineTo(15f); verticalLineTo(9f); close()
            }
        }
    }

    val Memory: ImageVector by lazy {
        icon("Memory") {
            path(fill = tint) {
                moveTo(15f, 9f); horizontalLineTo(9f); verticalLineTo(15f); horizontalLineTo(15f); verticalLineTo(9f); close()
                moveTo(13f, 13f); horizontalLineTo(11f); verticalLineTo(11f); horizontalLineTo(13f); verticalLineTo(13f); close()
                moveTo(21f, 11f); verticalLineTo(9f); horizontalLineTo(19f); verticalLineTo(7f)
                curveTo(19f, 5.9f, 18.1f, 5f, 17f, 5f); horizontalLineTo(15f); verticalLineTo(3f)
                horizontalLineTo(13f); verticalLineTo(5f); horizontalLineTo(11f); verticalLineTo(3f)
                horizontalLineTo(9f); verticalLineTo(5f); horizontalLineTo(7f)
                curveTo(5.9f, 5f, 5f, 5.9f, 5f, 7f); verticalLineTo(9f); horizontalLineTo(3f)
                verticalLineTo(11f); horizontalLineTo(5f); verticalLineTo(13f); horizontalLineTo(3f)
                verticalLineTo(15f); horizontalLineTo(5f); verticalLineTo(17f)
                curveTo(5f, 18.1f, 5.9f, 19f, 7f, 19f); horizontalLineTo(9f); verticalLineTo(21f)
                horizontalLineTo(11f); verticalLineTo(19f); horizontalLineTo(13f); verticalLineTo(21f)
                horizontalLineTo(15f); verticalLineTo(19f); horizontalLineTo(17f)
                curveTo(18.1f, 19f, 19f, 18.1f, 19f, 17f); verticalLineTo(15f); horizontalLineTo(21f)
                verticalLineTo(13f); horizontalLineTo(19f); verticalLineTo(11f); horizontalLineTo(21f); close()
                moveTo(17f, 17f); horizontalLineTo(7f); verticalLineTo(7f); horizontalLineTo(17f); verticalLineTo(17f); close()
            }
        }
    }

    val Translate: ImageVector by lazy {
        icon("Translate") {
            path(fill = tint) {
                moveTo(12.87f, 15.07f); lineTo(10.33f, 12.56f); lineTo(10.36f, 12.53f)
                curveTo(12.1f, 10.59f, 13.34f, 8.36f, 14.07f, 6f); horizontalLineTo(17f)
                verticalLineTo(4f); horizontalLineTo(10f); verticalLineTo(2f); horizontalLineTo(8f)
                verticalLineTo(4f); horizontalLineTo(1f); verticalLineTo(5.99f); horizontalLineTo(12.17f)
                curveTo(11.5f, 7.92f, 10.44f, 9.75f, 9f, 11.35f)
                curveTo(8.07f, 10.32f, 7.3f, 9.19f, 6.69f, 8f); horizontalLineTo(4.69f)
                curveTo(5.42f, 9.63f, 6.42f, 11.17f, 7.67f, 12.56f); lineTo(2.58f, 17.58f)
                lineTo(4f, 19f); lineTo(9f, 14f); lineTo(12.11f, 17.11f); lineTo(12.87f, 15.07f); close()
                moveTo(18.5f, 10f); horizontalLineTo(16.5f); lineTo(12f, 22f); horizontalLineTo(14f)
                lineTo(15.12f, 19f); horizontalLineTo(19.87f); lineTo(21f, 22f); horizontalLineTo(23f)
                lineTo(18.5f, 10f); close()
                moveTo(15.88f, 17f); lineTo(17.5f, 12.67f); lineTo(19.12f, 17f); horizontalLineTo(15.88f); close()
            }
        }
    }

    val GraphicEq: ImageVector by lazy {
        icon("GraphicEq") {
            path(fill = tint) {
                moveTo(7f, 18f); horizontalLineTo(9f); verticalLineTo(6f); horizontalLineTo(7f); verticalLineTo(18f); close()
                moveTo(11f, 22f); horizontalLineTo(13f); verticalLineTo(2f); horizontalLineTo(11f); verticalLineTo(22f); close()
                moveTo(3f, 14f); horizontalLineTo(5f); verticalLineTo(10f); horizontalLineTo(3f); verticalLineTo(14f); close()
                moveTo(15f, 18f); horizontalLineTo(17f); verticalLineTo(6f); horizontalLineTo(15f); verticalLineTo(18f); close()
                moveTo(19f, 10f); verticalLineTo(14f); horizontalLineTo(21f); verticalLineTo(10f); horizontalLineTo(19f); close()
            }
        }
    }

    val Storage: ImageVector by lazy {
        icon("Storage") {
            path(fill = tint) {
                moveTo(2f, 20f); horizontalLineTo(22f); verticalLineTo(16f); horizontalLineTo(2f); verticalLineTo(20f); close()
                moveTo(4f, 17f); horizontalLineTo(6f); verticalLineTo(19f); horizontalLineTo(4f); verticalLineTo(17f); close()
                moveTo(2f, 4f); verticalLineTo(8f); horizontalLineTo(22f); verticalLineTo(4f); horizontalLineTo(2f); close()
                moveTo(6f, 7f); horizontalLineTo(4f); verticalLineTo(5f); horizontalLineTo(6f); verticalLineTo(7f); close()
                moveTo(2f, 14f); horizontalLineTo(22f); verticalLineTo(10f); horizontalLineTo(2f); verticalLineTo(14f); close()
                moveTo(4f, 11f); horizontalLineTo(6f); verticalLineTo(13f); horizontalLineTo(4f); verticalLineTo(11f); close()
            }
        }
    }

    val Lan: ImageVector by lazy {
        icon("Lan") {
            path(fill = tint) {
                moveTo(13f, 22f); horizontalLineTo(21f); verticalLineTo(15f); horizontalLineTo(18f)
                verticalLineTo(11f); horizontalLineTo(13f); verticalLineTo(9f); horizontalLineTo(16f)
                verticalLineTo(2f); horizontalLineTo(8f); verticalLineTo(9f); horizontalLineTo(11f)
                verticalLineTo(11f); horizontalLineTo(6f); verticalLineTo(15f); horizontalLineTo(3f)
                verticalLineTo(22f); horizontalLineTo(11f); verticalLineTo(15f); horizontalLineTo(8f)
                verticalLineTo(13f); horizontalLineTo(16f); verticalLineTo(15f); horizontalLineTo(13f)
                verticalLineTo(22f); close()
                moveTo(10f, 7f); verticalLineTo(4f); horizontalLineTo(14f); verticalLineTo(7f); horizontalLineTo(10f); close()
                moveTo(9f, 17f); verticalLineTo(20f); horizontalLineTo(5f); verticalLineTo(17f); horizontalLineTo(9f); close()
                moveTo(19f, 17f); verticalLineTo(20f); horizontalLineTo(15f); verticalLineTo(17f); horizontalLineTo(19f); close()
            }
        }
    }

    val Speed: ImageVector by lazy {
        icon("Speed") {
            path(fill = tint) {
                moveTo(20.38f, 8.57f); lineTo(19.15f, 10.43f)
                curveTo(19.7f, 11.49f, 20f, 12.71f, 20f, 14f)
                curveTo(20f, 15.11f, 19.77f, 16.17f, 19.35f, 17.13f); horizontalLineTo(4.65f)
                curveTo(4.23f, 16.17f, 4f, 15.11f, 4f, 14f)
                curveTo(4f, 9.58f, 7.58f, 6f, 12f, 6f)
                curveTo(13.57f, 6f, 15.03f, 6.46f, 16.27f, 7.25f); lineTo(17.71f, 5.81f)
                curveTo(16.07f, 4.67f, 14.11f, 4f, 12f, 4f)
                curveTo(6.48f, 4f, 2f, 8.48f, 2f, 14f)
                curveTo(2f, 15.85f, 2.5f, 17.58f, 3.38f, 19.07f); horizontalLineTo(20.62f)
                curveTo(21.5f, 17.58f, 22f, 15.85f, 22f, 14f)
                curveTo(22f, 12.01f, 21.41f, 10.15f, 20.38f, 8.57f); close()
                moveTo(10.59f, 15.41f); curveTo(11.37f, 16.19f, 12.64f, 16.19f, 13.42f, 15.41f)
                lineTo(19.07f, 6.93f); lineTo(10.59f, 12.59f)
                curveTo(9.81f, 13.37f, 9.81f, 14.63f, 10.59f, 15.41f); close()
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            path(fill = tint) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f)
                curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f); horizontalLineTo(17.65f)
                curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
                curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f); lineTo(13f, 11f)
                horizontalLineTo(20f); verticalLineTo(4f); lineTo(17.65f, 6.35f); close()
            }
        }
    }

    val Sparkles: ImageVector by lazy {
        icon("Sparkles") {
            path(fill = tint, pathFillType = PathFillType.NonZero) {
                moveTo(12f, 2f); lineTo(14.5f, 8.5f); lineTo(21f, 11f); lineTo(14.5f, 13.5f)
                lineTo(12f, 20f); lineTo(9.5f, 13.5f); lineTo(3f, 11f); lineTo(9.5f, 8.5f); close()
                moveTo(19f, 15f); lineTo(20.1f, 17.9f); lineTo(23f, 19f); lineTo(20.1f, 20.1f)
                lineTo(19f, 23f); lineTo(17.9f, 20.1f); lineTo(15f, 19f); lineTo(17.9f, 17.9f); close()
            }
        }
    }

    val MoreVert: ImageVector by lazy {
        icon("MoreVert") {
            path(fill = tint) {
                moveTo(12f, 8f); curveTo(13.1f, 8f, 14f, 7.1f, 14f, 6f)
                curveTo(14f, 4.9f, 13.1f, 4f, 12f, 4f); curveTo(10.9f, 4f, 10f, 4.9f, 10f, 6f)
                curveTo(10f, 7.1f, 10.9f, 8f, 12f, 8f); close()
                moveTo(12f, 10f); curveTo(10.9f, 10f, 10f, 10.9f, 10f, 12f)
                curveTo(10f, 13.1f, 10.9f, 14f, 12f, 14f); curveTo(13.1f, 14f, 14f, 13.1f, 14f, 12f)
                curveTo(14f, 10.9f, 13.1f, 10f, 12f, 10f); close()
                moveTo(12f, 16f); curveTo(10.9f, 16f, 10f, 16.9f, 10f, 18f)
                curveTo(10f, 19.1f, 10.9f, 20f, 12f, 20f); curveTo(13.1f, 20f, 14f, 19.1f, 14f, 18f)
                curveTo(14f, 16.9f, 13.1f, 16f, 12f, 16f); close()
            }
        }
    }

    val ShieldCheck: ImageVector by lazy {
        icon("ShieldCheck") {
            path(fill = tint) {
                moveTo(12f, 1f); lineTo(3f, 5f); verticalLineTo(11f)
                curveTo(3f, 16.55f, 6.84f, 21.74f, 12f, 23f)
                curveTo(17.16f, 21.74f, 21f, 16.55f, 21f, 11f); verticalLineTo(5f); lineTo(12f, 1f); close()
                moveTo(10f, 17f); lineTo(6f, 13f); lineTo(7.41f, 11.59f)
                lineTo(10f, 14.17f); lineTo(16.59f, 7.58f); lineTo(18f, 9f); lineTo(10f, 17f); close()
            }
        }
    }

    val ExportFile: ImageVector by lazy {
        icon("ExportFile") {
            path(
                stroke = tint,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14f, 3f); horizontalLineTo(7f); curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
                verticalLineTo(19f); curveTo(5f, 20.1f, 5.9f, 21f, 7f, 21f); horizontalLineTo(17f)
                curveTo(18.1f, 21f, 19f, 20.1f, 19f, 19f); verticalLineTo(8f); lineTo(14f, 3f); close()
                moveTo(14f, 3f); verticalLineTo(8f); horizontalLineTo(19f)
                moveTo(12f, 11f); verticalLineTo(17f)
                moveTo(9.5f, 14.5f); lineTo(12f, 17f); lineTo(14.5f, 14.5f)
            }
        }
    }
}
