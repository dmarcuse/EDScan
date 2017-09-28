package me.apemanzilla.edscan.plugins;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.controlsfx.control.textfield.AutoCompletionBinding.ISuggestionRequest;
import org.controlsfx.control.textfield.TextFields;

import com.google.auto.service.AutoService;
import com.google.common.primitives.Ints;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.text.TextFlow;
import lombok.Data;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.apemanzilla.edjournal.events.FSDJump;
import me.apemanzilla.edscan.Plugin;
import me.apemanzilla.edscan.plugins.NeutronHighway.Route.Jump;

@Slf4j
@AutoService(Plugin.class)
public class NeutronHighway extends Plugin {
	private static final URI api = URI.create("https://www.spansh.co.uk/api/");

	private static final Gson gson = new GsonBuilder()
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

	@Override
	public String getName() {
		return "Neutron Highway";
	}

	@Override
	public String getDescription() {
		return "An interactive client for Spansh's neutron plotter that tracks progress and copies system names automatically";
	}

	private BorderPane viewWrapper;

	private void switchToForm() {
		log.info("Switching to form");
		edscan.getConfig().remove("neutronHighway.route");
		viewWrapper.setCenter(new FormController());
	}

	private void switchToRoute(Route route) {
		log.info("Switching to route");
		edscan.getConfig().put("neutronHighway.route", route);
		viewWrapper.setCenter(new RouteController(route));
		route.addListener(r -> edscan.getConfig().put("neutronHighway.route", route));
	}

	@Override
	public void init() throws Exception {
		viewWrapper = new BorderPane();

		Hyperlink link = new Hyperlink("Spansh's Neutron Plotter");
		link.setOnAction(e -> edscan.getHostServices().showDocument("https://www.spansh.co.uk/"));
		TextFlow credits = new TextFlow(new Label("Based on "), link);
		credits.setOpacity(0.75);

		viewWrapper.setBottom(credits);

		Optional<Route> route = edscan.getConfig().getAs(Route.class, "neutronHighway.route");

		if (route.isPresent())
			switchToRoute(route.get());
		else
			switchToForm();
	}

	@Override
	public Optional<Callable<Node>> getViewBuilder() {
		return Optional.of(() -> viewWrapper);
	}

	public class FormController extends GridPane implements Initializable {
		@FXML
		private GridPane root;

		@FXML
		private Button submitBtn;

		@FXML
		private TextField fromField, toField;

		@FXML
		private Slider rangeSlider, effSlider;

		@FXML
		private Label rangeLabel, effLabel;

		@SneakyThrows(IOException.class)
		public FormController() {
			FXMLLoader loader = new FXMLLoader(NeutronHighway.class.getResource("NeutronHighwayForm.fxml"));

			loader.setController(this);
			loader.setRoot(this);

			loader.load();
		}

		private List<String> completeSystemName(ISuggestionRequest request) {
			String s = request.getUserText().trim();

			if (s.length() < 2 || s.isEmpty()) return Collections.emptyList();

			try {
				return gson.fromJson(
						Unirest.get(api.resolve("systems").toString()).queryString("q", s).asString().getBody(),
						new TypeToken<List<String>>() {}.getType());

			} catch (UnirestException e) {
				log.error("Error fetching system completions for {}", s, e);
				return Collections.emptyList();
			}
		}

		@Override
		public void initialize(URL location, ResourceBundle resources) {
			edscan.getConfig().bindAndSet("neutronHighway.jumpRange", rangeSlider.valueProperty(), 30.0);
			edscan.getConfig().bindAndSet("neutronHighway.efficiency", effSlider.valueProperty(), 25.0);

			rangeLabel.textProperty().bind(Bindings.format("%.1fly", rangeSlider.valueProperty()));
			effLabel.textProperty().bind(Bindings.format("%.0f%%", effSlider.valueProperty()));

			TextFields.bindAutoCompletion(fromField, this::completeSystemName);
			TextFields.bindAutoCompletion(toField, this::completeSystemName);

			edscan.getJournal().lastEventOfType(FSDJump.class).map(FSDJump::getStarSystem)
					.ifPresent(fromField::setPromptText);
			edscan.addEventListener(FSDJump.class, j -> fromField.setPromptText(j.getStarSystem()));
		}

		@FXML
		private void plot() {
			setDisable(true);

			final Map<String, Object> params = new HashMap<>();
			params.put("from", fromField.getText().trim().isEmpty() ? fromField.getPromptText().trim()
					: fromField.getText().trim());

			params.put("to", toField.getText().trim());
			params.put("range", rangeSlider.getValue());
			params.put("efficiency", effSlider.getValue());

			Thread t = new Thread(() -> {
				try {
					log.info("Submitting route request, params {}", params.toString());

					HttpResponse<String> response = Unirest.post(api.resolve("route").toString()).queryString(params)
							.asString();

					JsonParser parser = new JsonParser();
					JsonObject o = parser.parse(response.getBody()).getAsJsonObject();

					if (!o.has("job")) {
						if (o.has("error")) {
							String e = o.get("error").getAsString();
							Platform.runLater(() -> {
								new Alert(AlertType.ERROR, e).showAndWait();
								setDisable(false);
							});
							return;
						} else {
							throw new IllegalStateException("Unknown remote error " + response.getBody());
						}
					}

					String job = o.get("job").getAsString();
					log.info("Route job ID {}", job);

					do {
						Thread.sleep(1000);
					} while ((response = Unirest.get(api.resolve("results/" + job.trim()).toString()).asString())
							.getStatus() == 202);

					o = parser.parse(response.getBody()).getAsJsonObject();

					if (!o.has("result")) {
						if (o.has("error")) {
							String e = o.get("error").getAsString();
							Platform.runLater(() -> {
								new Alert(AlertType.ERROR, e).showAndWait();
								setDisable(false);
							});
							return;
						} else {
							throw new IllegalStateException("Unknown remote error " + response.getBody());
						}
					}

					Route route = gson.fromJson(o.get("result"), Route.class);
					log.info("Route acquired!");

					Platform.runLater(() -> switchToRoute(route));
				} catch (IllegalStateException | InterruptedException | JsonParseException | UnirestException e) {
					log.error("Error plotting route params {}:", params, e);

					edscan.showErrorMessage("Error plotting route", "There was an error plotting the route.", e);
					Platform.runLater(() -> setDisable(false));
				}
			});

			t.setDaemon(true);
			t.setName("Neutron Highway Plotter");
			t.start();
		}
	}

