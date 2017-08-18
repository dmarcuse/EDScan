package me.apemanzilla.edscan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import javafx.beans.property.Property;
import javafx.beans.value.ObservableValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;

public class Config {
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	public static Config load(Path file) throws JsonIOException, JsonSyntaxException, IOException {
		return new Config(
				gson.fromJson(Files.newBufferedReader(file), new TypeToken<Map<String, JsonElement>>() {}.getType()));
	}

	/**
	 * The underlying map of this config. Changes made to this map will propagate to
	 * the config, but should be avoided since listeners will not be fired.
	 */
	@Getter(AccessLevel.PACKAGE)
	private final ConcurrentHashMap<String, JsonElement> map;

	/**
	 * Creates a new config using the mappings from the given map.
	 */
	public Config(Map<String, JsonElement> cfg) {
		map = new ConcurrentHashMap<>(cfg);
	}

	/**
	 * Creates a new, empty config.
	 */
	public Config() {
		this(Collections.emptyMap());
	}

	/**
	 * @return Whether the given key is present in the map
	 */
	public boolean hasKey(String key) {
		return map.containsKey(key);
	}

	/**
	 * Adds a key-value pair to the map
	 */
	public <T> void put(String key, T value) {
		map.put(key, gson.toJsonTree(value));
	}

	/**
	 * Removes a given key from the map
	 */
	public void remove(String key) {
		map.remove(key);
	}

	/**
	 * @return An <code>Optional</code>, empty if the given key is not present, or
	 *         containing the value mapped to the given key.
	 */
	public Optional<JsonElement> get(String key) {
		return Optional.ofNullable(map.get(key));
	}

	/**
	 * Gets a value from the map and casts it to the given class. If the value
	 * cannot be cast or no value is present, an empty <code>Optional</code> is
	 * returned.
	 */
	public <T> Optional<T> getAs(Class<T> cls, String key) {
		if (!map.containsKey(key)) return Optional.empty();
		return get(key).map(e -> gson.fromJson(e, cls));
	}

	/**
	 * Gets a value from the map and casts it to the given class. If the value
	 * cannot be cast or no value is present, the given default value is returned
	 * instead.
	 */
	public <T> T getAsOr(Class<T> cls, String key, T defaultValue) {
		return getAs(cls, key).orElse(defaultValue);
	}

	/**
	 * Binds the given key in this config to the given value unidirectionally, such
	 * that when the value changes, the config is updated to match.
	 * 
	 * @see #bindAndSet(String, Property)
	 * @see #bindAndSet(String, Property, Object)
	 */
	public <T> void bind(String key, ObservableValue<T> binding) {
		binding.addListener((s, o, n) -> put(key, n));
	}

	/**
	 * Sets the given property to the existing value in this config if present, then
	 * binds it such that when the property changes, the config is updated to
	 * match.<br>
	 * 
	 * @see #bind(String, ObservableValue)
	 * @see #bindAndSet(String, Property, Object)
	 */
	@SuppressWarnings("unchecked")
	public <T> void bindAndSet(String key, Property<T> binding) {
		assert binding.getValue() != null : "Cannot determine binding type parameter";

		getAs((Class<T>) binding.getValue().getClass(), key).ifPresent(binding::setValue);
		binding.addListener((s, o, n) -> put(key, n));
	}

	/**
	 * Sets the given property to the existing value in this config, or the given
	 * default value, and then binds it such that when the property value changes,
	 * the config is updated to match.<br>
	 * <br>
	 * When storing the new value in the config, the value will be deleted if the
	 * new value is equal to the default value.
	 * 
	 * @see #bind(String, ObservableValue)
	 * @see #bindAndSet(String, Property)
	 */
	@SuppressWarnings("unchecked")
	public <T> void bindAndSet(String key, Property<T> binding, @NonNull T defaultValue) {
		binding.setValue(getAsOr((Class<T>) defaultValue.getClass(), key, defaultValue));
		binding.addListener((s, o, n) -> {
			if (defaultValue.equals(n))
				remove(key);
			else
				put(key, n);
		});
	}

	public void save(Path path) throws IOException {
		Files.write(path, gson.toJson(map).getBytes());
	}
}
