package me.apemanzilla.edscan;

import java.security.CodeSource;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class PluginManager {
	private final EDScan edscan;

	public static PluginManager loadPlugins(EDScan edscan, ServiceLoader<Plugin> loader) {
		Set<Plugin> plugins = ConcurrentHashMap.newKeySet();

		loader.forEach(p -> {
			log.info("Loaded plugin [{}] from {}", p,
					p.getSource().map(CodeSource::toString).orElse("(unknown location)"));

			p.edscan = edscan;

			plugins.add(p);
		});

		return new PluginManager(edscan, plugins);
	}

	private final Set<Plugin> plugins;

	boolean isEnabled(Plugin p) {
		String key = "plugins." + p.getClass().getName() + ".enabled";
		return edscan.getConfig().getAsOr(Boolean.class, key, true);
	}

	Stream<Plugin> enabledPlugins() {
		return plugins.stream().filter(this::isEnabled);
	}

	public void init() {
		enabledPlugins().forEach(p -> {
			try {
				p.init();
			} catch (Exception e) {
				log.error("Error calling init for [{}]", p, e);
				edscan.showErrorMessage("Plugin initialization error", "There was an error initializing plugin " + p,
						e);
			}
		});
	}

	public void addViews() {
		enabledPlugins().filter(p -> p.getViewBuilder().isPresent()).forEach(p -> {
			try {
				edscan.addView(p.toString(), p.getViewBuilder().get().call());
			} catch (Exception e) {
				log.error("Error creating view for [{}]", p, e);
				edscan.showErrorMessage("Plugin view error", "There was an error creating the view for plugin " + p, e);
			}
		});
	}

	public void cleanup() {
		enabledPlugins().forEach(p -> {
			try {
				p.cleanup();
			} catch (Exception e) {
				log.error("Error calling cleanup for [{}]", p, e);
				edscan.showErrorMessage("Plugin cleanup error", "There was an error cleaning up plugin " + p, e);
			}
		});
	}
}
