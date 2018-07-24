package bungeepluginmanager;

import com.google.common.collect.Multimap;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Level;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginClassloader;
import net.md_5.bungee.api.plugin.PluginDescription;
import net.md_5.bungee.api.plugin.PluginManager;
import org.yaml.snakeyaml.Yaml;

public final class PluginUtils {

    @SuppressWarnings("deprecation")
    public static void unloadPlugin(Plugin plugin) {

        PluginManager pluginManager = ProxyServer.getInstance().getPluginManager();
        ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();

        try {
            //call onDisable
            plugin.onDisable();
            //close all log handlers
            for (Handler handler : plugin.getLogger().getHandlers()) {
                handler.close();
            }
        } catch (Throwable t) {
            severe("Exception disabling plugin", t, plugin.getDescription().getName());
        }

        //unregister event handlers
        pluginManager.unregisterListeners(plugin);
        //unregister commands
        pluginManager.unregisterCommands(plugin);
        //cancel tasks
        ProxyServer.getInstance().getScheduler().cancel(plugin);
        //shutdown internal executor
        plugin.getExecutorService().shutdownNow();
        //stop all still active threads that belong to a plugin
        Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> (thread.getClass().getClassLoader() == pluginClassLoader))
                .forEach(thread -> {
                    try {
                        thread.interrupt();
                        thread.join(2000);
                        if (thread.isAlive()) {
                            thread.stop();
                        }
                    } catch (Throwable t) {
                        severe("Failed to stop thread that belong to plugin", t, plugin.getDescription().getName());
                    }
                });

        //finish uncompleted intents
        ModifiedPluginEventBus.completeIntents(plugin);
        //remove commands that were registered by plugin not through normal means
        try {
            Map<String, Command> commandMap = ReflectionUtils.getFieldValue(pluginManager, "commandMap");
            commandMap.entrySet().removeIf(entry -> entry.getValue().getClass().getClassLoader() == pluginClassLoader);
        } catch (Throwable t) {
            severe("Failed to cleanup commandMap", t, plugin.getDescription().getName());
        }
        //cleanup internal listener and command maps from plugin refs
        try {
            Map<String, Plugin> pluginsMap = ReflectionUtils.getFieldValue(pluginManager, "plugins");
            pluginsMap.values().remove(plugin);
            Multimap<Plugin, Command> commands = ReflectionUtils.getFieldValue(pluginManager, "commandsByPlugin");
            commands.removeAll(plugin);
            Multimap<Plugin, Listener> listeners = ReflectionUtils.getFieldValue(pluginManager, "listenersByPlugin");
            listeners.removeAll(plugin);
        } catch (Throwable t) {
            severe("Failed to cleanup bungee internal maps from plugin refs", t, plugin.getDescription().getName());
        }
        //close classloader
        if (pluginClassLoader instanceof URLClassLoader) {
            try {
                ((URLClassLoader) pluginClassLoader).close();
            } catch (Throwable t) {
                severe("Failed to close the classloader for plugin", t, plugin.getDescription().getName());
            }
        }
        //remove classloader
        Set<PluginClassloader> allLoaders = ReflectionUtils.getStaticFieldValue(PluginClassloader.class, "allLoaders");
        allLoaders.remove(pluginClassLoader);

    }

    @SuppressWarnings("resource")
    public static boolean loadPlugin(File pluginfile) {

        try (JarFile jar = new JarFile(pluginfile)) {

            JarEntry pdf = jar.getJarEntry("bungee.yml");

            if (pdf == null) {
                pdf = jar.getJarEntry("plugin.yml");
            }

            try (InputStream in = jar.getInputStream(pdf)) {
                //load description
                PluginDescription desc = new Yaml().loadAs(in, PluginDescription.class);
                desc.setFile(pluginfile);
                //check depends
                HashSet<String> plugins = new HashSet<>();
                ProxyServer.getInstance().getPluginManager().getPlugins().forEach(plugin -> plugins.add(plugin.getDescription().getName()));
                for (String dependency : desc.getDepends()) {
                    if (!plugins.contains(dependency)) {
                        ProxyServer.getInstance().getLogger().log(Level.WARNING, "{0} (required by {1}) is unavailable", new Object[]{dependency, desc.getName()});
                        return false;
                    }
                }

                // do actual loading
                URLClassLoader loader = new PluginClassloader( new URL[] {
                        pluginfile.toURI().toURL()
                });
                Class<?> main = loader.loadClass(desc.getMain());
                Plugin clazz = (Plugin) main.getDeclaredConstructor().newInstance();

                // reflection
                Map<String, Plugin> pluginsMap = ReflectionUtils.getFieldValue(ProxyServer.getInstance().getPluginManager(), "plugins");
                ReflectionUtils.invokeMethod(clazz, "init", ProxyServer.getInstance(), desc);

                pluginsMap.put(desc.getName(), clazz);
                clazz.onLoad();
                clazz.onEnable();
                return true;
            }
        } catch (Throwable t) {
            severe("Failed to load plugin", t, pluginfile.getName());
            return false;
        }

    }

    private static void severe(String message, Throwable t, String pluginname) {
        ProxyServer.getInstance().getLogger().log(Level.SEVERE, message + " " + pluginname, t);
    }

}
