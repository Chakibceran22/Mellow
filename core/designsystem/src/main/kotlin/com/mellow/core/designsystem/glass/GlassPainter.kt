package com.mellow.core.designsystem.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.light.LightingEngine
import com.mellow.core.designsystem.theme.ContinuousCornerPower
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The shape of the glass: distance to its edge, and which way is out. Shared by both shaders. */
private fun edgeSource(continuous: Boolean): String =
    if (continuous) {
        """
uniform float uPower;

float3 edge(float2 p, float2 halfSize) {
    float2 q = abs(p) - halfSize + uRadius;
    float2 side = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    if (q.x <= 0.0 || q.y <= 0.0) {
        // Clear of every corner: the nearest edge is a straight one.
        return q.x > q.y ? float3(q.x - uRadius, side.x, 0.0) : float3(q.y - uRadius, 0.0, side.y);
    }
    float sum = pow(q.x, uPower) + pow(q.y, uPower);
    float2 g = float2(pow(q.x, uPower - 1.0), pow(q.y, uPower - 1.0));
    return float3(pow(sum, 1.0 / uPower) - uRadius, side * normalize(g + float2(0.00001, 0.0)));
}
"""
    } else {
        """
float3 edge(float2 p, float2 halfSize) {
    float2 q = abs(p) - halfSize + uRadius;
    float2 side = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float2 o = max(q, 0.0);
    float len = length(o);
    float2 g = len > 0.0001 ? o / len : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    return float3(len + min(max(q.x, q.y), 0.0) - uRadius, side * g);
}
"""
    }

/**
 * The glass itself, as a fragment shader over the scene's frost.
 *
 * A piece of glass is modelled as a slab with a flat face and a curved rim. `content` is
 * what lies behind it, already blurred. Per pixel the shader:
 *
 *  1. finds the distance to the edge of the shape and which way is out, in closed form;
 *  2. across the flat face, does one straight lookup and nothing else;
 *  3. inside the rim, bends the lookup toward the centre, as a lens edge does, and splits
 *     red and blue by a hair for dispersion;
 *  4. lifts saturation and lays in the tint;
 *  5. lights the rim from the engine's key light: a line of light that is brightest where
 *     the edge faces the lamp and fades along it, a fainter one where light leaves through
 *     the far edge, and a broad sheen on the curve between;
 *  6. adds a small lamp under the finger while the surface is pressed.
 *
 * It is written for a mid-range mobile GPU: no `pow` anywhere on the common path (powers
 * are whole numbers, spelled out as multiplications), and everything that is the same for
 * every pixel, such as where the lamp is, arrives precomputed as a uniform.
 *
 * There are two builds of it. A capsule or circle has round corners, so its distance is a
 * plain `length`. A continuous corner follows `x^n + y^n = r^n`, which does need `pow`,
 * but only in the four corner regions.
 */
private fun glassSource(continuous: Boolean): String {
    val edge = edgeSource(continuous)
    return """
uniform shader content;
uniform float2 uSize;
uniform float2 uOrigin;
uniform float uRadius;
uniform float uBezel;
uniform float uRefraction;
uniform float uHairline;
uniform float2 uLamp;
uniform float3 uHalf;
uniform float4 uTint;
uniform float uSaturation;
uniform float uRim;
uniform float uSheen;
uniform float3 uLightColor;
uniform float3 uTouch;
uniform float uTouchRadius;
$edge
half4 main(float2 xy) {
    float2 halfSize = uSize * 0.5;
    float2 local = xy - uOrigin;
    float2 p = local - halfSize;

    // x: signed distance to the edge. yz: unit direction pointing out across it.
    float3 e = edge(p, halfSize);
    float d = e.x;
    float mask = clamp(0.5 - d, 0.0, 1.0);
    if (mask <= 0.0) {
        return half4(0.0);
    }
    float2 outward = e.yz;

    // 0 across the flat face, rising to 1 at the very edge.
    float rise = clamp(1.0 + d / uBezel, 0.0, 1.0);
    float curve = rise * rise;

    float3 col;
    if (rise <= 0.0) {
        col = content.eval(xy).rgb;
    } else {
        float2 bend = -outward * curve * rise * uRefraction;
        col = float3(
            content.eval(xy + bend * 1.16).r,
            content.eval(xy + bend).g,
            content.eval(xy + bend * 0.86).b);
    }

    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(luma), col, uSaturation);
    col = mix(col, uTint.rgb, uTint.a);

    if (rise > 0.0) {
        // The key light is treated as a lamp a short way off (uLamp), so each point on the
        // edge sees it from a different angle and the line of light swells and fades along
        // the rim instead of outlining it evenly. Its mirror image is the light leaving
        // through the opposite edge.
        float toward = max(dot(outward, normalize(uLamp - p)), 0.0);
        float away = max(dot(outward, normalize(-uLamp - p)), 0.0);
        float hair = clamp(1.0 + d / uHairline, 0.0, 1.0);
        float lit = toward * toward * (0.55 + 0.45 * toward);
        float leaving = away * away * away * 0.5;
        float rim = hair * (0.04 + lit + leaving);

        // Sheen: a Blinn-Phong highlight on the curve of the rim, to the sixteenth power.
        float3 n = normalize(float3(outward * curve * 1.6, 1.0));
        float s = max(dot(n, uHalf), 0.0);
        float s2 = s * s;
        float s4 = s2 * s2;
        float s8 = s4 * s4;

        col += uLightColor * (rim * uRim + s8 * s8 * curve * uSheen);

        // The far rim sits in the slab's own shadow, which is what gives it thickness.
        col *= 1.0 - 0.12 * curve * away * (1.0 - hair);
    }

    // A soft lamp under the finger.
    if (uTouch.z > 0.001) {
        float2 fromTouch = local - uTouch.xy;
        col += uLightColor * 0.20 * uTouch.z * exp(-dot(fromTouch, fromTouch) / (uTouchRadius * uTouchRadius));
    }

    col = clamp(col, 0.0, 1.0);
    return half4(col * mask, mask);
}
"""
}