	@Data
	public static class Route implements Observable {
		@Data
		public static class Jump {
			String system;
			int jumps;
			double distanceLeft;
		}

		String destinationSystem;
		String sourceSystem;
		List<Jump> systemJumps;
		int progress;
		transient Set<InvalidationListener> listeners;

		private Route() {
			progress = 0;
			listeners = new HashSet<>();
		}

		public Optional<Jump> getJump(int i) {
			if (i < 0 || i >= systemJumps.size())
				return Optional.empty();
			else
				return Optional.of(systemJumps.get(i));
		}

		public Optional<Jump> getCurrentJump() {
			return getJump(progress);
		}

		public Optional<Jump> getNextJump() {
			return getJump(progress + 1);
		}

		public void changeProgress(int amt) {
			progress = Ints.constrainToRange(progress + amt, 0, getSystemJumps().size() - 1);
			listeners.forEach(l -> l.invalidated(this));
		}

		public void incrementProgress() {
			changeProgress(1);
		}

		public void decrementProgress() {
			changeProgress(-1);
		}

		public int totalJumps() {
			return systemJumps.stream().mapToInt(Jump::getJumps).sum();
		}

		public int completedJumps() {
			return systemJumps.stream().limit(progress + 1).mapToInt(Jump::getJumps).sum();
		}

		@Override
		public void addListener(InvalidationListener listener) {
			listeners.add(listener);
		}

		@Override
		public void removeListener(InvalidationListener listener) {
			listeners.remove(listener);
		}
	}

	public class RouteController extends GridPane implements Initializable, Consumer<FSDJump> {
		@Getter
		private final Route route;

		@FXML
		private Button clearBtn, copyBtn, skipBtn, prevBtn;

		@FXML
		private Label nextLabel, destLabel, currentJumpLabel, totalJumpsLabel, distanceRemainingLabel;

		@FXML
		private ProgressBar progress;

		@FXML
		private CheckBox autoCopy;

		@SneakyThrows(IOException.class)
		public RouteController(Route route) {
			this.route = route;

			FXMLLoader loader = new FXMLLoader(NeutronHighway.class.getResource("NeutronHighwayRoute.fxml"));

			loader.setController(this);
			loader.setRoot(this);

			loader.load();
		}

		@Override
		public void initialize(URL location, ResourceBundle resources) {
			edscan.addEventListener(FSDJump.class, this);
			destLabel.setText(route.getDestinationSystem());
			edscan.getConfig().bindAndSet("neutronHighway.autoCopy", autoCopy.selectedProperty(), false);

			updateProgress();
		}

		@FXML
		private void copyNextSystem() {
			route.getNextJump().map(Jump::getSystem).ifPresent(s -> {
				Clipboard.getSystemClipboard().setContent(Collections.singletonMap(DataFormat.PLAIN_TEXT, s));
				Platform.runLater(() -> {
					copyBtn.setText("Copied");
					copyBtn.setDisable(true);

					new Thread(() -> {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {}
						Platform.runLater(() -> copyBtn.setDisable(false));
					}).start();
				});
			});
		}

		@FXML
		private void clearRoute() {
			edscan.removeEventListener(this);
			switchToForm();
		}

		private void updateProgress() {
			nextLabel.setText(route.getNextJump().map(Jump::getSystem).orElse("Done!"));

			progress.setProgress((double) route.completedJumps() / route.totalJumps());
			// progressLabel.setText(String.format("%d / %d jumps (%.1f ly remaining)",
			// route.completedJumps(),
			// route.totalJumps(),
			// route.getCurrentJump().map(Jump::getDistanceLeft).get()));
			currentJumpLabel.setText("" + route.completedJumps());
			totalJumpsLabel.setText("" + route.totalJumps());
			distanceRemainingLabel
					.setText(String.format("%.1f", route.getCurrentJump().map(Jump::getDistanceLeft).orElse(0.0)));

			if (autoCopy.isSelected()) copyNextSystem();
		}

		@FXML
		private void skipJump() {
			route.incrementProgress();
			updateProgress();
		}

		@FXML
		private void previousJump() {
			route.changeProgress(-1);
			updateProgress();
		}

		@Override
		public void accept(FSDJump jump) {
			route.getNextJump().map(Jump::getSystem).ifPresent(s -> {
				if (s.equalsIgnoreCase(jump.getStarSystem())) {
					route.incrementProgress();

					Platform.runLater(this::updateProgress);
				}
			});
		}
	}
}
