package me.ajh123.be_quiet_negotiator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Replacement for {@link ConfigTracker#loadDefaultServerConfigs()} that isolates each config.
 * <p>
 * When a client joins a vanilla server, NeoForge loads every mod's default SERVER config on the client and fires
 * {@link ModConfigEvent.Loading} for each one. Some mods react to that event with server-only logic (for example
 * sending payloads to all players), which throws on the client. FML collects those failures into a
 * {@code ModLoadingException} that aborts the whole loop (leaving later configs unloaded) and disconnects the client.
 * <p>
 * Here every config is loaded on its own: the config values are applied before the event is fired, so a failing
 * listener only gets logged and every other mod still receives its default server config.
 */
public final class SafeServerConfigLoader {
    private SafeServerConfigLoader() {
    }

    public static void loadDefaultServerConfigs() {
        Reflection reflection;
        try {
            reflection = Reflection.get();
        } catch (ReflectiveOperationException | RuntimeException e) {
            BeQuietNegotiator.LOGGER.warn("Could not access FML config internals, falling back to NeoForge's default server config loading", e);
            ConfigTracker.INSTANCE.loadDefaultServerConfigs();
            return;
        }

        for (ModConfig config : reflection.serverConfigs()) {
            try {
                if (config.getLoadedConfig() != null) {
                    BeQuietNegotiator.LOGGER.debug("Overwriting loaded config {} with default server config", config.getFileName());
                }
                Object defaultConfig = reflection.createDefaultConfig.invoke(null, config.getSpec());
                Object loadedConfig = reflection.loadedConfigConstructor.newInstance(defaultConfig, null, config);
                Function<ModConfig, ModConfigEvent> event = ModConfigEvent.Loading::new;
                reflection.setConfig.invoke(config, loadedConfig, event);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
                BeQuietNegotiator.LOGGER.error(
                        "Mod {} failed while loading its default server config {} on the client; ignoring so the connection can continue",
                        config.getModId(), config.getFileName(), cause);
            }
        }
    }

    private record Reflection(Field configSets, Method createDefaultConfig, Constructor<?> loadedConfigConstructor, Method setConfig) {
        private static Reflection instance;

        static synchronized Reflection get() throws ReflectiveOperationException {
            if (instance == null) {
                Field configSets = ConfigTracker.class.getDeclaredField("configSets");
                configSets.setAccessible(true);

                Method createDefaultConfig = ConfigTracker.class.getDeclaredMethod("createDefaultConfig", IConfigSpec.class);
                createDefaultConfig.setAccessible(true);

                Class<?> loadedConfigClass = Class.forName("net.neoforged.fml.config.LoadedConfig", false, ConfigTracker.class.getClassLoader());
                Class<?> commentedConfigClass = createDefaultConfig.getReturnType();
                Constructor<?> loadedConfigConstructor = loadedConfigClass.getDeclaredConstructor(commentedConfigClass, Path.class, ModConfig.class);
                loadedConfigConstructor.setAccessible(true);

                Method setConfig = ModConfig.class.getDeclaredMethod("setConfig", loadedConfigClass, Function.class);
                setConfig.setAccessible(true);

                instance = new Reflection(configSets, createDefaultConfig, loadedConfigConstructor, setConfig);
            }
            return instance;
        }

        @SuppressWarnings("unchecked")
        List<ModConfig> serverConfigs() {
            try {
                EnumMap<ModConfig.Type, Set<ModConfig>> sets = (EnumMap<ModConfig.Type, Set<ModConfig>>) configSets.get(ConfigTracker.INSTANCE);
                Set<ModConfig> server = sets.get(ModConfig.Type.SERVER);
                // Copy under the set's lock (it is a synchronized set) so we don't iterate while it changes.
                synchronized (server) {
                    return new ArrayList<>(server);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