/**
 * Glass with nothing behind it worth bending: a second, far cheaper way to draw it.
 *
 * The refracting shader above needs a copy of what is behind the glass, which costs two
 * off-screen passes for every surface, every frame. That is worth paying where words and
 * pictures pass beneath the glass and bend at its rim. It buys nothing over the lit room,
 * which is smooth, or on top of another piece of glass, which is already frosted: there is
 * nothing there to bend.
 *
 * So here the glass is painted straight onto the screen, in one go. Its body is only its
 * tint, laid over whatever is already there by ordinary blending; the rim light, the
 * sheen and the lamp under the finger are the same as above, added on top. It also paints
 * its own soft shadow, outside its edge only, because a see-through body cannot hide the
 * system's shadow the way a solid one does.
 */
private fun plainGlassSource(continuous: Boolean): String = """
uniform float2 uSize;
uniform float uRadius;
uniform float uBezel;
uniform float uHairline;
uniform float2 uLamp;
uniform float3 uHalf;
uniform float4 uTint;
uniform float uRim;
uniform float uSheen;
uniform float3 uLightColor;
uniform float3 uTouch;
uniform float uTouchRadius;
uniform float4 uShadow;
uniform float2 uShadowShape;
${edgeSource(continuous)}
half4 main(float2 xy) {
    float2 halfSize = uSize * 0.5;
    float2 p = xy - halfSize;

    float3 e = edge(p, halfSize);
    float d = e.x;
    float mask = clamp(0.5 - d, 0.0, 1.0);

    // The shadow: the same shape, a little lower (uShadowShape.x) and soft (uShadowShape.y).
    float below = edge(p - float2(0.0, uShadowShape.x), halfSize).x;
    float shadow = uShadow.a * (1.0 - smoothstep(-0.4 * uShadowShape.y, uShadowShape.y, below)) * (1.0 - mask);
    if (mask <= 0.0) {
        return half4(uShadow.rgb * shadow, shadow);
    }
    float2 outward = e.yz;

    float rise = clamp(1.0 + d / uBezel, 0.0, 1.0);
    float curve = rise * rise;

    float3 light = float3(0.0);
    float shade = 0.0;
    if (rise > 0.0) {
        float toward = max(dot(outward, normalize(uLamp - p)), 0.0);
        float away = max(dot(outward, normalize(-uLamp - p)), 0.0);
        float hair = clamp(1.0 + d / uHairline, 0.0, 1.0);
        float lit = toward * toward * (0.55 + 0.45 * toward);
        float leaving = away * away * away * 0.5;
        float rim = hair * (0.04 + lit + leaving);

        float3 n = normalize(float3(outward * curve * 1.6, 1.0));
        float s = max(dot(n, uHalf), 0.0);
        float s2 = s * s;
        float s4 = s2 * s2;
        float s8 = s4 * s4;

        light = uLightColor * (rim * uRim + s8 * s8 * curve * uSheen);
        // The far rim sits in the slab's own shadow.
        shade = 0.12 * curve * away * (1.0 - hair);
    }
    if (uTouch.z > 0.001) {
        float2 fromTouch = xy - uTouch.xy;
        light += uLightColor * 0.20 * uTouch.z * exp(-dot(fromTouch, fromTouch) / (uTouchRadius * uTouchRadius));
    }

    // Premultiplied: the tint covers by its own alpha, the shade darkens a little of what
    // is left, and the light is simply added.
    float cover = uTint.a + shade * (1.0 - uTint.a);
    float3 body = uTint.rgb * uTint.a * (1.0 - shade) + light;
    return half4(min(body, float3(1.0)) * mask + uShadow.rgb * shadow, cover * mask + shadow);
}
"""

