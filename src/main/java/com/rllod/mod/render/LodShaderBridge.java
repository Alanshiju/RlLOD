package com.rllod.mod.render;

import com.rllod.mod.RlLodMod;
import com.rllod.mod.config.RlLodConfig;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = RlLodMod.MODID, value = Side.CLIENT)
public final class LodShaderBridge {

    public enum ShaderPassType {
        NONE,
        OPTIFINE,
        BUILT_IN_GLSL
    }

    private static boolean checkedOptifine = false;
    private static Field isShaderPackInitializedField = null;
    private static Method useProgramMethod = null;
    private static Method pushProgramMethod = null;
    private static Method popProgramMethod = null;
    private static Method setShaderPackMethod = null;
    private static Method getShaderPackNameMethod = null;
    private static Method uninitMethod = null;
    private static Method storeConfigMethod = null;
    private static Object programTerrainInstance = null;
    private static Object programWaterInstance = null;
    private static Method preWaterMethod = null;
    private static Object uniformFarInstance = null;
    private static Method uniformFarSetValue = null;

    // Built-in GLSL Atmospheric & Directional Lighting Shader
    private static boolean glslInitialized = false;
    private static int glslProgramId = 0;
    private static int uFogColorLoc = -1;
    private static int uFogStartLoc = -1;
    private static int uFogEndLoc = -1;
    private static int uSunBrightnessLoc = -1;
    private static int uLightDirLoc = -1;

    private static final String VERTEX_SHADER_SRC =
            "#version 120\n" +
            "varying vec4 vColor;\n" +
            "varying float vEyeDist;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec2 vLight;\n" +
            "varying vec3 vWorldPos;\n" +
            "void main() {\n" +
            "    vec4 eyePos = gl_ModelViewMatrix * gl_Vertex;\n" +
            "    vEyeDist = length(eyePos.xyz);\n" +
            "    gl_Position = gl_ProjectionMatrix * eyePos;\n" +
            "    vColor = gl_Color;\n" +
            "    vNormal = normalize(gl_NormalMatrix * gl_Normal);\n" +
            "    vLight = gl_MultiTexCoord1.xy / 256.0;\n" +
            "    vWorldPos = gl_Vertex.xyz;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_SRC =
            "#version 120\n" +
            "varying vec4 vColor;\n" +
            "varying float vEyeDist;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec2 vLight;\n" +
            "varying vec3 vWorldPos;\n" +
            "uniform vec3 uFogColor;\n" +
            "uniform float uFogStart;\n" +
            "uniform float uFogEnd;\n" +
            "uniform float uSunBrightness;\n" +
            "uniform vec3 uLightDir;\n" +
            "void main() {\n" +
            "    // 1. Crisp Directional Sunlight + Hemisphere Sky Ambient Lighting\n" +
            "    float NdotL = max(0.0, dot(vNormal, normalize(uLightDir)));\n" +
            "    float sunDiffuse = mix(0.55, 1.05, NdotL);\n" +
            "    float skyUp = max(0.0, vNormal.y);\n" +
            "    float skyAmbient = mix(0.45, 0.90, skyUp);\n" +
            "    // 2. Sky & Block Lightmap contribution\n" +
            "    float ambientLight = clamp(0.25 + (vLight.y * uSunBrightness * 0.75) + (vLight.x * 0.85), 0.15, 1.0);\n" +
            "    // 3. Warm sunlight / cool moonlight atmospheric tinting\n" +
            "    vec3 warmSun = vec3(1.05, 1.02, 0.96);\n" +
            "    vec3 coolNight = vec3(0.60, 0.68, 0.88);\n" +
            "    vec3 atmoTint = mix(coolNight, warmSun, clamp(uSunBrightness, 0.0, 1.0));\n" +
            "    // 4. Subtle micro-grid block bevel definition on top faces so block steps pop with 3D depth\n" +
            "    float blockBevel = 1.0;\n" +
            "    if (vNormal.y > 0.5) {\n" +
            "        vec2 grid = abs(fract(vWorldPos.xz) - 0.5);\n" +
            "        float edge = max(grid.x, grid.y);\n" +
            "        blockBevel = 0.96 + 0.04 * smoothstep(0.40, 0.48, 0.5 - edge);\n" +
            "    }\n" +
            "    vec3 litColor = vColor.rgb * sunDiffuse * skyAmbient * ambientLight * atmoTint * blockBevel;\n" +
            "    // 5. Smooth exponential-power distance fog using real Minecraft fog color\n" +
            "    float linearFog = clamp((vEyeDist - uFogStart) / max(1.0, (uFogEnd - uFogStart)), 0.0, 1.0);\n" +
            "    float fogFactor = pow(linearFog, 1.45);\n" +
            "    vec3 finalRgb = mix(litColor, uFogColor, fogFactor);\n" +
            "    gl_FragColor = vec4(finalRgb, vColor.a);\n" +
            "}\n";

