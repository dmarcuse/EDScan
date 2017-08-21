package me.apemanzilla.edscan.plugins;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.controlsfx.control.ToggleSwitch;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.gson.*;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.apemanzilla.edjournal.JournalUtils;
import me.apemanzilla.edjournal.events.*;
import me.apemanzilla.edscan.EDScan;
import me.apemanzilla.edscan.Plugin;

@Slf4j
@AutoService(Plugin.class)
public class EDDNSync extends Plugin {
	private static final String schema = "https://eddn.edcd.io/schemas/journal/1";
	private static final URI apiUri = URI.create("https://eddn.edcd.io:4430/upload/");

	private static final JsonSerializer<Instant> instantAdapter = (JsonSerializer<Instant>) (v, t, c) -> {
		return new JsonPrimitive(JournalUtils.timestampFormat.format(Date.from(v)));
	};

	private static final List<String> blacklistedProperties = ImmutableList.of("CockpitBreach", "BoostUsed",
			"FuelLevel", "FuelUsed", "JumpDist", "Latitude", "Longitude");

	private static final ExclusionStrategy excluder = new ExclusionStrategy() {
		@Override
		public boolean shouldSkipField(FieldAttributes f) {
			return blacklistedProperties.stream().anyMatch(f.getName()::equalsIgnoreCase);
		}

		@Override
		public boolean shouldSkipClass(Class<?> clazz) {
			return false;
		}
	};

	private static final Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
			.registerTypeAdapter(Instant.class, instantAdapter).addSerializationExclusionStrategy(excluder).create();

	private String lastSystem;
	private double[] lastSystemPos;

	private BlockingQueue<JsonObject> journalMessageQueue = new LinkedBlockingQueue<>();

	private String getAnonymousUUID() {
		if (!edscan.getConfig().hasKey("eddn.anonymousUUID")) {
			edscan.getConfig().put("eddn.anonymousUUID", UUID.randomUUID().toString());
			try {
				edscan.saveConfig();
			} catch (IOException e) {
				log.error("Exception saving config", e);

				edscan.showErrorMessage("Config saving error", "There was an error saving the config.", e);
			}
		}

		return edscan.getConfig().getAs(String.class, "eddn.anonymousUUID").get();
	}

	@Override
	public String getName() {
		return "EDDN Sync";
	}

	@Override
	public String getDescription() {
		return "Submits limited journal data to EDDN";
	}

	private void handle(JournalEvent event) {
		if (event instanceof FSDJump) {
			lastSystem = ((FSDJump) event).getStarSystem();
			lastSystemPos = ((FSDJump) event).getStarPos();
		} else if (event instanceof Location) {
			lastSystem = ((Location) event).getStarSystem();
			lastSystemPos = ((Location) event).getStarPos();
		}

		JsonObject json = gson.toJsonTree(event).getAsJsonObject();

		if (!json.has("StarSystem")) json.addProperty("StarSystem", lastSystem);
		if (!json.has("StarPos")) json.add("StarPos", gson.toJsonTree(lastSystemPos, double[].class));

		if (edscan.getConfig().getAsOr(Boolean.class, "eddn.submit", false)) journalMessageQueue.offer(json);
	}

	@Override
	public void init() throws Exception {
		String commanderName = edscan.getJournal().lastEventOfType(LoadGame.class).map(LoadGame::getCommander)
				.orElse("Unknown");

		edscan.addView("EDDN Sync", new EDDNSyncController());

		Optional<FSDJump> lastJump = edscan.getJournal().lastEventOfType(FSDJump.class);
		if (lastJump.isPresent()) {
			lastSystem = lastJump.get().getStarSystem();
			lastSystemPos = lastJump.get().getStarPos();
		} else {
			Location location = edscan.getJournal().lastEventOfType(Location.class).get();
			lastSystem = location.getStarSystem();
			lastSystemPos = location.getStarPos();
		}

		edscan.addEventListener(FSDJump.class, this::handle);
		edscan.addEventListener(Scan.class, this::handle);
		edscan.addEventListener(Docked.class, this::handle);
		edscan.addEventListener(Location.class, this::handle);

		Thread submitter = new Thread(() -> {
			while (true) {
				try {
					JsonObject packet = new JsonObject();
					packet.add("message", journalMessageQueue.take());

					JsonObject header = new JsonObject();

					if (edscan.getConfig().getAsOr(Boolean.class, "eddn.anonymize", true)) {
						header.addProperty("uploaderID", getAnonymousUUID().toString());
					} else {
						header.addProperty("uploaderID", commanderName);
					}

					header.addProperty("softwareName", "EDScan");
					header.addProperty("softwareVersion", EDScan.getVersion().orElse("unknown"));
					packet.add("header", header);

					packet.addProperty("$schemaRef", schema);

					log.debug("Submitting packet to EDDN: {}", packet);

					HttpResponse<String> response = Unirest.post(apiUri.toString())
							.header("Content-Type", "application/json").body(packet.toString()).asString();

					if (response.getStatus() != 200) throw new IllegalStateException(
							"Got HTTP code " + response.getStatus() + ": " + response.getBody());
				} catch (InterruptedException e) {
					return;
				} catch (UnirestException e) {
					log.error("Unexpected exception submitting data to EDDN", e);
					edscan.showErrorMessage("EDDN Submission Error",
							"There was an unexpected error submitting data to EDDN.", e);
				}
			}
		});

		submitter.setDaemon(true);
		submitter.setPriority(Thread.MIN_PRIORITY);
		submitter.setName("EDDN Submitter");

		submitter.start();
	}

	public class EDDNSyncController extends GridPane implements Initializable {
		@FXML
		private CheckBox anonymousCheckbox;

		@FXML
		private ToggleSwitch submitSwitch;

		@FXML
		private TextField uuidField;

		@SneakyThrows(IOException.class)
		public EDDNSyncController() {
			FXMLLoader loader = new FXMLLoader(EDDNSync.class.getResource("EDDNSync.fxml"));

			loader.setController(this);
			loader.setRoot(this);

			loader.load();
		}

		@Override
		public void initialize(URL location, ResourceBundle resources) {
			edscan.getConfig().bindAndSet("eddn.anonymize", anonymousCheckbox.selectedProperty(), true);
			edscan.getConfig().bindAndSet("eddn.submit", submitSwitch.selectedProperty(), false);

			uuidField.setText(getAnonymousUUID().toString());
		}
	}
}
