package com.gtnewhorizons.angelica.loading;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.transform.BlockTransformer;
import com.gtnewhorizons.angelica.transform.RedirectorTransformer;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.CoreModManager;
import cpw.mods.fml.relauncher.FMLLaunchHandler;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.launch.platform.MixinContainer;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.transformer.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gtnewhorizons.angelica.loading.AngelicaTweaker.LOGGER;

public class MixinCompatHackTweaker implements ITweaker {
    public static final boolean DISABLE_OPTIFINE_FASTCRAFT_BETTERFPS = true;
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        verifyDependencies();

        if(DISABLE_OPTIFINE_FASTCRAFT_BETTERFPS) {
            LOGGER.info("Disabling Optifine, Fastcraft, BetterFPS, and other incompatible mods (if present)");
            disableIncompatibleMods();
        }

        if (AngelicaConfig.enableHudCaching){
            disableXaerosMinimapWaypointTransformer();
        }
    }

    private void verifyDependencies() {
        if(MixinCompatHackTweaker.class.getResource("/it/unimi/dsi/fastutil/ints/Int2ObjectMap.class") == null) {
            throw new RuntimeException("Missing dependency: Angelica requires GTNHLib 0.2.1 or newer! Download: https://modrinth.com/mod/gtnhlib");
        }
    }

    private void disableXaerosMinimapWaypointTransformer() {
        try {
            final LaunchClassLoader lcl = Launch.classLoader;
            final Field xformersField = lcl.getClass().getDeclaredField("transformers");
            xformersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<IClassTransformer> xformers = (List<IClassTransformer>) xformersField.get(lcl);

            // Use a temporary list to store transformers to remove
            List<IClassTransformer> toRemove = new ArrayList<>();
            for (IClassTransformer transformer : xformers) {
                final String name = transformer.getClass().getName();
                if (name.startsWith("xaero.common.core.transformer.GuiIngameForgeTransformer")) {
                    LOGGER.info("Marking transformer for removal: " + name);
                    toRemove.add(transformer);
                }
            }

            // Remove transformers outside of iteration
            xformers.removeAll(toRemove);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @SuppressWarnings("unchecked")
    private void disableIncompatibleMods() {
        // Remove transformers, Mod Containers, and mixins for Optifine, Fastcraft, BetterFPS and other incompatible mods
        try {
            final LaunchClassLoader lcl = Launch.classLoader;
            final Field xformersField = lcl.getClass().getDeclaredField("transformers");
            xformersField.setAccessible(true);
            final List<IClassTransformer> xformers = (List<IClassTransformer>) xformersField.get(lcl);
            xformers.removeIf(xformer -> xformer.getClass().getName().startsWith("optifine")
                || xformer.getClass().getName().startsWith("fastcraft")
                || xformer.getClass().getName().startsWith("me.guichaguri.betterfps"));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            final Field injectedContainersField = Loader.class.getDeclaredField("injectedContainers");
            injectedContainersField.setAccessible(true);
            final List<String> containers = (List<String>) injectedContainersField.get(Loader.class);
            containers.removeIf(container -> container.startsWith("optifine") || container.startsWith("fastcraft"));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            final Field reparsedCoremodsField = CoreModManager.class.getDeclaredField("reparsedCoremods");
            final Field loadedCoremodsField = CoreModManager.class.getDeclaredField("loadedCoremods");
            reparsedCoremodsField.setAccessible(true);
            loadedCoremodsField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            final ArrayList<String> mixinConfigsDefault = (ArrayList<String>) Launch.blackboard.get("mixin.configs.default");
            if (mixinConfigsDefault != null) {
                mixinConfigsDefault.removeIf(name -> name != null && (name.startsWith("optifine") || name.startsWith("fastcraft")));
            }
            final Set<Config> mixinConfigs = (Set<Config>) Launch.blackboard.get("mixin.configs.queue");
            if (mixinConfigs != null) {
                mixinConfigs.removeIf(config -> config.getName() != null && (config.getName().startsWith("optifine") || config.getName().startsWith("fastcraft")));
            }
            final MixinPlatformManager platformManager = (MixinPlatformManager) Launch.blackboard.get("mixin.platform");
            if (platformManager != null) {
                final Field containersField = platformManager.getClass().getDeclaredField("containers");
                containersField.setAccessible(true);
                final Map<IContainerHandle, MixinContainer> containers = (Map<IContainerHandle, MixinContainer>) containersField.get(platformManager);
                containers.entrySet().removeIf(entry -> {
                    final String attribute = entry.getKey().getAttribute("MixinConfigs");
                    return attribute != null && (attribute.startsWith("optifine") || attribute.startsWith("fastcraft"));
                });
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        // no-op
    }

    @Override
    public String getLaunchTarget() {
        return null;
    }

    @Override
    public String[] getLaunchArguments() {
        if (FMLLaunchHandler.side().isClient()) {
            final boolean rfbLoaded = Launch.blackboard.getOrDefault("angelica.rfbPluginLoaded", Boolean.FALSE) == Boolean.TRUE;

            if (!rfbLoaded) {
                // Run after Mixins, but before LWJGl3ify
                Launch.classLoader.registerTransformer(RedirectorTransformer.class.getName());
            }
            if(AngelicaConfig.enableSodium) {
                Launch.classLoader.registerTransformer(BlockTransformer.class.getName());
            }
        }

        return new String[0];
    }
}
