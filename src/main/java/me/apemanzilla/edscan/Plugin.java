package me.apemanzilla.edscan;

import java.security.CodeSource;
import java.util.Optional;
import java.util.concurrent.Callable;

import javafx.scene.Node;

public abstract class Plugin {
	protected EDScan edscan;

	/**
	 * @return The human-readable name of this plugin
	 */
	public abstract String getName();

	/**
	 * @return The human-readable description of this plugin
	 */
	public abstract String getDescription();

	/**
	 * @return A human-readable version string for this plugin, or an empty optional
	 *         (default)
	 */
	public Optional<String> getVersion() {
		return Optional.empty();
	}

	/**
	 * Allows the plugin to perform initialization tasks
	 */
	public void init() throws Exception {

	}

	/**
	 * Gets the view builder for this plugin. This will be invoked after
	 * {@link #init()}.<br>
	 * The default implementation returns an empty optional.
	 */
	public Optional<Callable<Node>> getViewBuilder() {
		return Optional.empty();
	}

	/**
	 * Allows the plugin to perform cleanup tasks
	 */
	public void cleanup() throws Exception {

	}

	/**
	 * @return The {@link CodeSource} of this plugin, or an empty optional.
	 */
	public final Optional<CodeSource> getSource() {
		return Optional.ofNullable(getClass().getProtectionDomain().getCodeSource());
	}

	/**
	 * Gets the name and version (if present) of this plugin.<br>
	 * Examples:<br>
	 * <code>MyPlugin 1.2.3</code><br>
	 * <code>MyPlugin</code>
	 */
	public final String toString() {
		return getName() + getVersion().map(v -> " " + v).orElse("");
	}
}
