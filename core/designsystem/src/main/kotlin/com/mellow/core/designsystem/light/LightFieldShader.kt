package com.mellow.core.designsystem.light

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The room, rendered per pixel on the GPU.
 *
 * The surface is a slow-moving height field (three long swells). For each pixel the shader
 * takes the surface normal and asks how much light reaches it from the key light and from
 * each lamp: diffuse light by Lambert's cosine law with inverse-square falloff, glints by
 * a Blinn-Phong highlight. Colour is never interpolated across the screen; it is only ever
 * the result of that lighting sum.
 */
private const val LightFieldSource = """
uniform float2 uRes;
uniform float uPhase;
uniform float3 uCanvas;
uniform float3 uLift;
uniform float3 uShade;
uniform float3 uKey;
uniform float4 uLampA;
uniform float4 uLampB;
uniform float4 uLampC;
uniform float3 uColorA;
uniform float3 uColorB;
uniform float3 uColorC;
uniform float3 uSpecColor;
uniform float uSpecular;
uniform float uRelief;
uniform float uGrain;

// Light reaching surface point p (normal n) from a lamp hanging at L.xyz with intensity
// L.w. Returns (diffuse, specular).
float2 lamp(float4 L, float2 p, float aspect, float3 n) {
    float3 toLamp = float3(L.x - p.x, L.y * aspect - p.y, L.z);
    float d2 = dot(toLamp, toLamp);
    float3 l = toLamp * inversesqrt(d2);
    float falloff = (L.z * L.z) / d2;
    float diffuse = max(dot(n, l), 0.0) * falloff * L.w;
    float3 h = normalize(l + float3(0.0, 0.0, 1.0));
    float glint = pow(max(dot(n, h), 0.0), 20.0) * falloff * L.w * 0.5;
    return float2(diffuse, glint);
}

half4 main(float2 xy) {
    float aspect = uRes.y / uRes.x;
    float2 p = xy / uRes.x;
    float t = uPhase;

    // Height field: three swells crossing at different angles, bent slightly so their
    // crests are never straight. Only the slope is needed, and it has a closed form.
    float2 q = p + 0.07 * float2(sin(p.y * 3.3 + t * 4.0), cos(p.x * 2.9 - t * 3.0));
    float w1 = dot(q, float2(0.80, 0.60)) * 4.4 + t * 7.0;
    float w2 = dot(q, float2(-0.55, 0.83)) * 7.1 - t * 5.0 + 1.7;
    float w3 = dot(q, float2(0.98, -0.20)) * 12.0 + t * 9.0 + 4.1;
    float2 slope = 0.31 * cos(w1) * float2(0.80, 0.60)
                 + 0.26 * cos(w2) * float2(-0.55, 0.83)
                 + 0.13 * cos(w3) * float2(0.98, -0.20);
    float3 n = normalize(float3(-slope * uRelief, 1.0));

    // Key light: slopes that face it lift, slopes that turn away fall into shade.
    float facing = dot(n, uKey) - uKey.z;
    float3 col = uCanvas;
    col = mix(col, uLift, clamp(facing * 2.4, 0.0, 1.0));
    col = mix(col, uShade, clamp(-facing * 2.4, 0.0, 1.0));

    // Lamps: each one washes the surface with its colour where its light lands.
    float2 a = lamp(uLampA, p, aspect, n);
    float2 b = lamp(uLampB, p, aspect, n);
    float2 c = lamp(uLampC, p, aspect, n);
    col = mix(col, uColorA, clamp(a.x, 0.0, 1.0));
    col = mix(col, uColorB, clamp(b.x, 0.0, 1.0));
    col = mix(col, uColorC, clamp(c.x, 0.0, 1.0));

    // Glints where the surface mirrors a light back at the viewer.
    float3 hk = normalize(uKey + float3(0.0, 0.0, 1.0));
    float keyGlint = pow(max(dot(n, hk), 0.0), 40.0);
    col += uSpecColor * uSpecular * (keyGlint * 0.6 + a.y + b.y + c.y);

    float grain = fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    col += grain * uGrain;
    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""

/** Owns the compiled shader and feeds it the engine's state each frame. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LightFieldPainter {

    private val shader = RuntimeShader(LightFieldSource)
    private val paint = Paint().apply { shader = this@LightFieldPainter.shader }

    fun DrawScope.render(engine: LightingEngine, scheme: LightingScheme, vividness: Float) {
        shader.setFloatUniform("uRes", size.width, size.height)
        shader.setFloatUniform("uPhase", engine.phase)
        shader.setColor("uCanvas", scheme.canvas)
        shader.setColor("uLift", scheme.lift)
        shader.setColor("uShade", scheme.shade)
        shader.setFloatUniform("uKey", engine.keyX, engine.keyY, engine.keyZ)

        val lamps = engine.lamps
        val strength = scheme.lampStrength * vividness
        shader.setFloatUniform("uLampA", lamps[0], lamps[1], lamps[2], lamps[3] * strength)
        shader.setFloatUniform("uLampB", lamps[4], lamps[5], lamps[6], lamps[7] * strength)
        shader.setFloatUniform("uLampC", lamps[8], lamps[9], lamps[10], lamps[11] * strength)

        // The first lamp leans toward the playing track's colour.
        val own = scheme.lamps[0]
        val mix = engine.accentAmount * AccentShare
        shader.setFloatUniform(
            "uColorA",
            own.red + (engine.accentRed - own.red) * mix,
            own.green + (engine.accentGreen - own.green) * mix,
            own.blue + (engine.accentBlue - own.blue) * mix,
        )
        shader.setColor("uColorB", scheme.lamps[1])
        shader.setColor("uColorC", scheme.lamps[2])

        shader.setColor("uSpecColor", scheme.specular)
        shader.setFloatUniform("uSpecular", scheme.specularStrength * (1f + 0.5f * engine.energy))
        shader.setFloatUniform("uRelief", scheme.relief)
        shader.setFloatUniform("uGrain", scheme.grain)

        // Setting a uniform invalidates the shader's native instance; re-assigning it makes
        // the paint pick the new one up.
        paint.shader = shader
        drawIntoCanvas { it.nativeCanvas.drawPaint(paint) }
    }

    private fun RuntimeShader.setColor(name: String, color: Color) =
        setFloatUniform(name, color.red, color.green, color.blue)

    private companion object {
        /** How far the first lamp moves from the theme's colour to the track's. */
        const val AccentShare = 0.85f
    }
}