    private LodShaderBridge() {}

    private static void checkOptifine() {
        if (checkedOptifine) {
            return;
        }
        checkedOptifine = true;
        try {
            Class<?> shadersClass = Class.forName("net.optifine.shaders.Shaders");
            isShaderPackInitializedField = shadersClass.getDeclaredField("isShaderPackInitialized");
            isShaderPackInitializedField.setAccessible(true);

            Class<?> programClass = Class.forName("net.optifine.shaders.Program");
            useProgramMethod = shadersClass.getMethod("useProgram", programClass);
            pushProgramMethod = shadersClass.getMethod("pushProgram");
            popProgramMethod = shadersClass.getMethod("popProgram");

            setShaderPackMethod = shadersClass.getMethod("setShaderPack", String.class);
            getShaderPackNameMethod = shadersClass.getMethod("getShaderPackName");
            uninitMethod = shadersClass.getMethod("uninit");
            storeConfigMethod = shadersClass.getMethod("storeConfig");

            Field programTerrainField = shadersClass.getDeclaredField("ProgramTerrain");
            programTerrainField.setAccessible(true);
            programTerrainInstance = programTerrainField.get(null);

            Field programWaterField = shadersClass.getDeclaredField("ProgramWater");
            programWaterField.setAccessible(true);
            programWaterInstance = programWaterField.get(null);

            try {
                preWaterMethod = shadersClass.getMethod("preWater");
                preWaterMethod.setAccessible(true);
            } catch (Throwable ignored) {
            }

            Field uniformFarField = shadersClass.getDeclaredField("uniform_far");
            uniformFarField.setAccessible(true);
            uniformFarInstance = uniformFarField.get(null);
            if (uniformFarInstance != null) {
                uniformFarSetValue = uniformFarInstance.getClass().getMethod("setValue", float.class);
            }
            RlLodMod.LOGGER.info("[RlLOD] Successfully linked OptiFine Shaders G-Buffer reflection bridge (Terrain + Water).");
        } catch (Throwable t) {
            RlLodMod.LOGGER.debug("[RlLOD] OptiFine Shaders classes not present or reflection skipped: {}", t.getMessage());
        }
    }

    public static boolean isOptifineAvailable() {
        checkOptifine();
        return useProgramMethod != null;
    }

