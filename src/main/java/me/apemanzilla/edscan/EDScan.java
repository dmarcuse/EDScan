package me.apemanzilla.edscan;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.apemanzilla.edjournal.Journal;
import me.apemanzilla.edjournal.events.JournalEvent;

@Slf4j
public class EDScan extends Application {
	public static Optional<String> getVersion() {
		return Optional.ofNullable(EDScan.class.getPackage().getImplementationVersion());
	}

	public static void main(String[] args) {
		log.info("Launching EDScan {}", getVersion().orElse(""));
		log.info("Arguments: {}", Arrays.toString(args));
		launch(args);
	}

	@Getter
	private Journal journal;

	@Getter(AccessLevel.PACKAGE)
	private PluginManager pluginManager;

	private Multimap<Class<?>, Consumer<? extends JournalEvent>> listeners = MultimapBuilder.hashKeys().hashSetValues()
			.build();

	public class EDScanController extends BorderPane {
		@SneakyThrows(IOException.class)
		private EDScanController() {
			FXMLLoader loader = new FXMLLoader(EDScan.class.getResource("EDScan.fxml"));

			loader.setController(this);
			loader.setRoot(this);

			loader.load();
		}

		@FXML
		private VBox viewPane;

		@FXML
		private void quit() {
			Platform.exit();
		}

		@FXML
		private void pluginMgr() {
			Stage dialog = new Stage();
			PluginManagerView pv = new PluginManagerView(EDScan.this);
			dialog.setScene(new Scene(pv));

			dialog.setMinWidth(650);
			dialog.setMinHeight(250);
			dialog.setHeight(250);
			dialog.setTitle("Plugin Manager");

			dialog.initOwner(primaryStage);
			
			dialog.show();
		}

		@FXML
		private void about() {
			Stage dialog = new Stage(StageStyle.UTILITY);
			AboutView about = new AboutView();
			
			dialog.setScene(new Scene(about, 200, 200));
			dialog.setTitle("About EDScan");
			dialog.setResizable(false);
			
			dialog.initOwner(primaryStage);
			
			dialog.show();
		}
	}

	private EDScanController controller;

	private Stage primaryStage;

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

	public Path getPluginDirectory() {
		return getDataDirectory().resolve("plugins");
	}

	public Path getConfigFile() {
		return getDataDirectory().resolve("config.json");
	}

	public void saveConfig() throws IOException {
		log.info("Writing config");
		config.save(getConfigFile());
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

			alert.initOwner(primaryStage);
			
			alert.showAndWait();
		});
	}

	void addView(String name, Node content) {
		TitledPane pane = new TitledPane(name, content);
		List<Node> children = controller.viewPane.getChildren();

		int i;

		for (i = 0; i < children.size(); i++) {
			Node n = children.get(i);

			if (n instanceof TitledPane) {
				if (name.compareToIgnoreCase(((TitledPane) n).getText()) < 0) break;
			}
		}

		children.add(i, pane);
	}

	@Override
	public void init() throws URISyntaxException, IOException {
		log.info("Loading config");
		if (Files.exists(getConfigFile())) {
			try (Reader r = Files.newBufferedReader(getConfigFile())) {
				config = Config.load(getConfigFile());
			} catch (JsonIOException | JsonSyntaxException | IOException e) {
				log.error("Error loading config from {}", getConfigFile(), e);
				config = new Config();
			}
		} else {
			config = new Config();
		}

		log.info("Initializing journal");
		journal = Journal.create();

		log.info("Starting event listener");
		Thread eventListener = new Thread(() -> journal.liveEvents().forEach(EDScan.this::handleEvent));
		eventListener.setDaemon(true);
		eventListener.setName("EDScan Event Listener");
		eventListener.start();

		log.info("Loading plugins");
		Files.createDirectories(getPluginDirectory());
		URLClassLoader classLoader = new URLClassLoader(new URL[] { getPluginDirectory().toUri().toURL() });
		pluginManager = PluginManager.loadPlugins(this, ServiceLoader.load(Plugin.class, classLoader));

		log.info("Initialization complete");
	}

	@Override
	public void start(Stage primaryStage) throws IOException {
		controller = new EDScanController();

		pluginManager.init();
		pluginManager.addViews();
		
		Scene scene = new Scene(controller);

		primaryStage.setScene(scene);

		primaryStage.setMinHeight(300);
		primaryStage.setMinWidth(400);

		config.bind("edscan.height", primaryStage.heightProperty());
		config.bind("edscan.width", primaryStage.widthProperty());
		config.bind("edscan.x", primaryStage.xProperty());
		config.bind("edscan.y", primaryStage.yProperty());

		config.getAs(Double.class, "edscan.height").ifPresent(primaryStage::setHeight);
		config.getAs(Double.class, "edscan.width").ifPresent(primaryStage::setWidth);
		config.getAs(Double.class, "edscan.x").ifPresent(primaryStage::setX);
		config.getAs(Double.class, "edscan.y").ifPresent(primaryStage::setY);

		primaryStage.setTitle("EDScan" + getVersion().map(v -> " " + v).orElse(""));
		primaryStage.show();
		primaryStage.toFront();
		primaryStage.requestFocus();

		this.primaryStage = primaryStage;
	}

	@Override
	public void stop() throws IOException {
		log.info("Cleaning up plugins");
		pluginManager.cleanup();

		saveConfig();
	}
}