/** Everything one frame of one glass surface needs. Reused across frames; never allocated per draw. */
internal class GlassFrame {
    /** The strip of frost under this surface, or null in the one frame before there is one. */
    var band: FrostBand? = null

    /** True when there is nothing behind this surface worth bending, and it is painted directly. */
    var plain = false
    var origin: Offset = Offset.Zero
    var shape: GlassShape = GlassShape.Capsule
    lateinit var style: GlassStyle
    var lightColor: Color = Color.White
    var pressAmount = 0f
    var pressPosition: Offset = Offset.Unspecified
}

/**
 * Draws the body of a glass surface at the best fidelity the device supports:
 *
 *  - Android 13+: the scene's frost, refracted and lit in one shader pass.
 *  - Android 12: the frost and a tint, with a flat hairline for the rim.
 *  - Older: a near-opaque fill and hairline. No blur exists to see through.
 */
internal class GlassPainter {

    private val refractive: RefractiveGlass? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RefractiveGlass() else null

    private val plain: PlainGlass? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PlainGlass() else null

    fun DrawScope.drawGlass(layer: GraphicsLayer, backdrop: Backdrop?, engine: LightingEngine, frame: GlassFrame) {
        val radius = when (val shape = frame.shape) {
            GlassShape.Capsule -> min(size.width, size.height) / 2f
            is GlassShape.Continuous -> min(shape.radius.toPx(), min(size.width, size.height) / 2f)
        }
        val band = frame.band
        when {
            frame.plain && plain != null -> with(plain) { draw(engine, frame, radius) }
            backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> drawFlat(frame.style, radius)
            // No frost under it yet: leave the body out for this one frame rather than
            // flash something that is not glass.
            band == null -> Unit
            refractive != null -> with(refractive) { draw(layer, band, engine, frame, radius) }
            else -> drawFrosted(layer, band, frame, radius)
        }
    }

    private fun DrawScope.drawFrosted(layer: GraphicsLayer, band: FrostBand, frame: GlassFrame, radius: Float) {
        val style = frame.style
        layer.record { with(band) { drawPatch(frame.origin) } }
        layer.topLeft = IntOffset.Zero
        layer.setRoundRectOutline(Offset.Zero, size, radius)
        layer.clip = true
        drawLayer(layer)
        drawRoundRect(style.tint, cornerRadius = CornerRadius(radius))
        drawHairline(style, radius)
    }

    private fun DrawScope.drawFlat(style: GlassStyle, radius: Float) {
        drawRoundRect(style.fallback, cornerRadius = CornerRadius(radius))
        drawHairline(style, radius)
    }