    public static boolean isOptifineShadersActive() {
        checkOptifine();
        if (isShaderPackInitializedField != null) {
            try {
                return isShaderPackInitializedField.getBoolean(null);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static void pushOptifineProgram() {
        if (pushProgramMethod != null) {
            try {
                pushProgramMethod.invoke(null);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void popOptifineProgram() {
        if (popProgramMethod != null) {
            try {
                popProgramMethod.invoke(null);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void updateOptifineFarPlane() {
        if (RlLodConfig.overrideOptifineShaderFarPlane && uniformFarInstance != null && uniformFarSetValue != null) {
            try {
                float lodFar = RlLodConfig.lodRenderDistanceChunks * 16.0f;
                uniformFarSetValue.invoke(uniformFarInstance, lodFar);
            } catch (Throwable ignored) {
            }
        }
    }

    @SubscribeEvent
    public static void onRenderFog(EntityViewRenderEvent.RenderFogEvent event) {
        updateOptifineFarPlane();
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            updateOptifineFarPlane();
        }
    }

    public static void beginOptifineSolidPass(float farPlane) {
        beginOptifineSolidPass(farPlane, null, null);
    }

    public static void beginOptifineSolidPass(float farPlane, FloatBuffer extProj, FloatBuffer extProjInv) {
        if (useProgramMethod != null && programTerrainInstance != null) {
            try {
                useProgramMethod.invoke(null, programTerrainInstance);
                if (RlLodConfig.overrideOptifineShaderFarPlane && uniformFarInstance != null && uniformFarSetValue != null) {
                    uniformFarSetValue.invoke(uniformFarInstance, farPlane);
                }
                int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                if (prog > 0) {
                    applyProjectionUniforms(prog, farPlane, extProj, extProjInv);
                    int mcEntityLoc = GL20.glGetAttribLocation(prog, "mc_Entity");
                    if (mcEntityLoc >= 0) {
                        GL20.glDisableVertexAttribArray(mcEntityLoc);
                        GL20.glVertexAttrib4f(mcEntityLoc, -1.0f, 0.0f, 0.0f, 0.0f);
                    }
                    int tangentLoc = GL20.glGetAttribLocation(prog, "at_tangent");
                    if (tangentLoc >= 0) {
                        GL20.glDisableVertexAttribArray(tangentLoc);
                        GL20.glVertexAttrib4f(tangentLoc, 0.0f, 0.0f, 0.0f, 0.0f);
                    }
                }
            } catch (Throwable t) {
                RlLodMod.LOGGER.debug("[RlLOD] Failed invoking OptiFine ProgramTerrain: {}", t.getMessage());
            }
        }
    }

    public static void beginOptifineWaterPass(float farPlane) {
        beginOptifineWaterPass(farPlane, null, null);
    }

    public static void beginOptifineWaterPass(float farPlane, FloatBuffer extProj, FloatBuffer extProjInv) {
        if (useProgramMethod != null && programWaterInstance != null) {
            try {
                useProgramMethod.invoke(null, programWaterInstance);
                if (RlLodConfig.overrideOptifineShaderFarPlane && uniformFarInstance != null && uniformFarSetValue != null) {
                    uniformFarSetValue.invoke(uniformFarInstance, farPlane);
                }
                int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                if (prog > 0) {
                    applyProjectionUniforms(prog, farPlane, extProj, extProjInv);
                    int mcEntityLoc = GL20.glGetAttribLocation(prog, "mc_Entity");
                    if (mcEntityLoc >= 0) {
                        GL20.glDisableVertexAttribArray(mcEntityLoc);
                        GL20.glVertexAttrib4f(mcEntityLoc, 10008.0f, 0.0f, 0.0f, 0.0f);
                    }
                    int tangentLoc = GL20.glGetAttribLocation(prog, "at_tangent");
                    if (tangentLoc >= 0) {
                        GL20.glDisableVertexAttribArray(tangentLoc);
                        GL20.glVertexAttrib4f(tangentLoc, 1.0f, 0.0f, 0.0f, 1.0f);
                    }
                }
            } catch (Throwable t) {
                RlLodMod.LOGGER.debug("[RlLOD] Failed invoking OptiFine ProgramWater: {}", t.getMessage());
            }
        }
    }

    private static void applyProjectionUniforms(int prog, float farPlane, FloatBuffer extProj, FloatBuffer extProjInv) {
        if (extProj != null) {
            int projLoc = GL20.glGetUniformLocation(prog, "gbufferProjection");
            if (projLoc >= 0) {
                extProj.rewind();
                GL20.glUniformMatrix4(projLoc, false, extProj);
            }
            int dhProjLoc = GL20.glGetUniformLocation(prog, "dhProjection");
            if (dhProjLoc >= 0) {
                extProj.rewind();
                GL20.glUniformMatrix4(dhProjLoc, false, extProj);
            }
        }
        if (extProjInv != null) {
            int invLoc = GL20.glGetUniformLocation(prog, "gbufferProjectionInverse");
            if (invLoc >= 0) {
                extProjInv.rewind();
                GL20.glUniformMatrix4(invLoc, false, extProjInv);
            }
        }
        int dhNearLoc = GL20.glGetUniformLocation(prog, "dhNearPlane");
        if (dhNearLoc >= 0) {
            GL20.glUniform1f(dhNearLoc, 0.05f);
        }
        int dhFarLoc = GL20.glGetUniformLocation(prog, "dhFarPlane");
        if (dhFarLoc >= 0) {
            GL20.glUniform1f(dhFarLoc, farPlane);
        }
    }

    public static void copyPreWaterDepth() {
        preWater();
    }

    public static void preWater() {
        if (preWaterMethod != null) {
            try {
                preWaterMethod.invoke(null);
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean isMakeUpShaderActive() {
        checkOptifine();
        if (getShaderPackNameMethod != null) {
            try {
                String name = (String) getShaderPackNameMethod.invoke(null);
                return name != null && name.toLowerCase().contains("makeup");
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static void toggleFullMakeUpShader(boolean enable) {
        checkOptifine();
        if (setShaderPackMethod != null) {
            try {
                String packName = enable ? "MakeUp-UltraFast-9.5.zip" : "OFF";
                setShaderPackMethod.invoke(null, packName);
                if (storeConfigMethod != null) {
                    storeConfigMethod.invoke(null);
                }
                if (uninitMethod != null) {
                    uninitMethod.invoke(null);
                }
                net.minecraft.client.Minecraft.getMinecraft().renderGlobal.loadRenderers();
                RlLodMod.LOGGER.info("[RlLOD] Successfully set shader pack to: {}", packName);
            } catch (Throwable t) {
                RlLodMod.LOGGER.warn("[RlLOD] Failed toggling MakeUp-UltraFast shader pack", t);
            }
        }
    }

    /**
     * Fallback shader pass for built-in atmospheric GLSL shader when OptiFine shaders are inactive.
     */
    public static ShaderPassType beginLodShaderPass(
            float fogStart,
            float fogEnd,
            float sunBrightness,
            float celestialAngle,
            Vec3d fogColor
    ) {
        if (RlLodConfig.shaderMode == 2 || (!RlLodConfig.enableBuiltInShader && RlLodConfig.shaderMode != 1)) {
            return ShaderPassType.NONE;
        }

        if (!glslInitialized) {
            glslInitialized = true;
            initBuiltInGlslProgram();
        }

        if (glslProgramId > 0) {
            GL20.glUseProgram(glslProgramId);
            if (fogColor != null) {
                GL20.glUniform3f(uFogColorLoc, (float) fogColor.x, (float) fogColor.y, (float) fogColor.z);
            }
            GL20.glUniform1f(uFogStartLoc, fogStart);
            GL20.glUniform1f(uFogEndLoc, fogEnd);
            GL20.glUniform1f(uSunBrightnessLoc, sunBrightness);

            // Compute celestial sun/moon directional light vector
            double angleRad = celestialAngle * 2.0 * Math.PI;
            float sunY = (float) Math.cos(angleRad);
            float sunZ = (float) Math.sin(angleRad);
            float sunX = 0.35f;
            GL20.glUniform3f(uLightDirLoc, sunX, (sunY >= -0.1f) ? sunY : -sunY, (sunY >= -0.1f) ? sunZ : -sunZ);

            return ShaderPassType.BUILT_IN_GLSL;
        }

        return ShaderPassType.NONE;
    }

    public static void endLodShaderPass(ShaderPassType passType) {
        if (passType == ShaderPassType.BUILT_IN_GLSL) {
            GL20.glUseProgram(0);
        }
    }

    private static void initBuiltInGlslProgram() {
        try {
            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, VERTEX_SHADER_SRC);
            GL20.glCompileShader(vs);
            if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                RlLodMod.LOGGER.warn("[RlLOD] Built-in VS compile error: {}", GL20.glGetShaderInfoLog(vs, 512));
                return;
            }

            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, FRAGMENT_SHADER_SRC);
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                RlLodMod.LOGGER.warn("[RlLOD] Built-in FS compile error: {}", GL20.glGetShaderInfoLog(fs, 512));
                return;
            }

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                RlLodMod.LOGGER.warn("[RlLOD] Built-in Program link error: {}", GL20.glGetProgramInfoLog(prog, 512));
                return;
            }

            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            glslProgramId = prog;
            uFogColorLoc = GL20.glGetUniformLocation(prog, "uFogColor");
            uFogStartLoc = GL20.glGetUniformLocation(prog, "uFogStart");
            uFogEndLoc = GL20.glGetUniformLocation(prog, "uFogEnd");
            uSunBrightnessLoc = GL20.glGetUniformLocation(prog, "uSunBrightness");
            uLightDirLoc = GL20.glGetUniformLocation(prog, "uLightDir");
            RlLodMod.LOGGER.info("[RlLOD] Initialized upgraded GLSL atmospheric directional shader with 3D micro-bevels (program={}).", prog);
        } catch (Throwable t) {
            RlLodMod.LOGGER.warn("[RlLOD] Failed to initialize built-in GLSL shader", t);
        }
    }
}
