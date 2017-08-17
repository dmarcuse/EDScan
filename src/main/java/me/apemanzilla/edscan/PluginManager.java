package me.apemanzilla.edscan;

import java.security.CodeSource;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class PluginManager {
	private final EDScan edscan;

	public static PluginManager loadPlugins(EDScan inst, ServiceLoader<Plugin> loader) {
		Set<Plugin> plugins = ConcurrentHashMap.newKeySet();

		loader.forEach(p -> {
			log.info("Loaded plugin [{}] from {}", p,
					p.getSource().map(CodeSource::toString).orElse("(unknown location)"));

			p.edscan = inst;

			plugins.add(p);
		});

		return new PluginManager(inst, plugins);
	}

	private final Set<Plugin> plugins;

	public void init() {
		plugins.forEach(p -> {
			try {
				p.init();
			} catch (Exception e) {
				log.error("Error initializing plugin {}:", p, e);

				edscan.showErrorMessage("Plugin error", "There was an error initializing plugin " + p, e);
			}
		});
	}

	public void cleanup() {
		plugins.forEach(p -> {
			try {
				p.cleanup();
			} catch (Exception e) {
				log.error("Error cleaning up plugin {}:", p, e);

				edscan.showErrorMessage("Plugin error", "There was an error cleaning up plugin " + p, e);
			}
		});
	}
}
