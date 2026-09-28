package io.github.feedbacklib.android.internal.annotate

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixels [left, right) × [top, bottom). */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
    val isEmpty: Boolean
        get() = width <= 0 || height <= 0

    fun intersect(other: PixelRect): PixelRect =
        PixelRect(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))

    companion object {
        /** Every pixel the float rectangle touches. */
        fun covering(left: Float, top: Float, right: Float, bottom: Float): PixelRect =
            PixelRect(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())
    }
}

/** Straight (non-premultiplied) ARGB pixels of [rect], row by row, addressed in surface coordinates. */
internal class PixelRegion(val rect: PixelRect, val pixels: IntArray = IntArray(rect.width * rect.height)) {
    operator fun get(x: Int, y: Int): Int = pixels[(y - rect.top) * rect.width + (x - rect.left)]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[(y - rect.top) * rect.width + (x - rect.left)] = argb
    }
}

/**
 * Where edits are drawn: an Android Bitmap on the device (Task 7), an IntArray in unit tests.
 * Operations read and write it in bands, so a full-size photo never needs a second full-size buffer.
 */
internal interface PixelSurface {
    val width: Int
    val height: Int
    val bounds: PixelRect
        get() = PixelRect(0, 0, width, height)

    /** [rect] lies inside [bounds]. */
    fun read(rect: PixelRect): PixelRegion

    fun write(region: PixelRegion)
}

internal class IntArraySurface(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray = IntArray(width * height),
) : PixelSurface {

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    fun fill(argb: Int) = pixels.fill(argb)

    override fun read(rect: PixelRect): PixelRegion {
        val region = PixelRegion(rect)
        for (row in 0 until rect.height) {
            System.arraycopy(pixels, (rect.top + row) * width + rect.left, region.pixels, row * rect.width, rect.width)
        }
        return region
    }

    override fun write(region: PixelRegion) {
        val rect = region.rect
        for (row in 0 until rect.height) {
            System.arraycopy(region.pixels, row * rect.width, pixels, (rect.top + row) * width + rect.left, rect.width)
        }
    }
}

/** The editor's pixel operations (spec §6). Pure functions of their arguments and the surface. */
internal object Raster {

    /** Rows handled at once: an op's extra memory is one band, not a second copy of the image. */
    const val BAND_ROWS: Int = 128

    /** [src] over [dst] with [coverage] (0..1) of [src]'s own alpha; straight ARGB, as Bitmap.getPixels gives. */
    fun blend(dst: Int, src: Int, coverage: Float): Int {
        val sa = ((src ushr 24) / 255f) * coverage.coerceIn(0f, 1f)
        if (sa <= 0f) return dst
        val da = (dst ushr 24) / 255f
        val outA = sa + da * (1f - sa)
        if (outA <= 0f) return 0
        fun channel(shift: Int): Int {
            val s = (src shr shift) and 0xFF
            val d = (dst shr shift) and 0xFF
            return ((s * sa + d * da * (1f - sa)) / outA).roundToInt().coerceIn(0, 255)
        }
        val alpha = (outA * 255f).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** The squared distance `distanceToSegment` would give, without its `sqrt`: cheap enough to call per pixel. */
    private fun distanceSquaredToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return ex * ex + ey * ey
    }

