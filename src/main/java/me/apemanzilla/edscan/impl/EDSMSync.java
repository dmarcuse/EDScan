package me.apemanzilla.edscan.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.controlsfx.control.ToggleSwitch;

import com.google.auto.service.AutoService;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.apemanzilla.edjournal.events.FSDJump;
import me.apemanzilla.edscan.EDScan;
import me.apemanzilla.edscan.Plugin;

@Slf4j
@AutoService(Plugin.class)
public class EDSMSync extends Plugin {
	public static final DateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	static {
		timestampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	private static final Gson gson = new GsonBuilder().registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
		@Override
		public void write(JsonWriter out, Instant value) throws IOException {
			out.value(timestampFormat.format(Date.from(value)));
		}

		@Override
		public Instant read(JsonReader in) throws IOException {
			try {
				return timestampFormat.parse(in.nextString()).toInstant();
			} catch (ParseException e) {
				throw new JsonParseException(e);
			}
		}
	}).create();

	private static final URI apiUri = URI.create("https://www.edsm.net/");

	@Override
	public String getName() {
		return "EDSM Sync";
	}

	@Override
	public String getDescription() {
		return "Submits flight logs and system positions to EDSM";
	}

	@Override
	public void init() throws Exception {
		edscan.addView("EDSM Sync", new EDSMSyncController());
	}

	public class EDSMSyncController extends VBox implements Initializable, Consumer<FSDJump> {
		@FXML
		private TextField username;

		@FXML
		private PasswordField apiKey;

		@FXML
		private ToggleSwitch submitSwitch;

		@FXML
		private Label totalSubmitted, totalDiscovered;

		private final Object submitLock = new Object();

		private final TimerTask submitter = new TimerTask() {
			public void run() {
				if (!submitSwitch.isSelected()) return;
				synchronized (submitLock) {
					try {
						log.info("Starting EDSM sync");
						JsonParser parser = new JsonParser();

						HttpResponse<String> response = Unirest
								.get(apiUri.resolve("api-logs-v1/get-position").toString())
								.queryString(getCredentials()).asString();

						JsonObject o = parser.parse(response.getBody()).getAsJsonObject();
						int status = o.get("msgnum").getAsInt();
						if (status != 100) throw new IllegalStateException(o.get("msg").getAsString());

						Instant lastSent = gson.fromJson(o.get("date"), Instant.class);
						log.info("Last EDSM submission was at {}", lastSent);

						List<FSDJump> toSend = edscan.getJournal().events(FSDJump.class)
								.filter(j -> j.getTimestamp().isAfter(lastSent)).collect(Collectors.toList());

						if (toSend.isEmpty()) {
							log.info("No events to send!");
						} else {
							log.info("{} event(s) to send", toSend.size());

							for (FSDJump j : toSend) {
								HashMap<String, Object> params = getCredentials();

								params.put("systemName", j.getStarSystem());
								params.put("dateVisited", timestampFormat.format(Date.from(j.getTimestamp())));
								params.put("x", j.getStarPos()[0]);
								params.put("y", j.getStarPos()[1]);
								params.put("z", j.getStarPos()[2]);
								params.put("fromSoftware", "EDScan");
								params.put("fromSoftwareVersion", EDScan.getVersion().orElse("unknown"));

								response = Unirest.get(apiUri.resolve("api-logs-v1/set-log").toString())
										.queryString(params).asString();

								o = parser.parse(response.getBody()).getAsJsonObject();
								status = o.get("msgnum").getAsInt();
								if (status != 100) throw new IllegalStateException(o.get("msg").getAsString());

								log.debug("Submitted system {}", j.getStarSystem());

								Integer submitted = edscan.getConfig().getAsOr(Integer.class, "edsm.totalSubmitted", 0);
								Integer discovered = edscan.getConfig().getAsOr(Integer.class, "edsm.totalDiscovered",
										0);

								submitted++;
								if (o.has("systemCreated") && o.get("systemCreated").getAsBoolean()) discovered++;

								edscan.getConfig().put("edsm.totalSubmitted", submitted.toString());
								edscan.getConfig().put("edsm.totalDiscovered", discovered.toString());

								final Integer s = submitted;
								final Integer d = discovered;

								Platform.runLater(() -> {
									totalSubmitted.setText(s.toString());
									totalDiscovered.setText(d.toString());
								});

							}
						}

						log.info("EDSM sync complete");
					} catch (IllegalStateException | JsonParseException | UnirestException e) {
						log.error("EDSM sync failed", e);

						edscan.showErrorMessage("EDSM Sync failed", "There was an error syncing flight logs to EDSM.",
								e);
					}
				}
			}
		};

		@SneakyThrows(IOException.class)
		public EDSMSyncController() {
			FXMLLoader loader = new FXMLLoader(EDSMSync.class.getResource("EDSMSync.fxml"));

			loader.setController(this);
			loader.setRoot(this);

			loader.load();
		}

		@Override
		public void initialize(URL location, ResourceBundle resources) {
			edscan.getConfig().bindAndSet("edsm.cmdr", username.textProperty());
			edscan.getConfig().bindAndSet("edsm.apiKey", apiKey.textProperty(), "");
			edscan.getConfig().bindAndSet("edsm.submit", submitSwitch.selectedProperty(), false);

			username.disableProperty().bind(submitSwitch.selectedProperty());
			apiKey.disableProperty().bind(submitSwitch.selectedProperty());

			edscan.addEventListener(FSDJump.class, this);

			totalSubmitted.setText(edscan.getConfig().getAsOr(Integer.class, "edsm.totalSubmitted", 0).toString());
			totalDiscovered.setText(edscan.getConfig().getAsOr(Integer.class, "edsm.totalDiscovered", 0).toString());

			Timer t = new Timer("EDSM Submitter", true);
			t.scheduleAtFixedRate(submitter, 2000, 5 * 60 * 1000);
		}

		private HashMap<String, Object> getCredentials() {
			HashMap<String, Object> map = new HashMap<>();

			edscan.getConfig().getAs(String.class, "edsm.cmdr").ifPresent(c -> map.put("commanderName", c));
			edscan.getConfig().getAs(String.class, "edsm.apiKey").ifPresent(k -> map.put("apiKey", k));

			return map;
		}

		public void accept(FSDJump jump) {

		}

		@FXML
		private void openAPIPage() {
			edscan.getHostServices().showDocument("https://www.edsm.net/en/settings/api");
		}
	}
}