    private fun DrawScope.drawHairline(style: GlassStyle, radius: Float) {
        drawRoundRect(style.hairline, cornerRadius = CornerRadius(radius), style = Stroke(width = 1.dp.toPx()))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class RefractiveGlass {

    private val round = RuntimeShader(glassSource(continuous = false))
    private val continuous = RuntimeShader(glassSource(continuous = true))

    fun DrawScope.draw(layer: GraphicsLayer, band: FrostBand, engine: LightingEngine, frame: GlassFrame, radius: Float) {
        val style = frame.style

        // This surface's patch of frost, scaled up, with a pixel of margin so the edge of
        // the shape has room to fade out.
        layer.record(
            size = IntSize(ceil(size.width).toInt() + Margin * 2, ceil(size.height).toInt() + Margin * 2),
        ) {
            with(band) { drawPatch(frame.origin, inset = Margin.toFloat()) }
        }
        layer.topLeft = IntOffset(-Margin, -Margin)

        val isContinuous = frame.shape is GlassShape.Continuous
        val shader = if (isContinuous) continuous else round
        if (isContinuous) shader.setFloatUniform("uPower", ContinuousCornerPower)
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uOrigin", Margin.toFloat(), Margin.toFloat())
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uBezel", min(style.bezel.toPx(), min(size.width, size.height) / 2f))
        shader.setFloatUniform("uRefraction", style.refraction.toPx())
        shader.setFloatUniform("uHairline", 1.25.dp.toPx())

        // Per-surface constants of the lighting, worked out here once instead of per pixel:
        // where the lamp sits relative to this surface, and the half-vector for the sheen.
        val flat = max(sqrt(engine.keyX * engine.keyX + engine.keyY * engine.keyY), 0.0001f)
        val reach = max(size.width, size.height) * 0.75f
        shader.setFloatUniform("uLamp", engine.keyX / flat * reach, engine.keyY / flat * reach)
        val hz = engine.keyZ + 1f
        val half = sqrt(engine.keyX * engine.keyX + engine.keyY * engine.keyY + hz * hz)
        shader.setFloatUniform("uHalf", engine.keyX / half, engine.keyY / half, hz / half)

        shader.setFloatUniform("uTint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
        shader.setFloatUniform("uSaturation", style.saturation)
        // The rim brightens a little with the music.
        shader.setFloatUniform("uRim", style.rim * (1f + 0.45f * engine.energy))
        shader.setFloatUniform("uSheen", style.sheen * (1f + 0.45f * engine.energy))
        shader.setFloatUniform("uLightColor", frame.lightColor.red, frame.lightColor.green, frame.lightColor.blue)

        val touch = frame.pressPosition.takeIf { !it.x.isNaN() && !it.y.isNaN() } ?: Offset(size.width / 2f, size.height / 2f)
        shader.setFloatUniform("uTouch", touch.x, touch.y, frame.pressAmount.coerceIn(0f, 1f))
        shader.setFloatUniform("uTouchRadius", max(size.height * 0.9f, 48.dp.toPx()))

        // A runtime-shader effect snapshots its uniforms, so it is rebuilt every frame.
        layer.renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        drawLayer(layer)
    }

    private companion object {
        const val Margin = 1
    }
}

/** Paints glass directly, with [plainGlassSource]. No copy of the backdrop, no off-screen pass. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PlainGlass {

    private val round = RuntimeShader(plainGlassSource(continuous = false))
    private val continuous = RuntimeShader(plainGlassSource(continuous = true))
    private val roundBrush = ShaderBrush(round)
    private val continuousBrush = ShaderBrush(continuous)

    fun DrawScope.draw(engine: LightingEngine, frame: GlassFrame, radius: Float) {
        val style = frame.style
        val isContinuous = frame.shape is GlassShape.Continuous
        val shader = if (isContinuous) continuous else round
        if (isContinuous) shader.setFloatUniform("uPower", ContinuousCornerPower)
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uBezel", min(style.bezel.toPx(), min(size.width, size.height) / 2f))
        shader.setFloatUniform("uHairline", 1.25.dp.toPx())

        val flat = max(sqrt(engine.keyX * engine.keyX + engine.keyY * engine.keyY), 0.0001f)
        val reach = max(size.width, size.height) * 0.75f
        shader.setFloatUniform("uLamp", engine.keyX / flat * reach, engine.keyY / flat * reach)
        val hz = engine.keyZ + 1f
        val half = sqrt(engine.keyX * engine.keyX + engine.keyY * engine.keyY + hz * hz)
        shader.setFloatUniform("uHalf", engine.keyX / half, engine.keyY / half, hz / half)

        shader.setFloatUniform("uTint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
        shader.setFloatUniform("uRim", style.rim * (1f + 0.45f * engine.energy))
        shader.setFloatUniform("uSheen", style.sheen * (1f + 0.45f * engine.energy))
        shader.setFloatUniform("uLightColor", frame.lightColor.red, frame.lightColor.green, frame.lightColor.blue)

        val touch = frame.pressPosition.takeIf { !it.x.isNaN() && !it.y.isNaN() } ?: Offset(size.width / 2f, size.height / 2f)
        shader.setFloatUniform("uTouch", touch.x, touch.y, frame.pressAmount.coerceIn(0f, 1f))
        shader.setFloatUniform("uTouchRadius", max(size.height * 0.9f, 48.dp.toPx()))

        // The shadow a raised surface casts, sized from how far it is raised.
        val elevation = style.elevation.toPx()
        val drop = elevation * ShadowDrop
        val blur = elevation * ShadowBlur
        shader.setFloatUniform("uShadow", style.shadow.red, style.shadow.green, style.shadow.blue, style.shadow.alpha * ShadowStrength)
        shader.setFloatUniform("uShadowShape", drop, max(blur, 1f))

        // Painted past its own bounds, by as far as the shadow reaches.
        val spill = ceil(drop + blur)
        drawRect(
            brush = if (isContinuous) continuousBrush else roundBrush,
            topLeft = Offset(-spill, -spill),
            size = Size(size.width + spill * 2, size.height + spill * 2),
        )
    }

    private companion object {
        // Chosen to sit beside the system's own shadows, which the refracting glass uses.
        const val ShadowDrop = 0.45f
        const val ShadowBlur = 1.5f
        const val ShadowStrength = 0.26f
    }
}