    /**
     * A round-capped line through [points] (x0, y0, x1, y1, … in surface pixels), [width] thick,
     * anti-aliased over one pixel. Each pixel takes the coverage of its nearest segment, so a
     * translucent line is never painted twice where segments meet. One point draws a dot.
     */
    fun drawStroke(surface: PixelSurface, points: FloatArray, width: Float, color: Int) {
        if (points.size < 2 || width <= 0f) return
        val radius = width / 2f
        val pad = radius + 1f
        // Wholly inside the opaque core (at least half a pixel short of the edge): coverage is 1,
        // full stop — no need to know the exact distance, so no sqrt. A pixel this far out from
        // every segment falls through to the exact, sqrt-based coverage below unchanged.
        val coreRadius = radius - 0.5f
        val coreRadiusSquared = if (coreRadius > 0f) coreRadius * coreRadius else -1f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until points.size - 1 step 2) {
            minX = min(minX, points[i])
            maxX = max(maxX, points[i])
            minY = min(minY, points[i + 1])
            maxY = max(maxY, points[i + 1])
        }
        val box = PixelRect.covering(minX - pad, minY - pad, maxX + pad, maxY + pad).intersect(surface.bounds)
        if (box.isEmpty) return
        val last = (points.size / 2 - 1) * 2 // index of the last x
        val segments = max(1, points.size / 2 - 1)
        var bandTop = box.top
        while (bandTop < box.bottom) {
            val band = PixelRect(box.left, bandTop, box.right, min(bandTop + BAND_ROWS, box.bottom))
            val coverage = FloatArray(band.width * band.height)
            var touched = false
            for (s in 0 until segments) {
                val i = min(s * 2, last)
                val j = min(s * 2 + 2, last)
                val ax = points[i]
                val ay = points[i + 1]
                val bx = points[j]
                val by = points[j + 1]
                val area = PixelRect.covering(min(ax, bx) - pad, min(ay, by) - pad, max(ax, bx) + pad, max(ay, by) + pad).intersect(band)
                if (area.isEmpty) continue
                for (y in area.top until area.bottom) {
                    for (x in area.left until area.right) {
                        val px = x + 0.5f
                        val py = y + 0.5f
                        val c: Float
                        if (distanceSquaredToSegment(px, py, ax, ay, bx, by) <= coreRadiusSquared) {
                            c = 1f
                        } else {
                            val exact = radius + 0.5f - distanceToSegment(px, py, ax, ay, bx, by)
                            if (exact <= 0f) continue
                            c = exact
                        }
                        val k = (y - band.top) * band.width + (x - band.left)
                        val clamped = min(c, 1f)
                        if (clamped > coverage[k]) {
                            coverage[k] = clamped
                            touched = true
                        }
                    }
                }
            }
            if (touched) {
                val region = surface.read(band)
                for (k in coverage.indices) {
                    if (coverage[k] > 0f) region.pixels[k] = blend(region.pixels[k], color, coverage[k])
                }
                surface.write(region)
            }
            bandTop = band.bottom
        }
    }

    /**
     * Replaces [rect] with [block]-px blocks, each filled with the average of its own pixels. Blocks
     * start at the rectangle's corner, also where the surface clips it. Nothing of the detail
     * inside a block survives, on every API level (spec §6: irreversible).
     */
    fun pixelate(surface: PixelSurface, rect: PixelRect, block: Int) {
        if (block < 1) return
        val area = rect.intersect(surface.bounds)
        if (area.isEmpty) return
        val firstTop = rect.top + (area.top - rect.top) / block * block
        val firstLeft = rect.left + (area.left - rect.left) / block * block
        var blockTop = firstTop
        while (blockTop < area.bottom) {
            val rows = PixelRect(area.left, max(blockTop, area.top), area.right, min(blockTop + block, area.bottom))
            val region = surface.read(rows)
            var blockLeft = firstLeft
            while (blockLeft < area.right) {
                fillWithAverage(region, PixelRect(max(blockLeft, area.left), rows.top, min(blockLeft + block, area.right), rows.bottom))
                blockLeft += block
            }
            surface.write(region)
            blockTop += block
        }
    }

    /**
     * Inside the circle of [radius] around ([centerX], [centerY]): what lies under it, magnified
     * [zoom] times; around it a [ringWidth] ring of [ringColor].
     *
     * Requires `zoom >= 1`: at `zoom == 1` a pixel's source is itself, and for `zoom > 1` the
     * source is strictly closer to the centre than the pixel — never farther. Both the two-sweep
     * read order and the per-band source-height bound below depend on that direction: a `zoom`
     * under 1 would expand instead of compress, so a lower `zoom` is rejected rather than drawn
     * wrong.
     *
     * Both the surface's own pixels and its source are read in bands of at most [BAND_ROWS] rows,
     * so a ×2 magnifier with a large radius over a huge photo never allocates a source square of
     * about the radius's size — the extra memory of this op stays of the order of one band, not
     * O(radius²) (controller ruling D5).
     *
     * A pixel's source always lies at least as close to the centre as the pixel itself, so
     * processing rows from the box's edges inward — down from the top to the centre row, and up
     * from the bottom to it — guarantees every band's source is read while it is still the
     * original, untouched pixels: nothing a not-yet-processed band could have written. Each band's
     * source range is also padded by one pixel either side, for rounding safety; right at the
     * centre row that padding alone would reach one row past it, into the other sweep's
     * already-written territory, even though the true mapping never does. So the sweep nearer the
     * centre has that one row of padding clamped back to the centre row instead — a defensive
     * trim, not a correctness fix: it only ever removes padding, never a pixel the mapping
     * actually needs, so it costs no accuracy.
     */
    fun magnify(surface: PixelSurface, centerX: Float, centerY: Float, radius: Float, zoom: Float, ringWidth: Float, ringColor: Int) {
        if (radius <= 0f || zoom < 1f) return
        val outer = radius + max(0f, ringWidth)
        val box = PixelRect.covering(centerX - outer - 1f, centerY - outer - 1f, centerX + outer + 1f, centerY + outer + 1f)
            .intersect(surface.bounds)
        if (box.isEmpty) return
        val reach = radius / zoom
        val sourceBounds = PixelRect.covering(centerX - reach - 1f, centerY - reach - 1f, centerX + reach + 1f, centerY + reach + 1f)
            .intersect(surface.bounds)
        if (sourceBounds.isEmpty) return
        // Squared distances, compared in Double, decide inside-vs-ring without a sqrt for what is
        // normally the large majority of pixels (the disk's interior); only a ring pixel needs the
        // real distance, for its fractional edge coverage.
        val radiusSquared = radius.toDouble() * radius

        fun applyBand(band: PixelRect, sourceRowFloor: Int) {
            val rawTop = floor(centerY + (band.top - centerY) / zoom - 1f).toInt()
            val srcTop = rawTop.coerceIn(sourceRowFloor, sourceBounds.bottom - 1)
            val rawBottom = ceil(centerY + (band.bottom - centerY) / zoom + 1f).toInt()
            val srcBottom = rawBottom.coerceIn(srcTop + 1, sourceBounds.bottom)
            val sourceBand = PixelRect(sourceBounds.left, srcTop, sourceBounds.right, srcBottom)
            val source = surface.read(sourceBand)
            val region = surface.read(band)
            for (y in band.top until band.bottom) {
                for (x in band.left until band.right) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val dx = px - centerX
                    val dy = py - centerY
                    if (dx.toDouble() * dx + dy.toDouble() * dy <= radiusSquared) {
                        val sx = floor(centerX + dx / zoom).toInt().coerceIn(sourceBounds.left, sourceBounds.right - 1)
                        val sy = floor(centerY + dy / zoom).toInt().coerceIn(sourceBand.top, sourceBand.bottom - 1)
                        region[x, y] = source[sx, sy]
                    } else {
                        val distance = hypot(dx, dy)
                        val coverage = outer + 0.5f - distance
                        if (coverage > 0f) region[x, y] = blend(region[x, y], ringColor, min(coverage, 1f))
                    }
                }
            }
            surface.write(region)
        }

        // The row nearest the centre: rows above it are handled by the upper sweep, it and rows
        // below it by the lower sweep — a clean, non-overlapping partition of the box.
        val splitRow = centerY.roundToInt().coerceIn(box.top, box.bottom)

        // Upper sweep: top to bottom, i.e. farthest from the centre first. Its bands may freely
        // read ahead past the centre row — nothing there has been written yet.
        var top = box.top
        while (top < splitRow) {
            val band = PixelRect(box.left, top, box.right, min(top + BAND_ROWS, splitRow))
            applyBand(band, sourceBounds.top)
            top = band.bottom
        }

        // Lower sweep: bottom to top, again farthest first. Its source is clamped to never read
        // above the centre row, which the upper sweep has already written.
        var bottom = box.bottom
        while (bottom > splitRow) {
            val band = PixelRect(box.left, max(bottom - BAND_ROWS, splitRow), box.right, bottom)
            applyBand(band, splitRow)
            bottom = band.top
        }
    }

    private fun fillWithAverage(region: PixelRegion, cell: PixelRect) {
        if (cell.isEmpty) return
        var a = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in cell.top until cell.bottom) {
            for (x in cell.left until cell.right) {
                val p = region[x, y]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
        }
        val n = cell.width.toLong() * cell.height
        fun average(sum: Long): Int = ((sum + n / 2) / n).toInt()
        val color = (average(a) shl 24) or (average(r) shl 16) or (average(g) shl 8) or average(b)
        for (y in cell.top until cell.bottom) {
            for (x in cell.left until cell.right) region[x, y] = color
        }
    }
}
