package me.apemanzilla.edscan;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.Property;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import me.apemanzilla.edjournal.Journal;
import me.apemanzilla.edjournal.events.JournalEvent;

@Slf4j
public class EDScan extends Application {
	public static Optional<String> getVersion() {
		return Optional.ofNullable(EDScan.class.getPackage().getImplementationVersion());
	}

	private static Font loadFont(String name) {
		return Font.loadFont(EDScan.class.getResourceAsStream("fonts/" + name), 12);
	}

	private static void loadFonts() {
		loadFont("eurocaps.ttf");
		loadFont("sintony.ttf");
		loadFont("telegrama.ttf");
	}

	public static void main(String[] args) {
		log.info("Launching EDScan {}", getVersion().orElse(""));
		log.info("Arguments: {}", Arrays.toString(args));
		launch(args);
	}

	@Getter
	private Journal journal;

	private PluginManager pluginManager;

	private Multimap<Class<?>, Consumer<? extends JournalEvent>> listeners = MultimapBuilder.hashKeys().hashSetValues()
			.build();

	private VBox viewPane;

	private Gson gson = new GsonBuilder().setPrettyPrinting().create();

	public class Config {
		/**
		 * The underlying map of this config. Changes made to this map will propagate to
		 * the config.
		 */
		@Getter
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
		 * @see #bindAndSet(String, Property, Object)
		 */
		public <T> void bind(String key, ObservableValue<T> binding) {
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
	}

	@Getter
	private Config config;

	public Path getDataDirectory() {
		String os = System.getProperty("os.name").toLowerCase();
		if (os.startsWith("win")) {
			return Paths.get(System.getenv("APPDATA"), "EDScan");
		} else if (os.startsWith("mac")) {
			return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "EDScan");
		} else {
			return Paths.get(System.getProperty("user.home"), "EDScan");
		}
	}

	public Path getConfigFile() {
		return getDataDirectory().resolve("config.json");
	}

	public <T extends JournalEvent> void addEventListener(Class<T> cls, Consumer<T> consumer) {
		listeners.put(cls, consumer);
	}

	public <T extends JournalEvent> void removeEventListener(Class<T> cls, Consumer<T> consumer) {
		listeners.remove(cls, consumer);
	}

	public <T extends JournalEvent> void removeEventListener(Consumer<T> consumer) {
		listeners.values().removeIf(c -> c == consumer);
	}

	@SuppressWarnings("unchecked")
	private <T extends JournalEvent> void handleEvent(T event) {
		listeners.forEach((c, a) -> {
			if (c.isInstance(event)) ((Consumer<JournalEvent>) a).accept(event);
		});
	}

	public void showErrorMessage(String title, String header, Throwable t) {
		Platform.runLater(() -> {
			Alert alert = new Alert(AlertType.ERROR);

			alert.setTitle(title);
			alert.setHeaderText(header);
			alert.setContentText(t.toString());

			TextArea textArea = new TextArea(Utils.getStackTraceAsString(t));
			textArea.setEditable(false);
			textArea.setWrapText(true);

			textArea.setMaxHeight(Double.MAX_VALUE);
			textArea.setMaxWidth(Double.MAX_VALUE);

			VBox content = new VBox(new Label("Complete stack trace:"), textArea);
			alert.getDialogPane().setExpandableContent(content);

			alert.showAndWait();
		});
	}

	public void addView(String name, Node content) {
		TitledPane pane = new TitledPane(name, content);
		viewPane.getChildren().add(pane);
	}

	@Override
	public void init() throws URISyntaxException, IOException {
		log.info("Loading config");
		if (Files.exists(getConfigFile())) {
			try (Reader r = Files.newBufferedReader(getConfigFile())) {
/*				config = new Config(
						gson.<Map<String, Object>>fromJson(r, new TypeToken<Map<String, Object>>() {}.getType()));*/
				config = new Config(gson.fromJson(r, new TypeToken<Map<String, JsonElement>>() {}.getType()));
			} catch (JsonIOException | JsonSyntaxException | IOException e) {
				log.error("Error loading config from {}", getConfigFile(), e);
				config = new Config();
			}
		} else {
			config = new Config();
		}

		log.info("Loading fonts");
		loadFonts();

		log.info("Initializing journal");
		journal = Journal.create();

		log.info("Starting event listener");
		Thread eventListener = new Thread(() -> journal.liveEvents().forEach(EDScan.this::handleEvent));
		eventListener.setDaemon(true);
		eventListener.setName("EDScan Event Listener");
		eventListener.start();

		log.info("Loading plugins");
		Path pluginDir = getDataDirectory().resolve("plugins");
		Files.createDirectories(pluginDir);
		URLClassLoader classLoader = new URLClassLoader(new URL[] { pluginDir.toUri().toURL() });
		pluginManager = PluginManager.loadPlugins(this, ServiceLoader.load(Plugin.class, classLoader));

		log.info("Initialization complete");
	}

	@Override
	public void start(Stage primaryStage) throws IOException {
		ScrollPane scroll = new ScrollPane(viewPane = new VBox());
		scroll.setFitToWidth(true);

		Scene scene = new Scene(new BorderPane(scroll));

		pluginManager.init();

		primaryStage.setScene(scene);
		primaryStage.setTitle("EDScan");
		primaryStage.setMinHeight(300);
		primaryStage.setMinWidth(400);
		primaryStage.show();
	}

	@Override
	public void stop() throws IOException {
		log.info("Cleaning up plugins");
		pluginManager.cleanup();

		log.info("Writing config");
		Files.write(getConfigFile(), gson.toJson(config.getMap()).getBytes());
	}
}
